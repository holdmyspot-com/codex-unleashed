#include <cstdio>
#include <cstdlib>
#include "compiler_scheduler.h"
#if defined(_WIN32)
#include <process.h>
#include <cerrno>
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

int run_wrapper(int argc, char** argv, const char* endpoint
#if defined(_WIN32)
                , compiler_scheduler::Diagnostic& diagnostic
#endif
                ) {
  if (argc < 2) {
    std::fputs("compiler scheduler: expected a compiler and its arguments\n", stderr);
    return 2;
  }
  if (endpoint == nullptr || endpoint[0] == '\0') {
#if defined(_WIN32)
    diagnostic.fail("scheduler_endpoint", "configuration");
    return diagnostic.wrapper_exit();
#else
    std::fputs("compiler scheduler: CODEX_COMPILER_SCHEDULER_SOCKET is required\n", stderr);
    return 125;
#endif
  }
#if defined(_WIN32)
  int status = compiler_scheduler::run(endpoint, argv + 1, {}, &diagnostic);
#else
  int status = compiler_scheduler::run(endpoint, argv + 1);
#endif
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
std::string utf8(const wchar_t* text, compiler_scheduler::Diagnostic& diagnostic) {
  int length = WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, text, -1,
                                  nullptr, 0, nullptr, nullptr);
  if (length == 0) {
    diagnostic.win32("WideCharToMultiByte.size");
    throw std::runtime_error("invalid UTF-16 compiler argument");
  }
  std::vector<char> bytes(static_cast<size_t>(length));
  if (WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, text, -1, bytes.data(),
                          length, nullptr, nullptr) == 0) {
    diagnostic.win32("WideCharToMultiByte.convert");
    throw std::runtime_error("cannot encode compiler argument");
  }
  return std::string(bytes.data());
}

int wmain(int argc, wchar_t** argv) {
  compiler_scheduler::Diagnostic diagnostic;
  try {
    diagnostic.operation = "encode_arguments";
    std::vector<std::string> storage;
    for (int index = 0; index < argc; ++index) storage.push_back(utf8(argv[index], diagnostic));
    std::vector<char*> arguments;
    for (std::string& argument : storage) arguments.push_back(argument.data());
    arguments.push_back(nullptr);
    if (argc >= 2 && !compiles_crate(argc, arguments.data())) {
      diagnostic.operation = "compiler_query_arguments";
      std::vector<std::wstring> quoted;
      for (int index = 1; index < argc; ++index)
        quoted.push_back(compiler_scheduler::quote(argv[index]));
      std::vector<const wchar_t*> query_arguments;
      for (const std::wstring& argument : quoted) query_arguments.push_back(argument.c_str());
      query_arguments.push_back(nullptr);
      diagnostic.operation = "compiler_query";
      _set_doserrno(0);
      intptr_t status = _wspawnvp(_P_WAIT, argv[1], query_arguments.data());
      if (status == -1) {
        int error = errno;
        _get_doserrno(&diagnostic.crt_os_error);
        diagnostic.fail("_wspawnvp", "crt", static_cast<unsigned long>(error));
        return diagnostic.wrapper_exit();
      }
      diagnostic.child_exit_known = true;
      diagnostic.child_exit = static_cast<DWORD>(status);
      diagnostic.report_child125("compiler_query_exit");
      return static_cast<int>(status);
    }
    diagnostic.operation = "scheduler_endpoint";
    DWORD length = GetEnvironmentVariableW(L"CODEX_COMPILER_SCHEDULER_SOCKET", nullptr, 0);
    DWORD size_error = length == 0 ? GetLastError() : ERROR_SUCCESS;
    if (length == 0 && size_error != ERROR_ENVVAR_NOT_FOUND && size_error != ERROR_SUCCESS)
      diagnostic.fail("GetEnvironmentVariableW.size", "win32", size_error);
    std::vector<wchar_t> endpoint(length);
    std::string encoded_endpoint;
    if (length != 0) {
      DWORD read = GetEnvironmentVariableW(L"CODEX_COMPILER_SCHEDULER_SOCKET", endpoint.data(), length);
      DWORD read_error = read == 0 ? GetLastError() : ERROR_SUCCESS;
      if (read != length - 1) {
        diagnostic.fail("GetEnvironmentVariableW.read", read == 0 ? "win32" : "configuration_size_changed", read_error);
        throw std::runtime_error("cannot read compiler scheduler socket");
      }
      encoded_endpoint = utf8(endpoint.data(), diagnostic);
    }
    return run_wrapper(argc, arguments.data(), encoded_endpoint.c_str(), diagnostic);
  } catch (const std::exception&) {
    diagnostic.fail(diagnostic.operation, "cpp_exception");
    return diagnostic.wrapper_exit();
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
