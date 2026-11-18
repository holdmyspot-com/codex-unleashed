/*
 * A minimal setsid for macOS, which ships none: run a command in a new session, detached from the caller, like
 * `setsid -f COMMAND [ARGUMENT...]`.
 *
 * The parent returns 0 as soon as it has forked. The child starts a new session and replaces itself with COMMAND,
 * searched for on PATH. With no COMMAND, or when fork fails, the exit status is 1. When setsid or exec fails in the
 * child, it writes a message to standard error and exits with status 1; the parent has already returned 0 by then.
 *
 * Build: cc -O2 -Wall -Wextra -Werror -o setsid setsid-shim.c
 */
#include <stdio.h>
#include <unistd.h>

int main(int argc, char **argv)
{
	if (argc < 2)
	{
		fputs("usage: setsid COMMAND [ARGUMENT...]\n", stderr);
		return 1;
	}
	pid_t child = fork();
	if (child < 0)
	{
		perror("setsid: fork");
		return 1;
	}
	if (child != 0)
		return 0;
	if (setsid() < 0)
	{
		perror("setsid: setsid");
		_exit(1);
	}
	execvp(argv[1], argv + 1);
	perror(argv[1]);
	_exit(1);
}
