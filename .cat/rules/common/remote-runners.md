# Remote Runners

When about to dispatch, claim, connect to, or run commands on a remote runner over SSH or Upterm, including a retained
CI worker under test or diagnosis, lazy load `../include/common/remote-runner-ssh.md`.

When a failure reproduces only on a remote runner and its cause is still unknown after one failed run, such as a hang,
a stall, a crash, or a question about a process's state, dispatch the rerun with a retained debug session (the release
workflow's `public_key` input) and inspect the live worker. Do not dispatch another unattended run that only adds
logging. A dispatch takes over an hour; a live session answers each question in seconds and keeps the worker for the
next question.
