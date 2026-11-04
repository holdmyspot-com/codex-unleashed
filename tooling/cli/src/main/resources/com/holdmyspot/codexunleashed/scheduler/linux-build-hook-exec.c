/*
 * Exec trampoline for linux-build-hook.c, installed beside the library as codex-build-hook-exec.
 *
 * Arguments: path argv... The library redirects an exec of a static image here. Loaded with the library, the
 * trampoline hands path and its arguments to the library's codex_build_hook_exec_static, which returns only on
 * failure. Without the library, or with fewer than two arguments, it exits with status 125.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <stddef.h>

/** Exit status when the handoff cannot run, matching the library's failure status. */
#define FAILURE_STATUS 125

typedef int (*handoff_function)(const char *, char *const []);

int main(int argc, char *argv[])
{
    if (argc < 3) return FAILURE_STATUS;
    handoff_function handoff = (handoff_function) dlsym(RTLD_DEFAULT, "codex_build_hook_exec_static");
    if (handoff == NULL) return FAILURE_STATUS;
    return handoff(argv[1], argv + 2);
}
