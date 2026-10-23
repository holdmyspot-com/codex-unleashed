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
#include <system_error>
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

// One invocation retains the producer's error before cleanup or another API can replace it.
struct Diagnostic {
  const char* operation = "initialization";
  const char* failure_operation = nullptr;
  const char* error_domain = "none";
  unsigned long error = 0;
  unsigned long crt_os_error = 0;
  DWORD wrapper_pid = GetCurrentProcessId();
  DWORD child_pid = 0;
  uint32_t generation = 0;
  Packet packet = {};
  bool child_exit_known = false;
  DWORD child_exit = 0;
  bool awaiting_retry = false;
  bool killed = false;

  // Saves only the first failure; numeric errors are supplied by their producer.
  bool fail(const char* name, const char* domain, unsigned long code = 0) {
    if (failure_operation == nullptr) {
      failure_operation = name;
      error_domain = domain;
      error = code;
    }
    return false;
  }

  // Reads the thread's last Win32 error before returning to a caller that may clean up.
  bool win32(const char* name) { return fail(name, "win32", GetLastError()); }

  // Records a failed Win32 call without changing its boolean result.
  bool check(BOOL success, const char* name) {
    if (success == FALSE) return win32(name);
    return true;
  }

  // Records an invalid newly created handle before an owning handle can close anything.
  HANDLE handle(HANDLE value, const char* name) {
    if (value == nullptr || value == INVALID_HANDLE_VALUE) win32(name);
    return value;
  }

  // Emits captured state without consulting thread-local OS error slots.
  void print(const char* origin, const char* name, const char* domain, unsigned long code,
             const char* returned = "125") const {
    std::fprintf(stderr,
        "compiler_scheduler: origin=%s operation=%s error_domain=%s error=%lu "
        "wrapper_pid=%lu child_pid=%lu generation=%lu command=%lu packet_generation=%lu "
        "packet_value=%lu child_exit_known=%s child_exit=%lu child_exit_hex=0x%08lX "
        "phase=%s awaiting_retry=%s killed=%s crt_os_error=%lu return=%s\n",
        origin, name, domain, code, wrapper_pid, child_pid,
        static_cast<unsigned long>(generation), static_cast<unsigned long>(packet.command),
        static_cast<unsigned long>(packet.generation), static_cast<unsigned long>(packet.value),
        child_exit_known ? "true" : "false", child_exit, child_exit,
        operation, awaiting_retry ? "true" : "false", killed ? "true" : "false", crt_os_error, returned);
  }

  // Reports a wrapper failure before its owners unwind, preserving status 125.
  int wrapper_exit() {
    if (failure_operation == nullptr) fail(operation, "internal");
    print("wrapper", failure_operation, error_domain, error);
    return 125;
  }

  // Labels a known child status 125 without treating it as a wrapper failure.
  void report_child125(const char* name) const {
    if (child_exit_known && child_exit == 125) print("child", name, "none", 0);
  }
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

inline std::wstring wide(const char* text, Diagnostic& diagnostic) {
  int length = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, text, -1, nullptr, 0);
  if (length == 0) {
    diagnostic.win32("MultiByteToWideChar.size");
    throw std::runtime_error("invalid UTF-8 compiler argument");
  }
  std::vector<wchar_t> buffer(static_cast<size_t>(length));
  if (MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, text, -1, buffer.data(), length) == 0) {
    diagnostic.win32("MultiByteToWideChar.convert");
    throw std::runtime_error("cannot convert compiler argument");
  }
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
  explicit Connection(Diagnostic& diagnostic) : diagnostic_(diagnostic) {}
  Connection(const Connection&) = delete;
  Connection& operator=(const Connection&) = delete;
  ~Connection() {
    if (socket_ != INVALID_SOCKET) closesocket(socket_);
    if (initialized_) WSACleanup();
  }

  bool connect(const char* endpoint) {
    WSADATA data;
    int startup = WSAStartup(MAKEWORD(2, 2), &data);
    if (startup != 0) return diagnostic_.fail("WSAStartup", "wsa_startup", static_cast<unsigned long>(startup));
    initialized_ = true;
    socket_ = WSASocketW(AF_UNIX, SOCK_STREAM, 0, nullptr, 0, WSA_FLAG_NO_HANDLE_INHERIT);
    if (socket_ == INVALID_SOCKET) return diagnostic_.fail("WSASocketW", "wsa", static_cast<unsigned long>(WSAGetLastError()));

    // Only the basename enters sockaddr_un; restore the compiler's directory first.
    diagnostic_.operation = "connect.path";
    std::filesystem::path path(wide(endpoint, diagnostic_));
    std::filesystem::path original = std::filesystem::current_path();
    std::string name = path.filename().u8string();
    if (name.size() >= sizeof(SOCKADDR_UN::sun_path))
      return diagnostic_.fail("connect.basename_length", "configuration");
    SOCKADDR_UN address = {};
    address.sun_family = AF_UNIX;
    std::memcpy(address.sun_path, name.c_str(), name.size() + 1);
    std::error_code failure;
    if (path.has_parent_path()) std::filesystem::current_path(path.parent_path(), failure);
    if (failure) return diagnostic_.fail("connect.change_directory", failure.category().name(), static_cast<unsigned long>(failure.value()));
    int result = ::connect(socket_, reinterpret_cast<sockaddr*>(&address), static_cast<int>(sizeof(address)));
    // Directory restoration can overwrite the failed socket call's error.
    int connect_error = result == SOCKET_ERROR ? WSAGetLastError() : 0;
    if (result == SOCKET_ERROR) diagnostic_.fail("connect", "wsa", static_cast<unsigned long>(connect_error));
    std::filesystem::current_path(original, failure);
    if (failure) diagnostic_.fail("connect.restore_directory", failure.category().name(), static_cast<unsigned long>(failure.value()));
    if (result == SOCKET_ERROR || failure) return false;
    u_long nonblocking = 1;
    if (ioctlsocket(socket_, FIONBIO, &nonblocking) == SOCKET_ERROR)
      return diagnostic_.fail("ioctlsocket", "wsa", static_cast<unsigned long>(WSAGetLastError()));
    return true;
  }

  bool send(Command command, uint32_t generation, uint32_t value) {
    uint32_t packet[] = {htonl(command), htonl(generation), htonl(value)};
    int sent = 0;
    while (sent < static_cast<int>(sizeof(packet))) {
      int count = ::send(socket_, reinterpret_cast<const char*>(packet) + sent,
                         static_cast<int>(sizeof(packet)) - sent, 0);
      int error = count == SOCKET_ERROR ? WSAGetLastError() : 0;
      if (count > 0) sent += count;
      else if (count == SOCKET_ERROR && error == WSAEWOULDBLOCK) {
        if (ready(true) < 0) return false;
      } else return diagnostic_.fail("send", count == 0 ? "transport_zero_progress" : "wsa", static_cast<unsigned long>(error));
    }
    return true;
  }

  int receive(Packet* packet) {
    int selected = ready(false);
    if (selected <= 0) return selected;
    int count = recv(socket_, reinterpret_cast<char*>(buffer_) + used_,
                     static_cast<int>(sizeof(buffer_)) - used_, 0);
    if (count <= 0) {
      int error = count == SOCKET_ERROR ? WSAGetLastError() : 0;
      if (count == SOCKET_ERROR && error == WSAEWOULDBLOCK) return 0;
      diagnostic_.fail("recv", count == 0 ? "transport_eof" : "wsa", static_cast<unsigned long>(error));
      return -1;
    }
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
    if (result == SOCKET_ERROR) {
      diagnostic_.fail(writing ? "select.send" : "select.receive", "wsa", static_cast<unsigned long>(WSAGetLastError()));
      return -1;
    }
    return result;
  }

  Diagnostic& diagnostic_;
  SOCKET socket_ = INVALID_SOCKET;
  int used_ = 0;
  bool initialized_ = false;
  uint32_t buffer_[3];
};

class Attempt {
 public:
  explicit Attempt(Diagnostic& diagnostic) : diagnostic_(diagnostic) {}
  Attempt(const Attempt&) = delete;
  Attempt& operator=(const Attempt&) = delete;
  ~Attempt() {
    // Cleanup has its own record and cannot replace the primary error or child result.
    Diagnostic primary = diagnostic_;
    diagnostic_.failure_operation = nullptr;
    if (!stop()) {
      job_.reset();
      diagnostic_.print("cleanup", diagnostic_.failure_operation, diagnostic_.error_domain, diagnostic_.error, "unchanged");
      diagnostic_ = primary;
      return;
    }
    output_.reset();
    error_.reset();
    if (!directory_.empty()) {
      std::error_code failure;
      std::filesystem::remove_all(directory_, failure);
      if (failure) diagnostic_.print("cleanup", "remove_attempt_directory", failure.category().name(),
                                     static_cast<unsigned long>(failure.value()), "unchanged");
    }
    diagnostic_ = primary;
  }

  bool launch(char* const* arguments) {
    diagnostic_.operation = "random_device.construct";
    std::random_device random;
    diagnostic_.operation = "temp_directory_path";
    auto root = std::filesystem::temp_directory_path();
    for (int tries = 0; tries < 32; ++tries) {
      diagnostic_.operation = "random_device.generate";
      auto number = random();
      diagnostic_.operation = "create_attempt_directory";
      auto candidate = root / (L"codex-compile-" + std::to_wstring(number));
      std::error_code failure;
      if (std::filesystem::create_directory(candidate, failure)) {
        directory_ = std::move(candidate);
        break;
      }
      if (failure && failure != std::errc::file_exists)
        return diagnostic_.fail("create_attempt_directory", failure.category().name(), static_cast<unsigned long>(failure.value()));
    }
    if (directory_.empty()) return diagnostic_.fail("create_attempt_directory.exhausted", "filesystem");
    diagnostic_.operation = "launch.handles";
    SECURITY_ATTRIBUTES security = {sizeof(SECURITY_ATTRIBUTES), nullptr, TRUE};
    output_.reset(diagnostic_.handle(CreateFileW((directory_ / L"stdout").c_str(), GENERIC_READ | GENERIC_WRITE,
        FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, &security, CREATE_NEW,
        FILE_ATTRIBUTE_TEMPORARY | FILE_FLAG_DELETE_ON_CLOSE, nullptr), "CreateFileW.stdout"));
    error_.reset(diagnostic_.handle(CreateFileW((directory_ / L"stderr").c_str(), GENERIC_READ | GENERIC_WRITE,
        FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, &security, CREATE_NEW,
        FILE_ATTRIBUTE_TEMPORARY | FILE_FLAG_DELETE_ON_CLOSE, nullptr), "CreateFileW.stderr"));
    Handle input(diagnostic_.handle(CreateFileW(L"NUL", GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE,
                             &security, OPEN_EXISTING, 0, nullptr), "CreateFileW.NUL"));
    job_.reset(diagnostic_.handle(CreateJobObjectW(nullptr, nullptr), "CreateJobObjectW"));
    if (!output_.valid() || !error_.valid() || !input.valid() || !job_.valid()) return false;
    JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits = {};
    limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
    if (!diagnostic_.check(SetInformationJobObject(job_.get(), JobObjectExtendedLimitInformation,
                                 &limits, sizeof(limits)), "SetInformationJobObject")) return false;

    diagnostic_.operation = "launch.command";
    std::wstring command;
    for (size_t index = 0; arguments[index] != nullptr; ++index) {
      if (index > 0) command.push_back(L' ');
      command += quote(wide(arguments[index], diagnostic_));
    }
    std::vector<wchar_t> writable(command.begin(), command.end());
    writable.push_back(L'\0');
    diagnostic_.operation = "launch.attributes";
    SIZE_T bytes = 0;
    InitializeProcThreadAttributeList(nullptr, 1, 0, &bytes);
    std::vector<unsigned char> storage(bytes);
    auto attributes = reinterpret_cast<LPPROC_THREAD_ATTRIBUTE_LIST>(storage.data());
    if (!diagnostic_.check(InitializeProcThreadAttributeList(attributes, 1, 0, &bytes),
                           "InitializeProcThreadAttributeList")) return false;
    struct AttributesOwner {
      LPPROC_THREAD_ATTRIBUTE_LIST value;
      ~AttributesOwner() { DeleteProcThreadAttributeList(value); }
    } attributes_owner{attributes};
    HANDLE inherited[] = {input.get(), output_.get(), error_.get()};
    bool configured = diagnostic_.check(UpdateProcThreadAttribute(attributes, 0, PROC_THREAD_ATTRIBUTE_HANDLE_LIST,
                                                inherited, sizeof(inherited), nullptr, nullptr),
                                                "UpdateProcThreadAttribute");
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
    if (!created) {
      if (configured) diagnostic_.win32("CreateProcessW");
      return false;
    }
    diagnostic_.child_pid = process.dwProcessId;
    process_.reset(process.hProcess);
    Handle thread(process.hThread);
    if (!AssignProcessToJobObject(job_.get(), process_.get())) {
      diagnostic_.win32("AssignProcessToJobObject");
      TerminateProcess(process_.get(), 125);
      WaitForSingleObject(process_.get(), INFINITE);
      return false;
    }
    if (ResumeThread(thread.get()) == static_cast<DWORD>(-1)) return diagnostic_.win32("ResumeThread.start");
    return true;
  }

  bool status(DWORD* status, bool* complete) const {
    DWORD waiting = WaitForSingleObject(process_.get(), 0);
    *complete = waiting == WAIT_OBJECT_0;
    if (waiting == WAIT_FAILED) return diagnostic_.win32("WaitForSingleObject.compiler");
    if (!*complete) return true;
    if (!diagnostic_.check(GetExitCodeProcess(process_.get(), status), "GetExitCodeProcess")) return false;
    diagnostic_.child_exit_known = true;
    diagnostic_.child_exit = *status;
    return true;
  }

  bool pause() {
    if (!paused_.empty()) return true;
    while (true) {
      bool added = false;
      Handle snapshot(CreateToolhelp32Snapshot(TH32CS_SNAPTHREAD, 0));
      if (!snapshot.valid()) return diagnostic_.win32("CreateToolhelp32Snapshot");
      THREADENTRY32 entry = {};
      entry.dwSize = sizeof(entry);
      if (!Thread32First(snapshot.get(), &entry)) {
        DWORD error = GetLastError();
        return error == ERROR_NO_MORE_FILES || diagnostic_.fail("Thread32First", "win32", error);
      }
      do {
        Handle process(OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION | SYNCHRONIZE,
                                   FALSE, entry.th32OwnerProcessID));
        if (!process.valid()) continue;
        BOOL owned = FALSE;
        if (!diagnostic_.check(IsProcessInJob(process.get(), job_.get(), &owned), "IsProcessInJob")) return false;
        if (!owned || WaitForSingleObject(process.get(), 0) == WAIT_OBJECT_0) continue;
        bool known = false;
        for (const Handle& suspended : paused_)
          if (GetThreadId(suspended.get()) == entry.th32ThreadID) known = true;
        if (known) continue;
        Handle thread(OpenThread(THREAD_SUSPEND_RESUME | THREAD_QUERY_LIMITED_INFORMATION | SYNCHRONIZE,
                                 FALSE, entry.th32ThreadID));
        if (!thread.valid()) {
          DWORD error = GetLastError();
          if (error == ERROR_INVALID_PARAMETER) continue;
          return diagnostic_.fail("OpenThread", "win32", error);
        }
        if (GetProcessIdOfThread(thread.get()) != entry.th32OwnerProcessID) continue;
        if (SuspendThread(thread.get()) == static_cast<DWORD>(-1)) {
          DWORD error = GetLastError();
          if (WaitForSingleObject(thread.get(), 0) == WAIT_OBJECT_0) continue;
          return diagnostic_.fail("SuspendThread", "win32", error);
        }
        paused_.push_back(std::move(thread));
        added = true;
      } while (Thread32Next(snapshot.get(), &entry));
      DWORD error = GetLastError();
      if (error != ERROR_NO_MORE_FILES) return diagnostic_.fail("Thread32Next", "win32", error);
      if (!added) return true;
    }
  }

  bool resume() {
    for (const Handle& thread : paused_) {
      if (ResumeThread(thread.get()) == static_cast<DWORD>(-1)) {
        DWORD error = GetLastError();
        if (WaitForSingleObject(thread.get(), 0) != WAIT_OBJECT_0)
          return diagnostic_.fail("ResumeThread", "win32", error);
      }
    }
    paused_.clear();
    return true;
  }

  bool stop() {
    if (!job_.valid()) return true;
    if (!diagnostic_.check(TerminateJobObject(job_.get(), 137), "TerminateJobObject")) return false;
    JOBOBJECT_BASIC_ACCOUNTING_INFORMATION accounting = {};
    do {
      if (!diagnostic_.check(QueryInformationJobObject(job_.get(), JobObjectBasicAccountingInformation,
                                     &accounting, sizeof(accounting), nullptr), "QueryInformationJobObject")) return false;
      if (accounting.ActiveProcesses > 0) Sleep(1);
    } while (accounting.ActiveProcesses > 0);
    paused_.clear();
    return true;
  }

  bool replay() const {
    return copy(output_.get(), GetStdHandle(STD_OUTPUT_HANDLE), "stdout") &&
           copy(error_.get(), GetStdHandle(STD_ERROR_HANDLE), "stderr");
  }

 private:
  bool child_environment(std::vector<wchar_t>* result) const {
    diagnostic_.operation = "child_environment";
    std::unique_ptr<wchar_t, decltype(&FreeEnvironmentStringsW)> original(
        GetEnvironmentStringsW(), &FreeEnvironmentStringsW);
    if (original == nullptr) return diagnostic_.win32("GetEnvironmentStringsW");
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

  bool copy(HANDLE source, HANDLE destination, const char* stream) const {
    diagnostic_.operation = stream;
    LARGE_INTEGER beginning = {};
    if (!diagnostic_.check(SetFilePointerEx(source, beginning, nullptr, FILE_BEGIN), "SetFilePointerEx")) return false;
    char buffer[8192];
    DWORD count;
    while (ReadFile(source, buffer, sizeof(buffer), &count, nullptr)) {
      if (count == 0) return true;
      DWORD copied = 0;
      while (copied < count) {
        DWORD written;
        if (!diagnostic_.check(WriteFile(destination, buffer + copied, count - copied, &written, nullptr),
                               "WriteFile")) return false;
        if (written == 0) return diagnostic_.fail("WriteFile", "transport_zero_progress");
        copied += written;
      }
    }
    return diagnostic_.win32("ReadFile");
  }

  Diagnostic& diagnostic_;
  std::filesystem::path directory_;
  Handle output_;
  Handle error_;
  Handle job_;
  Handle process_;
  std::vector<Handle> paused_;
};

inline int run(const char* endpoint, char* const* arguments,
               const std::vector<std::string>& outputs = {}, Diagnostic* context = nullptr) {
  Diagnostic local;
  Diagnostic& diagnostic = context == nullptr ? local : *context;
  try {
    Connection connection(diagnostic);
    if (!connection.connect(endpoint)) return diagnostic.wrapper_exit();
    std::unique_ptr<Attempt> attempt;
    uint32_t generation = 0;
    bool& awaiting_retry = diagnostic.awaiting_retry;
    bool& killed = diagnostic.killed;
    DWORD outcome = 125;
    while (true) {
      Packet packet;
      int received = connection.receive(&packet);
      if (received < 0) return diagnostic.wrapper_exit();
      if (received > 0) {
        diagnostic.packet = packet;
        if (packet.command == CANCEL) {
          diagnostic.fail("scheduler_cancel", "protocol");
          return diagnostic.wrapper_exit();
        }
        if (packet.command == START && !attempt && !awaiting_retry && packet.generation > generation) {
          generation = packet.generation;
          diagnostic.generation = generation;
          diagnostic.child_pid = 0;
          diagnostic.child_exit_known = false;
          diagnostic.child_exit = 0;
          killed = false;
          diagnostic.operation = "create_attempt";
          attempt.reset(new Attempt(diagnostic));
          if (!attempt->launch(arguments) || !connection.send(STARTED, generation, 0))
            return diagnostic.wrapper_exit();
        } else if (attempt && !awaiting_retry && packet.generation == generation) {
          if (packet.command == PAUSE) { if (!attempt->pause()) return diagnostic.wrapper_exit(); }
          else if (packet.command == RESUME) { if (!attempt->resume()) return diagnostic.wrapper_exit(); }
          else if (packet.command == KILL) { killed = true; if (!attempt->stop()) return diagnostic.wrapper_exit(); }
          else {
            diagnostic.fail("unexpected_active_command", "protocol");
            return diagnostic.wrapper_exit();
          }
        } else if (packet.command == RETRY && awaiting_retry && packet.generation == generation) {
          attempt.reset();
          awaiting_retry = false;
        } else if (packet.command == FAIL && awaiting_retry && packet.generation == generation) {
          if (!attempt->replay()) return diagnostic.wrapper_exit();
          return static_cast<int>(outcome);
        } else {
          diagnostic.fail("unexpected_packet_state", "protocol");
          return diagnostic.wrapper_exit();
        }
      }
      if (!attempt || awaiting_retry) continue;
      bool complete = false;
      if (!attempt->status(&outcome, &complete)) return diagnostic.wrapper_exit();
      if (!complete) continue;
      if (!attempt->stop()) return diagnostic.wrapper_exit();
      bool memory_failure = killed || outcome == 0xC0000017UL || outcome == 0xC000012DUL;
      if (memory_failure) {
        for (const std::string& output : outputs) {
          diagnostic.operation = "remove_attempt_output";
          std::error_code failure;
          std::filesystem::remove_all(std::filesystem::path(wide(output.c_str(), diagnostic)), failure);
          if (failure) {
            diagnostic.fail("remove_attempt_output", failure.category().name(), static_cast<unsigned long>(failure.value()));
            return diagnostic.wrapper_exit();
          }
        }
        awaiting_retry = true;
        outcome = 137;
      } else if (!attempt->replay()) return diagnostic.wrapper_exit();
      if (!connection.send(memory_failure ? (killed ? KILLED : EXTERNALLY_KILLED) : EXITED,
                           generation, outcome)) return diagnostic.wrapper_exit();
      if (!awaiting_retry) {
        diagnostic.report_child125("compiler_exit");
        return static_cast<int>(outcome);
      }
    }
  } catch (const std::system_error& failure) {
    diagnostic.fail(diagnostic.operation, failure.code().category().name(), static_cast<unsigned long>(failure.code().value()));
    return diagnostic.wrapper_exit();
  } catch (const std::exception&) {
    diagnostic.fail(diagnostic.operation, "cpp_exception");
    return diagnostic.wrapper_exit();
  }
}

}  // namespace compiler_scheduler
#endif
