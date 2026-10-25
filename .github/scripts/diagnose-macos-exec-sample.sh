#!/usr/bin/env bash

start_archived_exec_sampler() {
  local output="$1" executable_paths="$2" executable_hash="$3" executable
  archived_exec_sample_output="$output"
  archived_exec_sampler_pid=
  mkdir "$output"
  printf 'unverified\n' > "$output/identity.status"
  if [[ "$(wc -l < "$executable_paths" | tr -d ' ')" != 1 ]]; then
    printf 'Expected exactly one authenticated executable path.\n' > "$output/start.status"
    return 0
  fi
  IFS= read -r executable < "$executable_paths"
  if [[ "$executable" != /* || ! -x "$executable" ]]; then
    printf 'Authenticated executable path is not an absolute executable file.\n' > "$output/start.status"
    return 0
  fi
  if ! shasum -a 256 "$executable" > "$output/executable-verification.log" 2>&1; then
    printf 'Could not hash the authenticated executable.\n' > "$output/start.status"
    return 0
  fi
  if ! cmp -s "$output/executable-verification.log" "$executable_hash"; then
    printf 'Authenticated executable hash or path does not match.\n' > "$output/start.status"
    return 0
  fi
  if ! command -v sample > "$output/sampler-command.txt"; then
    printf 'The sample command is not installed on the command path.\n' > "$output/start.status"
    return 0
  fi
  (
    cd -P "$(dirname "$executable")" || exit
    printf '%s/%s\n' "$PWD" "$(basename "$executable")"
  ) > "$output/expected-executable.txt"
  printf '%s\n' "$executable_hash" > "$output/executable-hash-file.txt"
  printf 'sample codex-exec 5 10 -wait -mayDie\n' > "$output/command.txt"
  sample codex-exec 5 10 -wait -mayDie \
    > "$output/sample.txt" 2> "$output/sample.stderr" &
  archived_exec_sampler_pid=$!
  printf '%s\n' "$archived_exec_sampler_pid" > "$output/sampler.pid"
  printf 'started\n' > "$output/start.status"
}

finish_archived_exec_sampler() {
  if [[ -z "${archived_exec_sampler_pid:-}" ]]; then
    return 0
  fi
  local output="$archived_exec_sample_output" status sampled_path expected_path hash_file
  if kill -0 "$archived_exec_sampler_pid" 2>/dev/null; then
    printf 'stopped-at-test-phase-end\n' > "$output/lifecycle.status"
    kill -TERM "$archived_exec_sampler_pid" 2>/dev/null || true
    if kill -0 "$archived_exec_sampler_pid" 2>/dev/null; then
      kill -KILL "$archived_exec_sampler_pid" 2>/dev/null || true
    fi
  else
    printf 'completed-before-test-phase-end\n' > "$output/lifecycle.status"
  fi
  if wait "$archived_exec_sampler_pid"; then
    status=0
  else
    status=$?
  fi
  archived_exec_sampler_pid=
  printf '%s\n' "$status" > "$output/sampler.status"
  if [[ "$status" != 0 ]]; then
    return 0
  fi

  sed -n 's/^Path:[[:space:]]*//p' "$output/sample.txt" > "$output/sampled-executable.txt"
  if [[ "$(wc -l < "$output/sampled-executable.txt" | tr -d ' ')" != 1 ]]; then
    printf 'missing-or-ambiguous-sampled-path\n' > "$output/identity.status"
    return 0
  fi
  IFS= read -r sampled_path < "$output/sampled-executable.txt"
  IFS= read -r expected_path < "$output/expected-executable.txt"
  if [[ "$sampled_path" != "$expected_path" ]]; then
    printf 'sampled-path-does-not-match-authenticated-executable\n' > "$output/identity.status"
    return 0
  fi
  IFS= read -r hash_file < "$output/executable-hash-file.txt"
  if ! shasum -a 256 -c "$hash_file" > "$output/post-sample-executable-verification.log" 2>&1; then
    printf 'authenticated-executable-changed\n' > "$output/identity.status"
    return 0
  fi
  printf 'verified\n' > "$output/identity.status"
  touch "$output/accepted"
}
