#!/usr/bin/env python3
"""
Report a macOS binary's unwind tables and check that the unwinder can walk its stack.

ld warns when a binary's __eh_frame section is larger than the 16 MB that compact unwind's 24-bit DWARF offsets can
address. Functions whose FDE lies past that point are found by searching __eh_frame instead. This script reports:
  - the sizes of __eh_frame and __unwind_info (otool -l);
  - the __eh_frame bytes of each crate or library, from each FDE's first address and the symbol that contains it
    (dwarfdump --eh-frame and nm -n), and how many FDEs of each start past 16 MB;
  - a stack walk through the running binary: a library interposed on write() calls _Unwind_Backtrace, the unwinder
    that panics and Rust backtraces use, the first time the binary writes to standard output. The write can come
    from the main thread, whose walk passes through the binary's main, or from a spawned thread, whose walk ends at
    _pthread_start.

Usage:
  macos-unwind-report.py BINARY ARGUMENT...
      Report on BINARY, running it with the arguments for the stack walk, which must write to standard output. Exits 1
      if the walk does not reach the end of the stack, through the binary's main or at a spawned thread's entry.
"""

import bisect
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

# Compact unwind encodes a function's FDE offset in 24 bits.
COMPACT_REACH = 1 << 24
OTHER = "(C or assembly)"
FDE_LINE = re.compile(r"^([0-9a-f]+) ([0-9a-f]+) ([0-9a-f]+) FDE cie=[0-9a-f]+ pc=(?:0x)?([0-9a-f]+)\.\.\.")
LEGACY = re.compile(r"^_{1,2}ZN[KVrO]*(\d+)")
V0 = re.compile(r"C(?:s[0-9A-Za-z]*_)?(\d+)_?")
# _Unwind_Backtrace returns this when the walk reaches the outermost frame.
END_OF_STACK = 5
# The outermost function of a spawned thread's stack; codex runs its command line on one.
THREAD_ENTRY = "_pthread_start"

TRACER = r"""
#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <unistd.h>
#include <unwind.h>

static int traced;

static _Unwind_Reason_Code report_frame(struct _Unwind_Context *context, void *count) {
    uintptr_t address = _Unwind_GetIP(context);
    Dl_info info;
    const char *image = "?";
    const char *name = "?";
    if (dladdr((void *) address, &info)) {
        if (info.dli_fname) image = info.dli_fname;
        if (info.dli_sname) name = info.dli_sname;
    }
    fprintf(stderr, "unwind-frame %#lx %s %s\n", (unsigned long) address, image, name);
    ++*(int *) count;
    return _URC_NO_REASON;
}

static ssize_t traced_write(int descriptor, const void *buffer, size_t size) {
    if (descriptor == 1 && !traced) {
        traced = 1;
        int count = 0;
        _Unwind_Reason_Code reason = _Unwind_Backtrace(report_frame, &count);
        fprintf(stderr, "unwind-end %d %d\n", (int) reason, count);
    }
    return write(descriptor, buffer, size);
}

__attribute__((used, section("__DATA,__interpose"))) static const struct {
    const void *replacement;
    const void *original;
} interpose_write = {(const void *) traced_write, (const void *) write};
"""


def section_sizes(otool_output, names):
    """Return {section: size} for each named section that otool -l lists."""
    sizes = {}
    current = None
    for line in otool_output.splitlines():
        fields = line.split()
        if len(fields) == 2 and fields[0] == "sectname":
            current = fields[1]
        elif len(fields) == 2 and fields[0] == "size" and current in names:
            sizes[current] = int(fields[1], 16)
            current = None
    return sizes


def fdes(lines):
    """Yield (section offset, size including the length field, first address) for each FDE in dwarfdump's output."""
    for line in lines:
        match = FDE_LINE.match(line)
        if match:
            yield int(match.group(1), 16), int(match.group(2), 16) + 4, int(match.group(4), 16)


def crate_of(symbol):
    """Return the crate, or the C++ namespace, that a mangled symbol belongs to, or OTHER."""
    match = LEGACY.match(symbol)
    if match:
        start = match.end()
        identifier = symbol[start:start + int(match.group(1))]
        if identifier.startswith("_$LT$"):
            # An impl, such as <alloc::vec::Vec<T> as Drop>::drop, belongs to the crate of its type's path.
            identifier = re.split(r"\.\.|\$", identifier[len("_$LT$"):], maxsplit=1)[0]
        return identifier or OTHER
    if symbol.startswith(("_R", "__R")):
        match = V0.search(symbol)
        if match:
            start = match.end()
            return symbol[start:start + int(match.group(1))]
    return OTHER


def text_symbols(nm_output):
    """Return the (address, name) of each defined text symbol in nm -n's output, sorted by address."""
    symbols = []
    for line in nm_output.splitlines():
        fields = line.split(maxsplit=2)
        if len(fields) == 3 and fields[1] in ("t", "T"):
            symbols.append((int(fields[0], 16), fields[2]))
    symbols.sort()
    return symbols


def attribute(entries, symbols):
    """
    Return ({crate: FDE bytes}, {crate: FDEs past compact unwind's reach}), giving each FDE to the symbol at or below
    its first address.
    """
    addresses = [address for address, _ in symbols]
    totals = {}
    beyond = {}
    for offset, size, start in entries:
        index = bisect.bisect_right(addresses, start) - 1
        crate = crate_of(symbols[index][1]) if index >= 0 else OTHER
        totals[crate] = totals.get(crate, 0) + size
        if offset >= COMPACT_REACH:
            beyond[crate] = beyond.get(crate, 0) + 1
    return totals, beyond


def walk_passed(trace, binary):
    """
    Return (passed, frames in the binary) for the interposed walk's output: it passes when the unwinder reached the end
    of the stack after at least two frames in the binary, and the walk passed through the binary's own main or ended
    at a spawned thread's entry.
    """
    binary = os.path.realpath(binary)
    ends = [line.split() for line in trace.splitlines() if line.startswith("unwind-end ")]
    frames = [line.split(maxsplit=3) for line in trace.splitlines() if line.startswith("unwind-frame ")]
    own = [frame for frame in frames if len(frame) == 4 and os.path.realpath(frame[2]) == binary]
    reached_main = any(frame[3] == "main" for frame in own)
    reached_thread_entry = bool(frames) and len(frames[-1]) == 4 and frames[-1][3] == THREAD_ENTRY
    passed = (len(ends) == 1 and int(ends[0][1]) == END_OF_STACK and (reached_main or reached_thread_entry)
              and len(own) >= 2)
    return passed, own


def run(command):
    """Return a tool's standard output, failing on a nonzero status."""
    return subprocess.run(command, check=True, capture_output=True, text=True).stdout


def main(arguments):
    """Print the report and return 1 if the stack walk failed."""
    if len(arguments) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    binary, *program_arguments = arguments
    sizes = section_sizes(run(["otool", "-l", binary]), ["__eh_frame", "__unwind_info"])
    for name in ("__eh_frame", "__unwind_info"):
        size = sizes.get(name)
        print(f"{name}: {'absent' if size is None else f'{size} bytes ({size / 2**20:.1f} MiB)'}")

    symbols = text_symbols(run(["nm", "-n", "--defined-only", binary]))
    starts = []
    with subprocess.Popen(["dwarfdump", "--eh-frame", binary], stdout=subprocess.PIPE, text=True) as dump:
        def recorded():
            for entry in fdes(dump.stdout):
                starts.append((entry[2], entry[0]))
                yield entry
        totals, beyond = attribute(recorded(), symbols)
    if dump.returncode != 0:
        raise subprocess.CalledProcessError(dump.returncode, dump.args)
    total = sum(totals.values())
    print(f"{len(starts)} FDEs, {total} bytes; {sum(beyond.values())} start past the {COMPACT_REACH // 2**20} MB "
          "that compact unwind can reach")
    print("__eh_frame bytes by crate or library (largest 30):")
    for crate, size in sorted(totals.items(), key=lambda item: -item[1])[:30]:
        print(f"  {size:>10} {100 * size / total:5.1f}%  {crate}  ({beyond.get(crate, 0)} FDEs past 16 MB)")

    starts.sort()
    with tempfile.TemporaryDirectory() as directory:
        source = Path(directory) / "trace-write.c"
        source.write_text(TRACER)
        library = Path(directory) / "trace-write.dylib"
        run(["cc", "-dynamiclib", "-o", library.as_posix(), source.as_posix()])
        result = subprocess.run([binary, *program_arguments], capture_output=True, text=True,
                                env=dict(os.environ, DYLD_INSERT_LIBRARIES=library.as_posix()))
    passed, own = walk_passed(result.stderr, binary)
    print(f"Stack walk from the first write to standard output (exit status {result.returncode}):")
    print("\n".join(line for line in result.stderr.splitlines() if line.startswith("unwind-")))
    first_addresses = [start for start, _ in starts]
    past = 0
    for frame in own:
        # A return address lies after its call, so look up the byte before it.
        index = bisect.bisect_right(first_addresses, int(frame[1], 16) - 1) - 1
        if index >= 0 and starts[index][1] >= COMPACT_REACH:
            past += 1
    print(f"{len(own)} frames in the binary, {past} in functions whose FDE starts past 16 MB")
    print("Stack walk passed" if passed else
          f"Stack walk FAILED: it did not reach the end of the stack through main or at {THREAD_ENTRY}")
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
