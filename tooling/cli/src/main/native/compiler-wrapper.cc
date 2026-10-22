#include <cstdio>
#include <cstdlib>
#include "compiler_scheduler.h"
#if defined(_WIN32)
#include <process.h>
#endif

bool compiles_crate(int argc, char** argv) {
  bool found = false;
  for (int index = 2; index < argc; ++index) {
    if (std::strcmp(argv[index], "--print") == 0 || std::strncmp(argv[index], "--print=", 8) == 0)
      return false;
    if (std::strcmp(argv[index], "--crate-name") == 0 || std::strncmp(argv[index], "--crate-name=", 13) == 0)
      found = true;
  }
  return found;
}

int run_wrapper(int argc, char** argv, const char* endpoint) {
  if (argc < 2) {
    std::fputs("compiler scheduler: expected a compiler and its arguments\n", stderr);
    return 2;
  }
  if (endpoint == nullptr || endpoint[0] == '\0') {
    std::fputs("compiler scheduler: CODEX_COMPILER_SCHEDULER_SOCKET is required\n", stderr);
    return 125;
  }
  int status = compiler_scheduler::run(endpoint, argv + 1);
  if (status == 137) {
    const char* crate = "compiler";
    for (int index = 2; index < argc; ++index) {
      if (std::strcmp(argv[index], "--crate-name") == 0 && index + 1 < argc)
        crate = argv[index + 1];
      else if (std::strncmp(argv[index], "--crate-name=", 13) == 0)
        crate = argv[index] + 13;
    }
    std::fprintf(stderr, "compiler scheduler: %s exited with status 137\n", crate);
  }
  return status;
}

#if defined(_WIN32)
std::string utf8(const wchar_t* text) {
  int length = WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, text, -1,
                                  nullptr, 0, nullptr, nullptr);
  if (length == 0) throw std::runtime_error("invalid UTF-16 compiler argument");
  std::vector<char> bytes(static_cast<size_t>(length));
  if (WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, text, -1, bytes.data(),
                          length, nullptr, nullptr) == 0)
    throw std::runtime_error("cannot encode compiler argument");
  return std::string(bytes.data());
}

int wmain(int argc, wchar_t** argv) {
  try {
    std::vector<std::string> storage;
    for (int index = 0; index < argc; ++index) storage.push_back(utf8(argv[index]));
    std::vector<char*> arguments;
    for (std::string& argument : storage) arguments.push_back(argument.data());
    arguments.push_back(nullptr);
    if (argc >= 2 && !compiles_crate(argc, arguments.data())) {
      intptr_t status = _wspawnvp(_P_WAIT, argv[1], argv + 1);
      if (status == -1) {
        std::perror("compiler scheduler: execute compiler query");
        return 125;
      }
      return static_cast<int>(status);
    }
    DWORD length = GetEnvironmentVariableW(L"CODEX_COMPILER_SCHEDULER_SOCKET", nullptr, 0);
    std::vector<wchar_t> endpoint(length);
    std::string encoded_endpoint;
    if (length != 0) {
      if (GetEnvironmentVariableW(L"CODEX_COMPILER_SCHEDULER_SOCKET", endpoint.data(), length) != length - 1)
        throw std::runtime_error("cannot read compiler scheduler socket");
      encoded_endpoint = utf8(endpoint.data());
    }
    return run_wrapper(argc, arguments.data(), encoded_endpoint.c_str());
  } catch (const std::exception& error) {
    std::fprintf(stderr, "compiler scheduler: %s\n", error.what());
    return 125;
  }
}
#else
int main(int argc, char** argv) {
  if (argc >= 2 && !compiles_crate(argc, argv)) {
    execvp(argv[1], argv + 1);
    std::perror("compiler scheduler: execute compiler query");
    return 125;
  }
  return run_wrapper(argc, argv, std::getenv("CODEX_COMPILER_SCHEDULER_SOCKET"));
}
#endif
