#define _DARWIN_C_SOURCE 1

#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

static volatile sig_atomic_t termination_received;

static void receive_termination(int signal_number)
{
	(void) signal_number;
	termination_received = 1;
}

static void write_encoded_argument(FILE *receipt, int index, const char *argument)
{
	(void) fprintf(receipt, "argument%d=", index);
	for (const unsigned char *character = (const unsigned char *) argument; *character != '\0'; ++character)
		(void) fprintf(receipt, "%02x", *character);
	(void) fputc('\n', receipt);
}

static int write_identity(const char *path, int argument_count, char *arguments[])
{
	FILE *receipt = fopen(path, "w");
	if (receipt == NULL)
		return 10;
	char current_directory[PATH_MAX];
	if (getcwd(current_directory, sizeof(current_directory)) == NULL)
		return 11;
	sigset_t current_mask;
	if (sigprocmask(SIG_SETMASK, NULL, &current_mask) != 0)
		return 12;
	int mask_empty = 1;
	for (int signal_number = 1; signal_number < NSIG; ++signal_number)
	{
		if (sigismember(&current_mask, signal_number) == 1)
			mask_empty = 0;
	}
	(void) fprintf(receipt, "cwd=%s\n", current_directory);
	(void) fprintf(receipt, "environment=%s\n", getenv("CODEX_TEST_VALUE"));
	(void) fprintf(receipt, "pid=%d\npgid=%d\nsid=%d\n", getpid(), getpgrp(), getsid(0));
	(void) fprintf(receipt, "argumentCount=%d\n", argument_count);
	for (int index = 0; index < argument_count; ++index)
		write_encoded_argument(receipt, index, arguments[index]);
	for (int descriptor = 0; descriptor < 3; ++descriptor)
	{
		errno = 0;
		int flags = fcntl(descriptor, F_GETFD);
		(void) fprintf(receipt, "fd%d=%s\n", descriptor, flags >= 0 ? "open" : errno == EBADF ? "closed" : "error");
	}
	(void) fprintf(receipt, "emptySignalMask=%s\n", mask_empty ? "true" : "false");
	const int signals[] = {SIGINT, SIGQUIT, SIGTERM, SIGPIPE, SIGXFSZ};
	const char *signal_names[] = {"INT", "QUIT", "TERM", "PIPE", "XFSZ"};
	for (size_t index = 0; index < sizeof(signals) / sizeof(signals[0]); ++index)
	{
		struct sigaction action;
		if (sigaction(signals[index], NULL, &action) != 0)
			return 13;
		(void) fprintf(receipt, "defaultSIG%s=%s\n", signal_names[index],
			action.sa_handler == SIG_DFL ? "true" : "false");
	}
	int result = fclose(receipt);
	return result == 0 ? 0 : 14;
}

static int run_tree(const char *path)
{
	int ready_pipe[2];
	if (pipe(ready_pipe) != 0)
		return 20;
	struct sigaction action = {.sa_handler = receive_termination};
	(void) sigemptyset(&action.sa_mask);
	action.sa_flags = 0;
	if (sigaction(SIGTERM, &action, NULL) != 0)
		return 21;
	pid_t child = fork();
	if (child < 0)
		return 22;
	if (child == 0)
	{
		(void) close(ready_pipe[0]);
		termination_received = 0;
		if (sigaction(SIGTERM, &action, NULL) != 0 || write(ready_pipe[1], "R", 1) != 1)
			_exit(23);
		(void) close(ready_pipe[1]);
		while (!termination_received)
			(void) pause();
		_exit(0);
	}
	(void) close(ready_pipe[1]);
	char ready_signal;
	if (read(ready_pipe[0], &ready_signal, 1) != 1)
		return 24;
	(void) close(ready_pipe[0]);
	FILE *receipt = fopen(path, "w");
	if (receipt == NULL)
		return 25;
	(void) fprintf(receipt, "child=%d\n", child);
	if (fclose(receipt) != 0)
		return 26;
	while (!termination_received)
		(void) pause();
	int status = 0;
	while (waitpid(child, &status, 0) < 0)
	{
		if (errno != EINTR)
			return 27;
	}
	return WIFEXITED(status) && WEXITSTATUS(status) == 0 ? 0 : 28;
}

static int run_resistant(const char *path)
{
	if (signal(SIGTERM, SIG_IGN) == SIG_ERR)
		return 30;
	FILE *receipt = fopen(path, "w");
	if (receipt == NULL)
		return 31;
	(void) fprintf(receipt, "ready\n");
	if (fclose(receipt) != 0)
		return 32;
	for (;;)
		(void) pause();
}

int main(int argument_count, char *arguments[])
{
	if (argument_count == 4 && strcmp(arguments[1], "identity") == 0)
		return write_identity(arguments[2], argument_count, arguments);
	if (argument_count == 3 && strcmp(arguments[1], "tree") == 0)
		return run_tree(arguments[2]);
	if (argument_count == 3 && strcmp(arguments[1], "resistant") == 0)
		return run_resistant(arguments[2]);
	return 64;
}
