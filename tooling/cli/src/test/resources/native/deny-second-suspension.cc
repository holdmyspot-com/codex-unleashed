// Exercises the real controller after one suspension succeeds and the next is denied.
#include <winsock2.h>
#include <windows.h>
#include <cstdio>

namespace {
DWORD WINAPI deny_second_suspension(HANDLE thread) {
  static unsigned int successful_suspensions = 0;
  if (successful_suspensions == 1) {
    std::fputs("fixture: denied suspension after one successful suspension\n", stderr);
    SetLastError(ERROR_ACCESS_DENIED);
    return static_cast<DWORD>(-1);
  }
  DWORD previous_count = ::SuspendThread(thread);
  if (previous_count != static_cast<DWORD>(-1)) ++successful_suspensions;
  return previous_count;
}

BOOL WINAPI terminate_owned_job(HANDLE job, UINT exit_code) {
  wchar_t enabled[2] = {};
  if (GetEnvironmentVariableW(L"CODEX_SCHEDULER_FIXTURE_STOP_FAILURE", enabled, 2) == 1 && enabled[0] == L'1') {
    SetLastError(ERROR_ACCESS_DENIED);
    return FALSE;
  }
  return ::TerminateJobObject(job, exit_code);
}
}  // namespace

#define SuspendThread deny_second_suspension
#define TerminateJobObject terminate_owned_job
#include "compiler-wrapper.cc"
#undef TerminateJobObject
#undef SuspendThread
