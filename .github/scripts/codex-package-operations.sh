#!/usr/bin/env bash

set -euo pipefail

# Reports a command-line error and returns the status reserved for invalid arguments.
#
# Arguments:
#   Description: The corrective message for the caller.
# Output: Writes the message to standard error.
# Exit status: 2.
argument_error() {
  printf '%s\n' "$1" >&2
  return 2
}

# Parses one command's named options into variables owned by its caller.
#
# Arguments:
#   Description: The allowed option names followed by option-value pairs.
# Environment: Caller-local variables named after the allowed options receive values.
# Exit status: 0 for valid pairs, 2 for missing, duplicate, unsupported, or empty values.
parse_arguments() {
  local allowed_options="$1"
  local seen_options=:
  local option option_name option_value
  shift

  while (($#)); do
    option="$1"
    case "$option" in
      --runner-os) option_name=runner_os ;;
      --build-mode) option_name=build_mode ;;
      --build-system) option_name=build_system ;;
      --target) option_name=target ;;
      --binaries) option_name=binaries ;;
      --target-dir) option_name=target_dir ;;
      --result-dir) option_name=result_dir ;;
      --source-root) option_name=source_root ;;
      --workspace) option_name=workspace ;;
      --runner-temp) option_name=runner_temp ;;
      --package-version) option_name=package_version ;;
      --voice-release-dir) option_name=voice_release_dir ;;
      --voice-build-commit) option_name=voice_build_commit ;;
      --tooling-launcher) option_name=tooling_launcher ;;
      *) argument_error "Unknown option: $option"; return 2 ;;
    esac

    case " $allowed_options " in
      *" $option_name "*) ;;
      *) argument_error "Option $option is not valid for this command"; return 2 ;;
    esac

    case "$seen_options" in
      *":$option_name:"*) argument_error "Option $option was provided more than once"; return 2 ;;
    esac
    seen_options="${seen_options}${option_name}:"

    shift
    if (($# == 0)); then
      argument_error "Option $option requires a value"
      return 2
    fi
    option_value="$1"
    if [[ -z "$option_value" && "$option_name" != voice_release_dir && "$option_name" != voice_build_commit && "$option_name" != tooling_launcher ]]; then
      argument_error "Option $option requires a non-empty value"
      return 2
    fi
    case "$option_value" in
      *$'\n'*|*$'\r'*) argument_error "Option $option must be a single line"; return 2 ;;
    esac
    printf -v "$option_name" '%s' "$option_value"
    shift
  done

  local required_option
  for required_option in $allowed_options; do
    case "$required_option" in
      voice_release_dir|voice_build_commit|tooling_launcher) continue ;;
    esac
    if [[ -z "${!required_option}" ]]; then
      argument_error "Missing required option: --${required_option//_/-}"
      return 2
    fi
  done
}

# Validates the runner identity accepted by package operations.
#
# Arguments:
#   Description: The GitHub runner operating system.
# Exit status: 0 for Linux, macOS, or Windows; 2 otherwise.
validate_runner_os() {
  case "$1" in
    Linux|macOS|Windows) ;;
    *) argument_error "Unsupported runner OS: $1"; return 2 ;;
  esac
}

# Validates the package action's supported build mode and build system.
#
# Arguments:
#   Description: The build mode and package build system.
# Exit status: 0 for release or debug with Cargo or Bazel; 2 otherwise.
validate_build_selection() {
  case "$1" in
    release|debug) ;;
    *) argument_error "Unsupported build mode: $1"; return 2 ;;
  esac
  case "$2" in
    cargo|bazel) ;;
    *) argument_error "Unsupported build system: $2"; return 2 ;;
  esac
}

# Produces the effective Cargo release-profile assignments for one package target.
#
# Arguments:
#   Description: The Rust target triple and the release or debug build mode.
# Output: Writes fixed KEY=value assignments, one per line.
# Exit status: 0 for a supported mode; 2 otherwise.
profile_assignments() {
  local target="$1"
  local build_mode="$2"

  case "$build_mode" in
    release|debug) ;;
    *) argument_error "Unsupported build mode: $build_mode"; return 2 ;;
  esac

  if [[ "$target" == *apple-darwin* ]]; then
    printf '%s\n' 'CARGO_PROFILE_RELEASE_SPLIT_DEBUGINFO=packed'
  else
    printf '%s\n' 'CARGO_PROFILE_RELEASE_SPLIT_DEBUGINFO=off'
  fi
  if [[ "$build_mode" == debug ]]; then
    printf '%s\n' \
      'CARGO_PROFILE_RELEASE_OPT_LEVEL=0' \
      'CARGO_PROFILE_RELEASE_LTO=off' \
      'CARGO_PROFILE_RELEASE_CODEGEN_UNITS=256'
  fi
}

# Writes the selected argument vector before running one build or archive tool.
# These NUL records preserve historical argv for diagnostics; consumers must never resolve or replay them as locators.
# Persisted file locators use an explicit project-relative base in their owning result format.
#
# Arguments:
#   Description: The executable and its individually separated arguments.
# Environment: Uses the result directory and command index owned by the active operation.
# Side effects: Creates the next NUL-delimited commands/NNNN.argv file.
# Exit status: The invoked executable's status.
run_recorded() {
  local command_file command_number argument
  command_index=$((command_index + 1))
  printf -v command_number '%04d' "$command_index"
  command_file="${result_dir}/commands/${command_number}.argv"
  : > "$command_file" || return $?
  for argument in "$@"; do
    printf '%s\0' "$argument" >> "$command_file" || return $?
  done
  "$@"
}

# Creates an empty result directory owned by this operation.
#
# Arguments:
#   Description: The caller-selected result directory.
# Side effects: Creates the result directory and its commands directory.
# Exit status: 0 when the directory is new or empty; 1 when it contains earlier data or cannot be created.
prepare_result_directory() {
  local destination="$1"
  local existing_path

  if [[ -d "$destination" ]]; then
    for existing_path in "$destination"/* "$destination"/.[!.]* "$destination"/..?*; do
      if [[ -e "$existing_path" || -L "$existing_path" ]]; then
        printf 'Result directory must be empty: %s\n' "$destination" >&2
        return 1
      fi
    done
  else
    mkdir -p "$destination" || return $?
  fi
  mkdir -p "${destination}/commands"
}

# Writes the approved present environment variables as NUL-delimited key-value pairs.
#
# Arguments:
#   Description: The environment record path.
# Output: Writes each present approved key and value as two NUL-terminated strings.
# Exit status: 0 when the record is written.
record_environment() {
  local output_path="$1"
  local name value
  : > "$output_path"

  while IFS= read -r name; do
    case "$name" in
      PATH|CARGO_HOME|CARGO_TARGET_DIR|RUSTUP_TOOLCHAIN|RUSTC|RUSTC_WRAPPER|RUSTC_WORKSPACE_WRAPPER|RUSTFLAGS|CARGO_ENCODED_RUSTFLAGS|CARGO_BUILD_JOBS|CARGO_INCREMENTAL|STABLE_GIT_COMMIT|CODEX_UNLEASHED_BUILD_NUMBER|CODEX_BWRAP_SHA256|MACOSX_DEPLOYMENT_TARGET|SDKROOT|DEVELOPER_DIR|LIBSQLITE3_FLAGS|SOURCE_DATE_EPOCH|AWS_LC_SYS_NO_JITTER_ENTROPY|INCLUDE|LIB|LIBPATH|VCINSTALLDIR|VCToolsInstallDir|WindowsSdkDir|WindowsSDKVersion)
        ;;
      CARGO_PROFILE_RELEASE_*|CARGO_TARGET_*|RUSTY_V8_*) ;;
      CC|CXX|AR|CFLAGS|CXXFLAGS|LDFLAGS|CC_*|CXX_*|AR_*|CFLAGS_*|CXXFLAGS_*|LDFLAGS_*) ;;
      *) continue ;;
    esac
    value="${!name}"
    printf '%s\0%s\0' "$name" "$value" >> "$output_path"
  done < <(compgen -e)
}

# Writes common result metadata before a build or archive operation starts.
#
# Arguments:
#   Description: The operation name, target, runner OS, and selected binaries.
# Side effects: Writes one-line result metadata and the binary selection.
# Exit status: 0 when all metadata files are written.
write_common_metadata() {
  local operation="$1"
  local target="$2"
  local runner_os="$3"
  local binaries="$4"
  printf '1\n' > "${result_dir}/format-version"
  printf '%s\n' "$operation" > "${result_dir}/operation"
  printf '%s\n' "$target" > "${result_dir}/target"
  printf '%s\n' "$runner_os" > "${result_dir}/runner-os"
  printf '%s\n' "$binaries" > "${result_dir}/binaries"
}

# Completes a result record by atomically publishing its completion marker.
#
# Arguments:
#   Description: None.
# Side effects: Atomically renames a temporary marker to completed.
# Exit status: 0 when the marker is published.
complete_result() {
  local temporary_marker="${result_dir}/.completed.tmp"
  printf 'true\n' > "$temporary_marker" || return $?
  mv -f "$temporary_marker" "${result_dir}/completed"
}

# Selects the package binaries using the runner operating system and build mode.
#
# Arguments:
#   Description: Runner OS, build mode, and Cargo or Bazel build system options.
# Output: Writes the exact space-separated binary list on one line.
# Exit status: 0 for supported selections; 2 for invalid arguments.
select_binaries() {
  local runner_os='' build_mode='' build_system='' selected_binaries
  parse_arguments 'runner_os build_mode build_system' "$@" || return $?
  validate_runner_os "$runner_os" || return $?
  validate_build_selection "$build_mode" "$build_system" || return $?

  selected_binaries='codex codex-app-server codex-code-mode-host codex-exec-server'
  if [[ "$runner_os" == Windows ]]; then
    selected_binaries+=' codex-command-runner codex-windows-sandbox-setup'
  fi
  printf '%s\n' "$selected_binaries"
}

# Emits the fixed profile assignments consumed by the Cargo package build.
#
# Arguments:
#   Description: The Rust target triple and release or debug build mode options.
# Output: Writes the profile assignments only.
# Exit status: 0 for a supported build mode; 2 otherwise.
profile_env() {
  local target='' build_mode=''
  parse_arguments 'target build_mode' "$@" || return $?
  profile_assignments "$target" "$build_mode"
}

# Builds selected Cargo package binaries and records the inputs observed at that operation.
#
# Arguments:
#   Description: Target, runner OS, build mode, binaries, Cargo target directory, and result directory options.
# Environment: Reads only approved determining variables and exports the effective profile and build identity.
# Side effects: Runs Cargo, strip, SHA-256, and Git commands; retains records and partial outputs in the result directory.
# Exit status: 0 on completion, 2 for invalid arguments, or the first failed child status.
build_binaries() {
  local target='' runner_os='' build_mode='' binaries='' target_dir='' result_dir=''
  local command_index=0
  local profile_file export_file assignment bwrap_path bwrap_digest_file digest_value git_commit_file
  local STABLE_GIT_COMMIT
  local build_argument
  local -a selected_binaries=()
  parse_arguments 'target runner_os build_mode binaries target_dir result_dir' "$@" || return $?
  validate_runner_os "$runner_os" || return $?
  case "$build_mode" in
    release|debug) ;;
    *) argument_error "Unsupported build mode: $build_mode"; return 2 ;;
  esac
  if [[ -z "$binaries" ]]; then
    argument_error 'Option --binaries requires at least one binary name'
    return 2
  fi
  IFS=$' \t\n' read -r -a selected_binaries <<< "$binaries"
  if ((${#selected_binaries[@]} == 0)); then
    argument_error 'Option --binaries requires at least one binary name'
    return 2
  fi
  prepare_result_directory "$result_dir" || return $?
  write_common_metadata build_binaries "$target" "$runner_os" "$binaries" || return $?
  printf '%s\n' "$build_mode" > "${result_dir}/build-mode" || return $?
  profile_file="${result_dir}/profile.env"
  export_file="${result_dir}/export.env"
  profile_assignments "$target" "$build_mode" > "$profile_file" || return $?
  cp "$profile_file" "$export_file" || return $?
  while IFS= read -r assignment; do
    export "${assignment%%=*}=${assignment#*=}"
  done < "$profile_file"

  if [[ "$runner_os" == Linux ]]; then
    run_recorded cargo build --target "$target" --release --timings --bin bwrap || return $?
    bwrap_path="${target_dir}/${target}/release/bwrap"
    run_recorded strip --strip-debug --strip-unneeded "$bwrap_path" || return $?
    bwrap_digest_file="${result_dir}/.bwrap-sha256sum.out"
    run_recorded sha256sum "$bwrap_path" > "$bwrap_digest_file" || return $?
    IFS=' ' read -r digest_value _ < "$bwrap_digest_file"
    case "$digest_value" in
      *[!0-9a-f]*|'') printf 'Invalid SHA-256 output for %s\n' "$bwrap_path" >&2; return 1 ;;
    esac
    export CODEX_BWRAP_SHA256="$digest_value"
    printf 'CODEX_BWRAP_SHA256=%s\n' "$digest_value" >> "$export_file" || return $?
  fi

  if [[ "$target" == x86_64-pc-windows-msvc ]]; then
    export LIBSQLITE3_FLAGS=SQLITE_DISABLE_INTRINSIC
  fi
  git_commit_file="${result_dir}/.git-rev-parse.out"
  run_recorded git rev-parse HEAD > "$git_commit_file" || return $?
  IFS= read -r STABLE_GIT_COMMIT < "$git_commit_file"
  export STABLE_GIT_COMMIT
  printf '%s\n' "$STABLE_GIT_COMMIT" > "${result_dir}/source-commit" || return $?

  record_environment "${result_dir}/environment.nul" || return $?

  local -a build_arguments=(cargo build --target "$target" --release --timings)
  for build_argument in "${selected_binaries[@]}"; do
    build_arguments+=(--bin "$build_argument")
  done
  run_recorded "${build_arguments[@]}" || return $?
  complete_result
}

# Archives the selected package binaries and records the archive tools' inputs.
#
# Arguments:
#   Description: Target, runner OS, binaries, target directory, source root, workspace, runner temp, package version,
#                and result directory options.
# Environment: Reads only approved determining variables.
# Side effects: Normalizes PDB names, archives symbols, builds package archives, and retains operation records.
# Exit status: 0 on completion, 2 for invalid arguments, or the first failed child status.
archive_packages() {
  local target='' runner_os='' binaries='' target_dir='' source_root='' workspace='' runner_temp='' package_version='' result_dir=''
  local voice_release_dir='' voice_build_commit='' tooling_launcher='' observed_source_commit='' operation_status
  local command_index=0
  local release_dir archive_dir executable_suffix binary package_variant expected_pdb underscored_pdb extension
  local primary_package
  local -a package_arguments=()
  local -a selected_binaries=()
  parse_arguments 'target runner_os binaries target_dir source_root workspace runner_temp package_version result_dir voice_release_dir voice_build_commit tooling_launcher' "$@" || return $?
  validate_runner_os "$runner_os" || return $?
  if [[ -n "$voice_release_dir" || -n "$voice_build_commit" || -n "$tooling_launcher" ]]; then
    if [[ -z "$voice_release_dir" || -z "$voice_build_commit" || -z "$tooling_launcher" ]]; then
      argument_error 'Voice options must be supplied together: --voice-release-dir, --voice-build-commit, --tooling-launcher'
      return 2
    fi
    if [[ ! -f "$tooling_launcher" ]]; then
      argument_error "Tooling launcher is not a file: $tooling_launcher"
      return 2
    fi
    archive_dir="${workspace}/dist/${target}"
    for extension in gz zst; do
      if [[ -e "${archive_dir}/codex-package-${target}.tar.${extension}" || -L "${archive_dir}/codex-package-${target}.tar.${extension}" ]]; then
        argument_error "Primary voice archive output already exists: ${archive_dir}/codex-package-${target}.tar.${extension}"
        return 2
      fi
    done
  fi
  if [[ -z "$binaries" ]]; then
    argument_error 'Option --binaries requires at least one binary name'
    return 2
  fi
  if [[ ! -d "$source_root" ]]; then
    argument_error "Source root is not a directory: $source_root"
    return 2
  fi
  IFS=$' \t\n' read -r -a selected_binaries <<< "$binaries"
  if ((${#selected_binaries[@]} == 0)); then
    argument_error 'Option --binaries requires at least one binary name'
    return 2
  fi
  prepare_result_directory "$result_dir" || return $?
  write_common_metadata archive_packages "$target" "$runner_os" "$binaries" || return $?
  printf '%s\n' "$package_version" > "${result_dir}/package-version" || return $?
  record_environment "${result_dir}/environment.nul" || return $?

  if [[ -n "$voice_release_dir" ]]; then
    local source_commit_file="${result_dir}/.voice-source-commit.out"
    run_recorded git -C "$source_root" rev-parse HEAD > "$source_commit_file" || return $?
    IFS= read -r observed_source_commit < "$source_commit_file"
    if [[ "$voice_build_commit" != "$observed_source_commit" ]]; then
      printf 'Voice build commit does not match source HEAD: expected %s, observed %s\n' \
        "$voice_build_commit" "$observed_source_commit" >&2
      return 1
    fi
  fi

  release_dir="${target_dir}/${target}/release"
  archive_dir="${workspace}/dist/${target}"
  executable_suffix=''
  if [[ "$runner_os" == Windows ]]; then
    executable_suffix=.exe
    for binary in "${selected_binaries[@]}"; do
      expected_pdb="${release_dir}/${binary}.pdb"
      if [[ -f "$expected_pdb" ]]; then
        continue
      fi
      underscored_pdb="${release_dir}/${binary//-/_}.pdb"
      if [[ ! -f "$underscored_pdb" ]]; then
        printf 'PDB for %s not found at %s or %s\n' "$binary" "$expected_pdb" "$underscored_pdb" >&2
        return 1
      fi
      run_recorded cp "$underscored_pdb" "$expected_pdb" || return $?
    done
  fi

  run_recorded bash "${source_root}/.github/scripts/archive-release-symbols-and-strip-binaries.sh" \
    --target "$target" \
    --artifact-name "$target" \
    --release-dir "$release_dir" \
    --archive-dir "${workspace}/symbols-dist/${target}" \
    --binaries "$binaries" || return $?

  package_arguments=(
    --target "$target"
    --code-mode-host-bin "${release_dir}/codex-code-mode-host${executable_suffix}"
    --exec-server-bin "${release_dir}/codex-exec-server${executable_suffix}"
    --cargo-profile release
    --package-version "$package_version"
    --force
  )
  if [[ "$runner_os" == Linux ]]; then
    package_arguments+=(--bwrap-bin "${release_dir}/bwrap")
  fi
  if [[ "$runner_os" == Windows ]]; then
    package_arguments+=(
      --codex-command-runner-bin "${release_dir}/codex-command-runner.exe"
      --codex-windows-sandbox-setup-bin "${release_dir}/codex-windows-sandbox-setup.exe"
    )
  fi

  for package_variant in codex codex-app-server; do
    if [[ "$package_variant" == codex ]]; then
      binary=codex
    else
      binary=codex-app-server
    fi
    primary_package="${runner_temp}/codex-package-${target}"
    local -a build_package_command=(python3 "${source_root}/scripts/build_codex_package.py")
    build_package_command+=(
      "${package_arguments[@]}" \
      --variant "$package_variant" \
      --entrypoint-bin "${release_dir}/${binary}${executable_suffix}" \
      --package-dir "${runner_temp}/${binary}-package-${target}"
    )
    if [[ "$package_variant" == codex && -n "$voice_release_dir" ]]; then
      run_recorded "${build_package_command[@]}" || return $?
      if run_recorded bash "$tooling_launcher" voice-runtime stage \
        --release-dir "$voice_release_dir" \
        --package-dir "$primary_package" \
        --upstream-root "$source_root" \
        --target "$target" \
        --build-commit "$voice_build_commit" \
        --package-version "$package_version"; then
        :
      else
        operation_status=$?
        return "$operation_status"
      fi
      for extension in gz zst; do
        if PYTHONPATH="${source_root}/scripts${PYTHONPATH:+:${PYTHONPATH}}" \
          run_recorded python3 -m codex_package.archive write \
          --package-dir "$primary_package" \
          --archive-output "${archive_dir}/codex-package-${target}.tar.${extension}"; then
          :
        else
          operation_status=$?
          run_recorded rm -f -- \
            "${archive_dir}/codex-package-${target}.tar.gz" \
            "${archive_dir}/codex-package-${target}.tar.zst" || :
          return "$operation_status"
        fi
      done
    else
      build_package_command+=(
        --archive-output "${archive_dir}/${binary}-package-${target}.tar.gz" \
        --archive-output "${archive_dir}/${binary}-package-${target}.tar.zst"
      )
      run_recorded "${build_package_command[@]}" || return $?
    fi
  done
  complete_result
}

# Dispatches one of the package operations and returns its status to the caller.
#
# Arguments:
#   Description: One operation name followed by that operation's named options.
# Exit status: 0 on success, 2 for invalid command arguments, or the operation's failure status.
main() {
  local operation
  if (($# == 0)); then
    argument_error 'Expected select-binaries, profile-env, build-binaries, or archive-packages'
    return 2
  fi
  operation="$1"
  shift
  case "$operation" in
    select-binaries) select_binaries "$@" ;;
    profile-env) profile_env "$@" ;;
    build-binaries) build_binaries "$@" ;;
    archive-packages) archive_packages "$@" ;;
    *) argument_error "Unknown operation: $operation"; return 2 ;;
  esac
}

main "$@"
