/* Linux packaging exec binding. No scheduler policy or environment rewriting belongs here. */
#define _POSIX_C_SOURCE 200809L
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

/* Resolve the installed executable, independently of argv[0], PATH and the working directory. */
static char *java_path(void)
{
    size_t capacity = 256;
    for (;;)
    {
        char *path = malloc(capacity);
        if (path == NULL) return NULL;
        ssize_t length = readlink("/proc/self/exe", path, capacity - 1);
        if (length < 0) { free(path); return NULL; }
        if ((size_t) length < capacity - 1)
        {
            path[length] = '\0';
            char *slash = strrchr(path, '/');
            if (slash == NULL) { free(path); errno = EINVAL; return NULL; }
            size_t directory = (size_t) (slash - path) + 1;
            if (directory > SIZE_MAX - sizeof("java"))
            { free(path); errno = ENAMETOOLONG; return NULL; }
            char *resolved = realloc(path, directory + sizeof("java"));
            if (resolved == NULL) { free(path); return NULL; }
            memcpy(resolved + directory, "java", sizeof("java"));
            return resolved;
        }
        free(path);
        if (capacity > SIZE_MAX / 2) { errno = ENAMETOOLONG; return NULL; }
        capacity *= 2;
    }
}

/* execv inherits environ and every descriptor without a shell or a second process. */
int main(int argc, char **argv)
{
    char *java = java_path();
    if (java == NULL) { perror("Resolve bundled Java"); return 127; }
    if ((size_t) argc > SIZE_MAX / sizeof(char *) - 12)
    { free(java); errno = EOVERFLOW; perror("Launcher arguments"); return 127; }
    char **arguments = calloc((size_t) argc + 12, sizeof(char *));
    if (arguments == NULL) { free(java); perror("Launcher arguments"); return 127; }
    size_t next = 0;
    arguments[next++] = java;
    const char *temporary = getenv("TMPDIR");
    if (temporary == NULL || temporary[0] == '\0') temporary = "/tmp";
    const char prefix[] = "-Djava.io.tmpdir=";
    size_t length = strlen(temporary);
    if (length > SIZE_MAX - sizeof(prefix))
    { errno = EOVERFLOW; perror("Java temporary directory"); free(arguments); free(java); return 127; }
    char *option = malloc(sizeof(prefix) + length);
    if (option == NULL) { perror("Java temporary directory"); free(arguments); free(java); return 127; }
    memcpy(option, prefix, sizeof(prefix) - 1);
    memcpy(option + sizeof(prefix) - 1, temporary, length + 1);
    arguments[next++] = option;
    arguments[next++] = "-Xlog:all=off:stdout";
    arguments[next++] = "-Xlog:all=warning:stderr";
    arguments[next++] = "-Djdk.lang.Process.allowAmbiguousCommands=true";
    arguments[next++] = "--enable-native-access=com.holdmyspot.codexunleashed.tooling,com.github.oshi.ffm";
    arguments[next++] = "--module";
    arguments[next++] = "com.holdmyspot.codexunleashed.tooling/com.holdmyspot.codexunleashed.tooling.Main";
    for (int index = 1; index < argc; ++index) arguments[next++] = argv[index];
    execv(java, arguments);
    int failure = errno;
    perror("Execute bundled Java");
    free(option);
    free(arguments);
    free(java);
    return failure == ENOENT ? 127 : 126;
}
