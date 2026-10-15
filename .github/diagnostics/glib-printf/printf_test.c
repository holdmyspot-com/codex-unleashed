#include <glib.h>
#include <string.h>

int main(void) {
  char *result = g_strdup_printf("%lld/%llu", -9223372036854775807LL,
                                 18446744073709551615ULL);
  int valid = strcmp(result, "-9223372036854775807/18446744073709551615") == 0;
  g_free(result);

  result = g_strdup_printf("%*s", -8, "left");
  valid = valid && strcmp(result, "left    ") == 0;
  g_free(result);

  result = g_strdup_printf("%s:%*s", "prefix", 4096, "tail");
  valid = valid && strlen(result) == 4103 && strncmp(result, "prefix:", 7) == 0;
  for (int index = 7; index < 4099; ++index) {
    valid = valid && result[index] == ' ';
  }
  valid = valid && strcmp(result + 4099, "tail") == 0;
  g_free(result);

  result = g_strdup_printf("%2$d %3$d %1$d", 1, 2, 3);
  valid = valid && strcmp(result, "2 3 1") == 0;
  g_free(result);
  return valid ? 0 : 1;
}
