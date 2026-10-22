#ifndef RULES_RUST_COMPILER_SCHEDULER_H_
#define RULES_RUST_COMPILER_SCHEDULER_H_

#if defined(_WIN32)
#include "compiler_scheduler_windows.h"
#elif defined(__linux__) || defined(__APPLE__)
#include <arpa/inet.h>
#include <fcntl.h>
#include <ftw.h>
#include <poll.h>
#include <signal.h>
#include <sys/socket.h>
#if defined(__linux__)
#include <sys/prctl.h>
#else
#include <libproc.h>
#include <sys/proc.h>
#endif
#include <sys/un.h>
#include <sys/wait.h>
#include <unistd.h>

#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <memory>
#include <limits>
#include <string>
#include <vector>

namespace compiler_scheduler {

#if defined(__APPLE__)
constexpr int kNoSignal = 0;
#else
constexpr int kNoSignal = MSG_NOSIGNAL;
#endif

inline bool configure_socket(int descriptor, bool nonblocking) {
  int flags = fcntl(descriptor, F_GETFD);
  if (flags < 0 || fcntl(descriptor, F_SETFD, flags | FD_CLOEXEC) < 0) return false;
  if (nonblocking) {
    flags = fcntl(descriptor, F_GETFL);
    if (flags < 0 || fcntl(descriptor, F_SETFL, flags | O_NONBLOCK) < 0) return false;
  }
#if defined(__APPLE__)
  int enabled = 1;
  if (setsockopt(descriptor, SOL_SOCKET, SO_NOSIGPIPE, &enabled, sizeof(enabled)) < 0) return false;
#endif
  return true;
}

// Every packet contains a command, an attempt generation and a command value.
enum Command : uint32_t {
  START = 1, PAUSE = 2, RESUME = 3, KILL = 4, RETRY = 5, FAIL = 6,
  CANCEL = 7, STARTED = 8, EXITED = 9, CANCELLED = 10, KILLED = 11, EXTERNALLY_KILLED = 12
};

struct Packet {
  uint32_t command;
  uint32_t generation;
  uint32_t value;
};

static volatile sig_atomic_t interrupted = 0;

inline void interrupt(int signal) { interrupted = signal; }

class SignalHandlers {
 public:
  SignalHandlers() : installed_(0), valid_(false) {
    sigemptyset(&signals_);
    for (int signal : numbers_) sigaddset(&signals_, signal);
    sigset_t previous;
    if (sigprocmask(SIG_BLOCK, &signals_, &previous) < 0) return;
    interrupted = 0;
    struct sigaction handler = {};
    handler.sa_handler = interrupt;
    sigemptyset(&handler.sa_mask);
    while (installed_ < 3) {
      if (sigaction(numbers_[installed_], &handler, &previous_[installed_]) < 0) break;
      ++installed_;
    }
    valid_ = installed_ == 3;
    if (!valid_) restore();
    if (sigprocmask(SIG_SETMASK, &previous, nullptr) < 0) valid_ = false;
  }

  SignalHandlers(const SignalHandlers&) = delete;
  SignalHandlers& operator=(const SignalHandlers&) = delete;

  ~SignalHandlers() {
    sigset_t previous;
    if (sigprocmask(SIG_BLOCK, &signals_, &previous) == 0) {
      restore();
      sigprocmask(SIG_SETMASK, &previous, nullptr);
    }
  }

  bool valid() const { return valid_; }

 private:
  void restore() {
    while (installed_ > 0) {
      --installed_;
      sigaction(numbers_[installed_], &previous_[installed_], nullptr);
    }
  }

  int numbers_[3] = {SIGINT, SIGTERM, SIGHUP};
  struct sigaction previous_[3];
  sigset_t signals_;
  int installed_;
  bool valid_;
};

// A single reader retains partial packets so cancellation remains observable.
class Connection {
 public:
  explicit Connection(int descriptor) : descriptor_(descriptor), used_(0) {}

  bool send(Command command, uint32_t generation, uint32_t value) {
    uint32_t packet[] = {htonl(command), htonl(generation), htonl(value)};
    size_t sent = 0;
    while (sent < sizeof(packet)) {
      if (interrupted != 0) return false;
      ssize_t result = ::send(descriptor_, reinterpret_cast<char*>(packet) + sent,
                              sizeof(packet) - sent, kNoSignal | MSG_DONTWAIT);
      if (result > 0) {
        sent += static_cast<size_t>(result);
      } else if (result < 0 && errno == EINTR && interrupted == 0) {
        continue;
      } else if (result < 0 && errno == EAGAIN) {
        struct pollfd ready = {descriptor_, POLLOUT, 0};
        if (poll(&ready, 1, 20) < 0 && errno != EINTR) return false;
      } else {
        return false;
      }
    }
    return true;
  }

  void cancel(uint32_t generation, uint32_t signal) {
    uint32_t packet[] = {htonl(CANCELLED), htonl(generation), htonl(signal)};
    // Cleanup never waits for the service to accept a cancellation notification.
    ::send(descriptor_, packet, sizeof(packet), kNoSignal | MSG_DONTWAIT);
  }

  // Returns one for a packet, zero for a timeout, and minus one for disconnect.
  int receive(Packet* packet) {
    struct pollfd ready = {descriptor_, POLLIN, 0};
    int result = poll(&ready, 1, 20);
    if (result < 0) return errno == EINTR ? 0 : -1;
    if (result == 0) return 0;
    ssize_t count = recv(descriptor_, reinterpret_cast<char*>(buffer_) + used_,
                         sizeof(buffer_) - used_, MSG_DONTWAIT);
    if (count <= 0) {
      return count < 0 && (errno == EINTR || errno == EAGAIN) ? 0 : -1;
    }
    used_ += static_cast<size_t>(count);
    if (used_ < sizeof(buffer_)) return 0;
    packet->command = ntohl(buffer_[0]);
    packet->generation = ntohl(buffer_[1]);
    packet->value = ntohl(buffer_[2]);
    used_ = 0;
    return 1;
  }

 private:
  int descriptor_;
  uint32_t buffer_[3];
  size_t used_;
};

inline int connect_relative(const char* endpoint) {
  struct sockaddr_un address = {};
  address.sun_family = AF_UNIX;
  if (std::strlen(endpoint) >= sizeof(address.sun_path)) {
    errno = ENAMETOOLONG;
    return -1;
  }
  std::strcpy(address.sun_path, endpoint);
  int descriptor = socket(AF_UNIX, SOCK_STREAM, 0);
  if (descriptor < 0) return -1;
  if (!configure_socket(descriptor, true)) {
    int failure = errno;
    close(descriptor);
    errno = failure;
    return -1;
  }
  int result = ::connect(descriptor, reinterpret_cast<struct sockaddr*>(&address), sizeof(address));
  if (result == 0) return descriptor;
  if (errno == EINPROGRESS) {
    while (interrupted == 0) {
      struct pollfd ready = {descriptor, POLLOUT, 0};
      result = poll(&ready, 1, 20);
      if (result < 0 && errno == EINTR) continue;
      if (result < 0) break;
      if (result == 0) continue;
      int error = 0;
      socklen_t length = sizeof(error);
      if (getsockopt(descriptor, SOL_SOCKET, SO_ERROR, &error, &length) < 0) break;
      if (error == 0) return descriptor;
      errno = error;
      break;
    }
    if (interrupted != 0) errno = EINTR;
  }
  int failure = errno;
  close(descriptor);
  errno = failure;
  return -1;
}

// This single-threaded supervisor restores its working directory before launching
// a compiler. Only the socket basename is subject to the Unix address limit.
inline int connect(const char* endpoint) {
  const char* separator = std::strrchr(endpoint, '/');
  if (separator == nullptr) return connect_relative(endpoint);
  int original = open(".", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
  if (original < 0) return -1;
  std::string directory(endpoint, static_cast<size_t>(separator - endpoint));
  if (directory.empty()) directory = "/";
  if (chdir(directory.c_str()) < 0) {
    int failure = errno;
    close(original);
    errno = failure;
    return -1;
  }
  int descriptor = connect_relative(separator + 1);
  int failure = errno;
  int restored;
  do {
    restored = fchdir(original);
  } while (restored < 0 && errno == EINTR);
  if (restored < 0) {
    failure = errno;
    if (descriptor >= 0) close(descriptor);
    descriptor = -1;
  }
  close(original);
  errno = failure;
  return descriptor;
}

inline bool terminate(pid_t child) {
  kill(-child, SIGKILL);
  kill(child, SIGKILL);
#if defined(__APPLE__)
  // Retain the leader's PID until no member of its process group can execute.
  siginfo_t leader = {};
  while (waitid(P_PID, static_cast<id_t>(child), &leader, WEXITED | WNOWAIT) < 0) {
    if (errno != EINTR) return false;
  }
  while (true) {
    errno = 0;
    int bytes = proc_listpids(PROC_PGRP_ONLY, static_cast<uint32_t>(child), nullptr, 0);
    if (bytes < 0 || (bytes == 0 && errno != 0)) return false;
    if (bytes > std::numeric_limits<int>::max() - static_cast<int>(sizeof(pid_t))) return false;
    std::vector<pid_t> members(static_cast<size_t>(bytes) / sizeof(pid_t) + 1);
    int capacity = static_cast<int>(members.size() * sizeof(pid_t));
    errno = 0;
    int received = proc_listpids(PROC_PGRP_ONLY, static_cast<uint32_t>(child), members.data(), capacity);
    if (received < 0 || (received == 0 && errno != 0)) return false;
    if (received >= capacity) continue;
    bool active = false;
    for (size_t index = 0; index < static_cast<size_t>(received) / sizeof(pid_t); ++index) {
      pid_t member = members[index];
      if (member == 0 || member == child) continue;
      struct proc_bsdinfo status = {};
      errno = 0;
      int size = proc_pidinfo(member, PROC_PIDTBSDINFO, 0, &status, static_cast<int>(sizeof(status)));
      if (size == 0 && errno == ESRCH) continue;
      if (size != static_cast<int>(sizeof(status))) return false;
      if (status.pbi_pgid == static_cast<uint32_t>(child) && status.pbi_status != SZOMB) active = true;
    }
    if (!active) break;
    kill(-child, SIGKILL);
    poll(nullptr, 0, 10);
  }
  while (waitpid(child, nullptr, 0) < 0) {
    if (errno != EINTR) return false;
  }
  return true;
#else
  while (waitpid(child, nullptr, 0) < 0 && errno == EINTR) {}
  // The supervisor has one attempt. Subreaping also adopts children that created another session.
  while (true) {
    pid_t result = waitpid(-1, nullptr, WNOHANG);
    if (result > 0 || (result < 0 && errno == EINTR)) continue;
    if (result < 0) return errno == ECHILD;
    std::ifstream descendants("/proc/self/task/" + std::to_string(getpid()) + "/children");
    if (!descendants.is_open()) return false;
    pid_t descendant;
    while (descendants >> descendant) {
      if (kill(descendant, SIGKILL) < 0 && errno != ESRCH) return false;
    }
    if (!descendants.eof()) return false;
    poll(nullptr, 0, 10);
  }
#endif
}

inline int remove_entry(const char* path, const struct stat*, int, struct FTW*) {
  return std::remove(path);
}

inline bool remove_tree(const std::string& path) {
  struct stat entry;
  if (lstat(path.c_str(), &entry) < 0) return errno == ENOENT;
  return nftw(path.c_str(), remove_entry, 16, FTW_DEPTH | FTW_PHYS) == 0;
}

// Attempt diagnostics remain anonymous files until the service chooses a terminal result.
class Attempt {
 public:
  Attempt() : output_(-1), error_(-1) {}
  Attempt(const Attempt&) = delete;
  Attempt& operator=(const Attempt&) = delete;

  ~Attempt() {
    if (output_ >= 0) close(output_);
    if (error_ >= 0) close(error_);
    if (!cleanup()) std::perror("compiler_scheduler: remove attempt directory");
  }

  bool prepare() {
    const char* root = std::getenv("TMPDIR");
    if (root == nullptr || *root == '\0') {
      errno = EINVAL;
      return false;
    }
    std::string pattern = std::string(root) + "/codex-compile-XXXXXX";
    std::vector<char> writable(pattern.begin(), pattern.end());
    writable.push_back('\0');
    char* directory = mkdtemp(writable.data());
    if (directory == nullptr) return false;
    directory_ = directory;
    output_ = spool("stdout");
    if (output_ < 0) return false;
    error_ = spool("stderr");
    return error_ >= 0;
  }

  bool redirect() const {
    return setenv("TMPDIR", directory_.c_str(), 1) == 0 &&
           setenv("CODEX_COMPILER_SCHEDULER_CHILD_STATUS", (directory_ + "/child-signal").c_str(), 1) == 0 &&
           dup2(output_, STDOUT_FILENO) >= 0 && dup2(error_, STDERR_FILENO) >= 0;
  }

  bool child_signal(int* signal) const {
    std::string path = directory_ + "/child-signal";
    struct stat entry;
    if (lstat(path.c_str(), &entry) < 0) return errno == ENOENT;
    std::ifstream status(path);
    if (!(status >> *signal) || *signal <= 0 || *signal >= NSIG) {
      errno = EINVAL;
      return false;
    }
    status >> std::ws;
    if (!status.eof()) {
      errno = EINVAL;
      return false;
    }
    return true;
  }

  bool replay() const {
    return copy(output_, STDOUT_FILENO) && copy(error_, STDERR_FILENO);
  }

  bool cleanup() {
    if (directory_.empty()) return true;
    if (!remove_tree(directory_)) return false;
    directory_.clear();
    return true;
  }

 private:
  int spool(const char* name) const {
    std::string path = directory_ + "/" + name;
    int descriptor = open(path.c_str(), O_RDWR | O_CREAT | O_EXCL | O_CLOEXEC, 0600);
    if (descriptor >= 0 && unlink(path.c_str()) < 0) {
      int failure = errno;
      close(descriptor);
      errno = failure;
      return -1;
    }
    return descriptor;
  }

  static bool copy(int source, int destination) {
    if (lseek(source, 0, SEEK_SET) < 0) return false;
    char buffer[8192];
    while (true) {
      ssize_t count = read(source, buffer, sizeof(buffer));
      if (count == 0) return true;
      if (count < 0) {
        if (errno == EINTR && interrupted == 0) continue;
        return false;
      }
      ssize_t copied = 0;
      while (copied < count) {
        ssize_t written = write(destination, buffer + copied, static_cast<size_t>(count - copied));
        if (written > 0) copied += written;
        else if (written < 0 && errno == EINTR && interrupted == 0) continue;
        else return false;
      }
    }
  }

  std::string directory_;
  int output_;
  int error_;
};

// The child cannot execute until its parent knows that the process group exists.
inline pid_t launch(char* const* arguments, const Attempt& attempt) {
  int handshake[2];
  if (socketpair(AF_UNIX, SOCK_STREAM, 0, handshake) < 0) return -1;
  if (!configure_socket(handshake[0], false) || !configure_socket(handshake[1], false)) {
    int failure = errno;
    close(handshake[0]);
    close(handshake[1]);
    errno = failure;
    return -1;
  }
  pid_t child = fork();
  if (child == 0) {
    close(handshake[0]);
    if (setsid() < 0) _exit(126);
    if (!attempt.redirect()) _exit(126);
    char acknowledgement = 1;
    if (::send(handshake[1], &acknowledgement, 1, kNoSignal) != 1) _exit(126);
    if (recv(handshake[1], &acknowledgement, 1, 0) != 1) _exit(126);
    close(handshake[1]);
    execvp(arguments[0], arguments);
    std::perror("compiler_scheduler: execvp");
    _exit(127);
  }

  int failure = errno;
  close(handshake[1]);
  if (child > 0) {
    char acknowledgement;
    ssize_t count;
    do {
      count = recv(handshake[0], &acknowledgement, 1, 0);
    } while (count < 0 && errno == EINTR && interrupted == 0);
    if (count != 1 || interrupted != 0 ||
        ::send(handshake[0], &acknowledgement, 1, kNoSignal) != 1) {
      terminate(child);
      child = -1;
      failure = ECANCELED;
    }
  }
  close(handshake[0]);
  errno = failure;
  return child;
}

// The service owns admission policy; this adapter owns only sandbox-local children.
inline int run(const char* endpoint, char* const* arguments,
               const std::vector<std::string>& outputs = {}) {
  SignalHandlers handlers;
  if (!handlers.valid()) {
    std::perror("compiler_scheduler: install cancellation handlers");
    return 125;
  }
  int descriptor = connect(endpoint);
  if (descriptor < 0) {
    std::perror("compiler_scheduler: connect");
    return interrupted == 0 ? 125 : 128 + interrupted;
  }
  Connection connection(descriptor);
#if defined(__linux__)
  int old_subreaper;
  if (prctl(PR_GET_CHILD_SUBREAPER, &old_subreaper) < 0 ||
      prctl(PR_SET_CHILD_SUBREAPER, 1) < 0) {
    std::perror("compiler_scheduler: enable child reaping");
    close(descriptor);
    return 125;
  }
#endif
  pid_t child = -1;
  uint32_t generation = 0;
  bool killed = false;
  bool awaiting_retry = false;
  std::unique_ptr<Attempt> attempt;
  int outcome = 125;
  bool finished = false;
  while (!finished && interrupted == 0) {
    Packet packet;
    int received = connection.receive(&packet);
    if (received < 0) break;
    if (received > 0) {
      if (packet.command == START && child < 0 && !awaiting_retry && packet.generation > generation) {
        generation = packet.generation;
        killed = false;
        attempt.reset(new Attempt());
        if (!attempt->prepare()) {
          std::perror("compiler_scheduler: prepare attempt");
          break;
        }
        child = launch(arguments, *attempt);
        if (child < 0) {
          std::perror("compiler_scheduler: launch");
          break;
        }
        if (!connection.send(STARTED, generation, 0)) break;
      } else if (packet.generation == generation && child > 0) {
        int signal = 0;
        if (packet.command == PAUSE) signal = SIGSTOP;
        else if (packet.command == RESUME) signal = SIGCONT;
        else if (packet.command == KILL) {
          signal = SIGKILL;
          killed = true;
        } else if (packet.command == CANCEL) {
          finished = true;
        } else {
          break;
        }
        if (signal != 0 && kill(-child, signal) < 0 && errno != ESRCH) break;
      } else if (packet.command == RETRY && awaiting_retry && packet.generation == generation) {
        attempt.reset();
        awaiting_retry = false;
      } else if (packet.command == FAIL && awaiting_retry && packet.generation == generation) {
        if (!attempt->replay()) {
          std::perror("compiler_scheduler: replay diagnostics");
          outcome = 125;
        }
        finished = true;
      } else if (packet.command == CANCEL) {
        finished = true;
      } else {
        break;
      }
    }

    if (finished) break;

    if (child > 0) {
      siginfo_t status = {};
      int result = waitid(P_PID, child, &status, WEXITED | WNOHANG | WNOWAIT);
      if (result < 0 && errno != EINTR) break;
      if (result == 0 && status.si_pid == child &&
          (status.si_code == CLD_EXITED || status.si_code == CLD_KILLED ||
           status.si_code == CLD_DUMPED)) {
        int exit_status = status.si_code == CLD_EXITED ? status.si_status : 128 + status.si_status;
        // Keep the exited leader's PID reserved until its remaining group is gone.
        if (!terminate(child)) {
          std::perror("compiler_scheduler: reap compiler descendants");
          break;
        }
        child = -1;
        int child_signal = 0;
        if (!attempt->child_signal(&child_signal)) {
          std::perror("compiler_scheduler: read wrapped compiler signal");
          break;
        }
        if (!attempt->cleanup()) {
          std::perror("compiler_scheduler: remove attempt directory");
          break;
        }
        bool sigkill = child_signal == SIGKILL ||
                       (status.si_code != CLD_EXITED && status.si_status == SIGKILL);
        if (sigkill) {
          exit_status = 128 + SIGKILL;
          bool cleaned = true;
          for (const std::string& output : outputs) {
            if (!remove_tree(output)) {
              std::perror("compiler_scheduler: remove incomplete output");
              cleaned = false;
            }
          }
          if (!cleaned) break;
          awaiting_retry = true;
        } else if (!attempt->replay()) {
          std::perror("compiler_scheduler: replay diagnostics");
          break;
        }
        if (!connection.send(sigkill ? (killed ? KILLED : EXTERNALLY_KILLED) : EXITED,
                              generation, static_cast<uint32_t>(exit_status))) break;
        outcome = exit_status;
        finished = !awaiting_retry;
      }
    }
  }
  if (child > 0 && !terminate(child)) std::perror("compiler_scheduler: reap compiler descendants");
  attempt.reset();
  if (interrupted != 0) {
    connection.cancel(generation, static_cast<uint32_t>(interrupted));
    outcome = 128 + interrupted;
  }
  close(descriptor);
#if defined(__linux__)
  prctl(PR_SET_CHILD_SUBREAPER, old_subreaper);
#endif
  return outcome;
}

}  // namespace compiler_scheduler
#endif
#endif
