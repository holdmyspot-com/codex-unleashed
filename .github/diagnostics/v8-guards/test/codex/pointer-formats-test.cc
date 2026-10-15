#include <cstdint>
#include <cstdio>
#include <cstring>

#include "src/base/macros.h"

#if defined(__MINGW32__)
static_assert(V8_CC_MINGW32 == 1);
static_assert(V8_CC_MINGW == 1);
#endif

#if defined(__MINGW64__)
static_assert(V8_CC_MINGW64 == 1);
#endif

int main() {
  char output[128];
#if UINTPTR_MAX == UINT64_MAX
  constexpr std::uintptr_t address = UINT64_C(0x123456789abcdef0);
  constexpr const char* expected = "123456789abcdef0 -123 123";
#else
  constexpr std::uintptr_t address = UINT32_C(0x12345678);
  constexpr const char* expected = "12345678 -123 123";
#endif
  std::snprintf(output, sizeof(output), "%" V8PRIxPTR " %" V8PRIdPTR " %" V8PRIuPTR,
                address, std::intptr_t{-123}, std::uintptr_t{123});
  return std::strcmp(output, expected);
}
