#ifndef RULES_RUST_COMPILER_SCHEDULER_WINDOWS_H_
#define RULES_RUST_COMPILER_SCHEDULER_WINDOWS_H_

#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <winsock2.h>
#include <windows.h>
#include <afunix.h>
#include <tlhelp32.h>

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <cwchar>
#include <filesystem>
#include <memory>
#include <random>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace compiler_scheduler {

enum Command : uint32_t {
  START = 1, PAUSE = 2, RESUME = 3, KILL = 4, RETRY = 5, FAIL = 6,
  CANCEL = 7, STARTED = 8, EXITED = 9, CANCELLED = 10, KILLED = 11,
  EXTERNALLY_KILLED = 12
};

struct Packet {
  uint32_t command;
  uint32_t generation;
  uint32_t value;
};

class Handle {
 public:
  explicit Handle(HANDLE value = nullptr) : value_(value) {}
  Handle(const Handle&) = delete;
  Handle& operator=(const Handle&) = delete;
  Handle(Handle&& other) noexcept : value_(other.release()) {}
  ~Handle() { reset(); }
  HANDLE get() const { return value_; }
  bool valid() const { return value_ != nullptr && value_ != INVALID_HANDLE_VALUE; }
  HANDLE release() { HANDLE value = value_; value_ = nullptr; return value; }
  void reset(HANDLE value = nullptr) {
    if (valid()) CloseHandle(value_);
    value_ = value;
  }

 private:
  HANDLE value_;
};

inline std::wstring wide(const char* text) {
  int length = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, text, -1, nullptr, 0);
  if (length == 0) throw std::runtime_error("invalid UTF-8 compiler argument");
  std::vector<wchar_t> buffer(static_cast<size_t>(length));
  if (MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, text, -1, buffer.data(), length) == 0)
    throw std::runtime_error("cannot convert compiler argument");
  return std::wstring(buffer.data());
}

inline std::wstring quote(const std::wstring& argument) {
  std::wstring result(1, L'"');
  size_t slashes = 0;
  for (wchar_t character : argument) {
    if (character == L'\\') { ++slashes; continue; }
    result.append(character == L'"' ? slashes * 2 + 1 : slashes, L'\\');
    result.push_back(character);
    slashes = 0;
  }
  result.append(slashes * 2, L'\\');
  result.push_back(L'"');
  return result;
}

class Connection {
 public:
  Connection() : socket_(INVALID_SOCKET), used_(0), initialized_(false) {}
  Connection(const Connection&) = delete;
  Connection& operator=(const Connection&) = delete;
  ~Connection() {
    if (socket_ != INVALID_SOCKET) closesocket(socket_);
    if (initialized_) WSACleanup();
  }

  bool connect(const char* endpoint) {
    WSADATA data;
    if (WSAStartup(MAKEWORD(2, 2), &data) != 0) return false;
    initialized_ = true;
    socket_ = WSASocketW(AF_UNIX, SOCK_STREAM, 0, nullptr, 0, WSA_FLAG_NO_HANDLE_INHERIT);
    if (socket_ == INVALID_SOCKET) return false;

    // Only the basename enters sockaddr_un; restore the compiler's directory first.
    std::filesystem::path path(wide(endpoint));
    std::filesystem::path original = std::filesystem::current_path();
    std::string name = path.filename().u8string();
    if (name.size() >= sizeof(SOCKADDR_UN::sun_path)) return false;
    SOCKADDR_UN address = {};
    address.sun_family = AF_UNIX;
    std::memcpy(address.sun_path, name.c_str(), name.size() + 1);
    std::error_code failure;
    if (path.has_parent_path()) std::filesystem::current_path(path.parent_path(), failure);
    if (failure) return false;
    int result = ::connect(socket_, reinterpret_cast<sockaddr*>(&address), static_cast<int>(sizeof(address)));
    std::filesystem::current_path(original, failure);
    if (result == SOCKET_ERROR || failure) return false;
    u_long nonblocking = 1;
    return ioctlsocket(socket_, FIONBIO, &nonblocking) == 0;
  }

  bool send(Command command, uint32_t generation, uint32_t value) {
    uint32_t packet[] = {htonl(command), htonl(generation), htonl(value)};
    int sent = 0;
    while (sent < static_cast<int>(sizeof(packet))) {
      int count = ::send(socket_, reinterpret_cast<const char*>(packet) + sent,
                         static_cast<int>(sizeof(packet)) - sent, 0);
      if (count > 0) sent += count;
      else if (count == SOCKET_ERROR && WSAGetLastError() == WSAEWOULDBLOCK) {
        if (ready(true) < 0) return false;
      } else return false;
    }
    return true;
  }

  int receive(Packet* packet) {
    int selected = ready(false);
    if (selected <= 0) return selected;
    int count = recv(socket_, reinterpret_cast<char*>(buffer_) + used_,
                     static_cast<int>(sizeof(buffer_)) - used_, 0);
    if (count <= 0)
      return count == SOCKET_ERROR && WSAGetLastError() == WSAEWOULDBLOCK ? 0 : -1;
    used_ += count;
    if (used_ < static_cast<int>(sizeof(buffer_))) return 0;
    packet->command = ntohl(buffer_[0]);
    packet->generation = ntohl(buffer_[1]);
    packet->value = ntohl(buffer_[2]);
    used_ = 0;
    return 1;
  }

 private:
  int ready(bool writing) const {
    fd_set descriptors;
    FD_ZERO(&descriptors);
    FD_SET(socket_, &descriptors);
    timeval timeout = {0, 20000};
    int result = select(0, writing ? nullptr : &descriptors,
                        writing ? &descriptors : nullptr, nullptr, &timeout);
    return result == SOCKET_ERROR ? -1 : result;
  }

  SOCKET socket_;
  int used_;
  bool initialized_;
  uint32_t buffer_[3];
};

class Attempt {
 public:
  Attempt() = default;
  Attempt(const Attempt&) = delete;
  Attempt& operator=(const Attempt&) = delete;
  ~Attempt() {
    if (!stop()) {
      job_.reset();
      std::fprintf(stderr, "compiler_scheduler: cannot confirm child cleanup; retaining attempt directory\n");
      return;
    }
    output_.reset();
    error_.reset();
    if (!directory_.empty()) {
      std::error_code failure;
      std::filesystem::remove_all(directory_, failure);
      if (failure) std::fprintf(stderr, "compiler_scheduler: cannot remove attempt directory\n");
    }
  }

  bool launch(char* const* arguments) {
    std::random_device random;
    auto root = std::filesystem::temp_directory_path();
    for (int tries = 0; tries < 32; ++tries) {
      auto candidate = root / (L"codex-compile-" + std::to_wstring(random()));
      std::error_code failure;
      if (std::filesystem::create_directory(candidate, failure)) {
        directory_ = std::move(candidate);
        break;
      }
      if (failure && failure != std::errc::file_exists) return false;
    }
    if (directory_.empty()) return false;
    SECURITY_ATTRIBUTES security = {sizeof(SECURITY_ATTRIBUTES), nullptr, TRUE};
    output_.reset(CreateFileW((directory_ / L"stdout").c_str(), GENERIC_READ | GENERIC_WRITE,
        FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, &security, CREATE_NEW,
        FILE_ATTRIBUTE_TEMPORARY | FILE_FLAG_DELETE_ON_CLOSE, nullptr));
    error_.reset(CreateFileW((directory_ / L"stderr").c_str(), GENERIC_READ | GENERIC_WRITE,
        FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, &security, CREATE_NEW,
        FILE_ATTRIBUTE_TEMPORARY | FILE_FLAG_DELETE_ON_CLOSE, nullptr));
    Handle input(CreateFileW(L"NUL", GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE,
                             &security, OPEN_EXISTING, 0, nullptr));
    job_.reset(CreateJobObjectW(nullptr, nullptr));
    if (!output_.valid() || !error_.valid() || !input.valid() || !job_.valid()) return false;
    JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits = {};
    limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
    if (!SetInformationJobObject(job_.get(), JobObjectExtendedLimitInformation,
                                 &limits, sizeof(limits))) return false;

    std::wstring command;
    for (size_t index = 0; arguments[index] != nullptr; ++index) {
      if (index > 0) command.push_back(L' ');
      command += quote(wide(arguments[index]));
    }
    std::vector<wchar_t> writable(command.begin(), command.end());
    writable.push_back(L'\0');
    SIZE_T bytes = 0;
    InitializeProcThreadAttributeList(nullptr, 1, 0, &bytes);
    std::vector<unsigned char> storage(bytes);
    auto attributes = reinterpret_cast<LPPROC_THREAD_ATTRIBUTE_LIST>(storage.data());
    if (!InitializeProcThreadAttributeList(attributes, 1, 0, &bytes)) return false;
    struct AttributesOwner {
      LPPROC_THREAD_ATTRIBUTE_LIST value;
      ~AttributesOwner() { DeleteProcThreadAttributeList(value); }
    } attributes_owner{attributes};
    HANDLE inherited[] = {input.get(), output_.get(), error_.get()};
    bool configured = UpdateProcThreadAttribute(attributes, 0, PROC_THREAD_ATTRIBUTE_HANDLE_LIST,
                                                inherited, sizeof(inherited), nullptr, nullptr) != FALSE;
    STARTUPINFOEXW startup = {};
    startup.StartupInfo.cb = sizeof(startup);
    startup.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
    startup.StartupInfo.hStdInput = input.get();
    startup.StartupInfo.hStdOutput = output_.get();
    startup.StartupInfo.hStdError = error_.get();
    startup.lpAttributeList = attributes;
    std::vector<wchar_t> environment;
    if (!child_environment(&environment)) return false;
    PROCESS_INFORMATION process = {};
    BOOL created = configured && CreateProcessW(nullptr, writable.data(), nullptr, nullptr, TRUE,
        CREATE_SUSPENDED | EXTENDED_STARTUPINFO_PRESENT | CREATE_UNICODE_ENVIRONMENT,
        environment.data(), nullptr,
        &startup.StartupInfo, &process);
    if (!created) return false;
    process_.reset(process.hProcess);
    Handle thread(process.hThread);
    if (!AssignProcessToJobObject(job_.get(), process_.get())) {
      TerminateProcess(process_.get(), 125);
      WaitForSingleObject(process_.get(), INFINITE);
      return false;
    }
    return ResumeThread(thread.get()) != static_cast<DWORD>(-1);
  }

  bool status(DWORD* status, bool* complete) const {
    DWORD waiting = WaitForSingleObject(process_.get(), 0);
    *complete = waiting == WAIT_OBJECT_0;
    return waiting != WAIT_FAILED && (!*complete || GetExitCodeProcess(process_.get(), status));
  }

  bool pause() {
    if (!paused_.empty()) return true;
    while (true) {
      bool added = false;
      Handle snapshot(CreateToolhelp32Snapshot(TH32CS_SNAPTHREAD, 0));
      if (!snapshot.valid()) return false;
      THREADENTRY32 entry = {};
      entry.dwSize = sizeof(entry);
      if (!Thread32First(snapshot.get(), &entry)) return GetLastError() == ERROR_NO_MORE_FILES;
      do {
        Handle process(OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION | SYNCHRONIZE,
                                   FALSE, entry.th32OwnerProcessID));
        if (!process.valid()) continue;
        BOOL owned = FALSE;
        if (!IsProcessInJob(process.get(), job_.get(), &owned)) return false;
        if (!owned || WaitForSingleObject(process.get(), 0) == WAIT_OBJECT_0) continue;
        bool known = false;
        for (const Handle& suspended : paused_)
          if (GetThreadId(suspended.get()) == entry.th32ThreadID) known = true;
        if (known) continue;
        Handle thread(OpenThread(THREAD_SUSPEND_RESUME | THREAD_QUERY_LIMITED_INFORMATION | SYNCHRONIZE,
                                 FALSE, entry.th32ThreadID));
        if (!thread.valid()) {
          if (GetLastError() == ERROR_INVALID_PARAMETER) continue;
          return false;
        }
        if (GetProcessIdOfThread(thread.get()) != entry.th32OwnerProcessID) continue;
        if (SuspendThread(thread.get()) == static_cast<DWORD>(-1)) {
          if (WaitForSingleObject(thread.get(), 0) == WAIT_OBJECT_0) continue;
          return false;
        }
        paused_.push_back(std::move(thread));
        added = true;
      } while (Thread32Next(snapshot.get(), &entry));
      if (GetLastError() != ERROR_NO_MORE_FILES) return false;
      if (!added) return true;
    }
  }

  bool resume() {
    for (const Handle& thread : paused_) {
      if (ResumeThread(thread.get()) == static_cast<DWORD>(-1) &&
          WaitForSingleObject(thread.get(), 0) != WAIT_OBJECT_0) return false;
    }
    paused_.clear();
    return true;
  }

  bool stop() {
    if (!job_.valid()) return true;
    if (!TerminateJobObject(job_.get(), 137)) return false;
    JOBOBJECT_BASIC_ACCOUNTING_INFORMATION accounting = {};
    do {
      if (!QueryInformationJobObject(job_.get(), JobObjectBasicAccountingInformation,
                                     &accounting, sizeof(accounting), nullptr)) return false;
      if (accounting.ActiveProcesses > 0) Sleep(1);
    } while (accounting.ActiveProcesses > 0);
    paused_.clear();
    return true;
  }

  bool replay() const {
    return copy(output_.get(), GetStdHandle(STD_OUTPUT_HANDLE)) &&
           copy(error_.get(), GetStdHandle(STD_ERROR_HANDLE));
  }

 private:
  bool child_environment(std::vector<wchar_t>* result) const {
    std::unique_ptr<wchar_t, decltype(&FreeEnvironmentStringsW)> original(
        GetEnvironmentStringsW(), &FreeEnvironmentStringsW);
    if (original == nullptr) return false;
    std::vector<std::wstring> variables;
    for (const wchar_t* entry = original.get(); *entry != L'\0'; entry += std::wcslen(entry) + 1) {
      if (_wcsnicmp(entry, L"TEMP=", 5) != 0 && _wcsnicmp(entry, L"TMP=", 4) != 0 &&
          _wcsnicmp(entry, L"TMPDIR=", 7) != 0) variables.emplace_back(entry);
    }
    variables.push_back(L"TEMP=" + directory_.wstring());
    variables.push_back(L"TMP=" + directory_.wstring());
    variables.push_back(L"TMPDIR=" + directory_.wstring());
    std::sort(variables.begin(), variables.end(), [](const std::wstring& left, const std::wstring& right) {
      return _wcsicmp(left.c_str(), right.c_str()) < 0;
    });
    for (const std::wstring& variable : variables) {
      result->insert(result->end(), variable.begin(), variable.end());
      result->push_back(L'\0');
    }
    result->push_back(L'\0');
    return true;
  }

  static bool copy(HANDLE source, HANDLE destination) {
    LARGE_INTEGER beginning = {};
    if (!SetFilePointerEx(source, beginning, nullptr, FILE_BEGIN)) return false;
    char buffer[8192];
    DWORD count;
    while (ReadFile(source, buffer, sizeof(buffer), &count, nullptr)) {
      if (count == 0) return true;
      DWORD copied = 0;
      while (copied < count) {
        DWORD written;
        if (!WriteFile(destination, buffer + copied, count - copied, &written, nullptr) || written == 0)
          return false;
        copied += written;
      }
    }
    return false;
  }

  std::filesystem::path directory_;
  Handle output_;
  Handle error_;
  Handle job_;
  Handle process_;
  std::vector<Handle> paused_;
};

inline int run(const char* endpoint, char* const* arguments,
               const std::vector<std::string>& outputs = {}) {
  try {
    Connection connection;
    if (!connection.connect(endpoint)) {
      std::fprintf(stderr, "compiler_scheduler: cannot connect to scheduler\n");
      return 125;
    }
    std::unique_ptr<Attempt> attempt;
    uint32_t generation = 0;
    bool awaiting_retry = false;
    bool killed = false;
    DWORD outcome = 125;
    while (true) {
      Packet packet;
      int received = connection.receive(&packet);
      if (received < 0) return 125;
      if (received > 0) {
        if (packet.command == CANCEL) return 125;
        if (packet.command == START && !attempt && !awaiting_retry && packet.generation > generation) {
          generation = packet.generation;
          killed = false;
          attempt.reset(new Attempt());
          if (!attempt->launch(arguments) || !connection.send(STARTED, generation, 0)) return 125;
        } else if (attempt && !awaiting_retry && packet.generation == generation) {
          if (packet.command == PAUSE) { if (!attempt->pause()) return 125; }
          else if (packet.command == RESUME) { if (!attempt->resume()) return 125; }
          else if (packet.command == KILL) { killed = true; if (!attempt->stop()) return 125; }
          else return 125;
        } else if (packet.command == RETRY && awaiting_retry && packet.generation == generation) {
          attempt.reset();
          awaiting_retry = false;
        } else if (packet.command == FAIL && awaiting_retry && packet.generation == generation) {
          return attempt->replay() ? static_cast<int>(outcome) : 125;
        } else return 125;
      }
      if (!attempt || awaiting_retry) continue;
      bool complete = false;
      if (!attempt->status(&outcome, &complete)) return 125;
      if (!complete) continue;
      if (!attempt->stop()) return 125;
      bool memory_failure = killed || outcome == 0xC0000017UL || outcome == 0xC000012DUL;
      if (memory_failure) {
        for (const std::string& output : outputs) {
          std::error_code failure;
          std::filesystem::remove_all(std::filesystem::path(wide(output.c_str())), failure);
          if (failure) return 125;
        }
        awaiting_retry = true;
        outcome = 137;
      } else if (!attempt->replay()) return 125;
      if (!connection.send(memory_failure ? (killed ? KILLED : EXTERNALLY_KILLED) : EXITED,
                           generation, outcome)) return 125;
      if (!awaiting_retry) return static_cast<int>(outcome);
    }
  } catch (const std::exception& failure) {
    std::fprintf(stderr, "compiler_scheduler: %s\n", failure.what());
    return 125;
  }
}

}  // namespace compiler_scheduler
#endif
