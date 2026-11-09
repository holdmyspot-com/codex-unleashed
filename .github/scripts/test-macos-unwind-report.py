#!/usr/bin/env python3
"""Exercise the parsing and attribution in macos-unwind-report.py on recorded tool output."""

import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location("macos_unwind_report",
                                              Path(__file__).with_name("macos-unwind-report.py"))
report = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(report)

OTOOL = """codex:
Load command 1
      cmd LC_SEGMENT_64
  cmdsize 712
  segname __TEXT
Section
  sectname __text
   segname __TEXT
      addr 0x0000000100001000
      size 0x0000000004000000
Section
  sectname __unwind_info
   segname __TEXT
      addr 0x0000000105000000
      size 0x0000000000123456
Section
  sectname __eh_frame
   segname __TEXT
      addr 0x0000000105200000
      size 0x0000000001200000
Section
  sectname __data
   segname __DATA
      addr 0x0000000106000000
      size 0x0000000000001000
"""

DWARFDUMP = """
__eh_frame contents:

00000000 00000014 00000000 CIE
  Format:                DWARF32
  Version:               1

00000018 0000001c 0000001c FDE cie=00000000 pc=0000000100001000...0000000100001040
  Format:       DWARF32
  DW_CFA_advance_loc: 4

00000038 00000024 0000003c FDE cie=00000000 pc=100002000...100002080
  DW_CFA_nop:

01000010 0000002c 01000014 FDE cie=00000000 pc=0000000100003000...0000000100003100
"""


class MacosUnwindReportTest(unittest.TestCase):
    """Check each parser against tool output and the attribution of FDE bytes to crates."""

    def test_section_sizes_are_read_from_otool(self):
        """The sizes of named sections come from otool -l, and a missing section is left out."""
        self.assertEqual(report.section_sizes(OTOOL, ["__eh_frame", "__unwind_info", "__gcc_except_tab"]),
                         {"__eh_frame": 0x1200000, "__unwind_info": 0x123456})

    def test_fdes_are_read_from_dwarfdump(self):
        """Each FDE gives its section offset, its size including the length field, and its first address; CIEs are
        skipped."""
        self.assertEqual(list(report.fdes(DWARFDUMP.splitlines())),
                         [(0x18, 0x20, 0x100001000), (0x38, 0x28, 0x100002000), (0x1000010, 0x30, 0x100003000)])

    def test_crate_names_come_from_mangled_symbols(self):
        """Legacy Rust and Itanium C++ names give their first path segment, an impl gives its type's crate, a v0 Rust
        name gives its crate, and anything else is grouped as C or assembly."""
        self.assertEqual(report.crate_of("__ZN5tokio7runtime4task3raw4poll17h0123456789abcdefE"), "tokio")
        self.assertEqual(report.crate_of("__ZN2v88internal7Isolate4InitEv"), "v8")
        self.assertEqual(
            report.crate_of("__ZN64_$LT$alloc..vec..Vec$LT$T$GT$$u20$as$u20$core..ops..drop..Drop$GT$4drop17h01E"),
            "alloc")
        self.assertEqual(report.crate_of("__RNvCs1234_10codex_core3run"), "codex_core")
        self.assertEqual(report.crate_of("_sqlite3_step"), "(C or assembly)")

    def test_fde_bytes_are_attributed_to_the_symbol_that_contains_each_function(self):
        """Each FDE counts toward the crate of the nearest symbol at or below its first address, and the FDEs that
        start past compact unwind's 16 MB reach are counted separately."""
        symbols = [(0x100001000, "__ZN5tokio3run17hE"), (0x100002000, "_sqlite3_step"),
                   (0x100002ff0, "__ZN10codex_core3run17hE")]
        totals, beyond = report.attribute(report.fdes(DWARFDUMP.splitlines()), symbols)
        self.assertEqual(totals, {"tokio": 0x20, "(C or assembly)": 0x28, "codex_core": 0x30})
        self.assertEqual(beyond, {"codex_core": 1})

    def test_symbols_are_read_from_nm(self):
        """Defined text symbols come from nm -n, sorted by address; other kinds and undefined symbols are skipped."""
        output = ("0000000100002000 T _main\n0000000100001000 t __ZN5tokio3run17hE\n"
                  "0000000106000000 D _data\n                 U _write\n")
        self.assertEqual(report.text_symbols(output), [(0x100001000, "__ZN5tokio3run17hE"), (0x100002000, "_main")])

    def test_walk_passes_only_when_it_reaches_main_through_the_binary(self):
        """The interposed walk passes when it ends at the end of the stack after frames in the binary that reach
        main, and fails when the walk stops early, never runs, or never leaves the interposer's library."""
        frames = ("unwind-frame 0x1 /tmp/x/trace.dylib traced_write\n"
                  "unwind-frame 0x100002010 /bin/codex __ZN3std2io5stdio17hE\n"
                  "unwind-frame 0x100002020 /bin/codex __ZN9codex_cli4main17hE\n"
                  "unwind-frame 0x100002030 /bin/codex main\n")
        self.assertTrue(report.walk_passed(frames + "unwind-end 5 4\n", "/bin/codex")[0])
        self.assertFalse(report.walk_passed(frames + "unwind-end 9 4\n", "/bin/codex")[0])
        self.assertFalse(report.walk_passed("", "/bin/codex")[0])
        self.assertFalse(report.walk_passed("unwind-frame 0x1 /tmp/x/trace.dylib traced_write\nunwind-end 5 1\n",
                                            "/bin/codex")[0])


if __name__ == "__main__":
    unittest.main()
