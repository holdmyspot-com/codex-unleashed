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
}  // namespace

#define SuspendThread deny_second_suspension
#include "compiler-wrapper.cc"
#undef SuspendThread
