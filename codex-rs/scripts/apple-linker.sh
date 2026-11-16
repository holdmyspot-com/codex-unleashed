#!/bin/sh
# Links an Apple target with `cc`, the linker rustc uses by default, and drops one ld notice from its stderr.
#
# The workspace denies `linker_messages`, so any linker output fails the build. The codex binary's `__eh_frame` section
# is larger than the 16 MB that compact unwind's 24-bit DWARF offsets can address, and ld reports that it falls back to
# a slower `__eh_frame` search for those functions. Unwinding still works; only panics that unwind pay the cost.
# Keeping compact unwind is the better trade than `-no_compact_unwind`, which would drop the fast path for every
# function. Only this exact line is removed: every other linker message still reaches rustc and fails the build.
# ld writes it as `ld: warning: ...`; rustc shows it as `ld: ...` because it drops `: warning: ` from linker output.
#
# Usage: apple-linker.sh ARGUMENT...
#   Runs `cc ARGUMENT...` with the same standard input and output, forwards its stderr without that line, and exits
#   with its status.
set -u
notice='ld: warning: __eh_frame section too large (max 16MB) to encode dwarf unwind offsets in compact unwind table, performance of exception handling might be affected'
log=$(mktemp "${TMPDIR:-/tmp}/apple-linker.XXXXXX") || exit 1
trap 'rm -f "$log"' EXIT
cc "$@" 2>"$log"
status=$?
# grep exits 1 when it prints nothing, which only means that every line was the notice.
grep -vxF -e "$notice" "$log" >&2
if [ $? -gt 1 ]; then
	cat "$log" >&2
fi
exit "$status"
