package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.MacosUnwindReport;
import com.holdmyspot.codexunleashed.tooling.MacosUnwindReport.Attribution;
import com.holdmyspot.codexunleashed.tooling.MacosUnwindReport.Fde;
import com.holdmyspot.codexunleashed.tooling.MacosUnwindReport.Symbol;
import com.holdmyspot.codexunleashed.tooling.SystemCommands;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies the parsing, attribution and stack-walk judgement of the macOS unwind report on recorded tool output.
 */
public final class MacosUnwindReportTest
{
	/**
	 * Recorded {@code otool -l} output.
	 */
	private static final String OTOOL = """
		codex:
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
		""";
	/**
	 * Recorded {@code dwarfdump --eh-frame} output.
	 */
	private static final String DWARFDUMP = """

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
		""";
	/**
	 * Recorded {@code nm -n --defined-only} output.
	 */
	private static final String NM = """
		0000000100001000 t __ZN5tokio3run17hE
		0000000100002000 T _sqlite3_step
		0000000100002ff0 t __ZN10codex_core3run17hE
		0000000106000000 D _data
		                 U _write
		""";
	/**
	 * A walk's frames through the binary, ending at its {@code main}.
	 */
	private static final String MAIN_FRAMES = """
		unwind-frame 0x1 /tmp/x/trace.dylib traced_write
		unwind-frame 0x100002010 /bin/codex __ZN3std2io5stdio17hE
		unwind-frame 0x100003010 /bin/codex __ZN10codex_core3run17hE
		unwind-frame 0x100002030 /bin/codex main
		""";

	/**
	 * Creates the tests.
	 */
	public MacosUnwindReportTest()
	{
	}

	/**
	 * The sizes of the named sections come from {@code otool -l}, and a section that is not listed is left out.
	 */
	@Test
	public void readsSectionSizesFromOtool()
	{
		requireThat(MacosUnwindReport.sectionSizes(OTOOL, List.of("__eh_frame", "__unwind_info", "__gcc_except_tab")),
			"sizes").isEqualTo(Map.of("__eh_frame", 0x1200000L, "__unwind_info", 0x123456L));
	}

	/**
	 * Each FDE gives its section offset, its size including the length field and its first address, and a CIE is
	 * skipped.
	 */
	@Test
	public void readsFdesFromDwarfdump()
	{
		List<Fde> entries = new ArrayList<>();
		for (String line : DWARFDUMP.lines().toList())
		{
			Optional<Fde> fde = MacosUnwindReport.parseFde(line);
			fde.ifPresent(entries::add);
		}
		requireThat(entries, "entries").isEqualTo(List.of(new Fde(0x18, 0x20, 0x100001000L),
			new Fde(0x38, 0x28, 0x100002000L), new Fde(0x1000010, 0x30, 0x100003000L)));
	}

	/**
	 * Legacy Rust and Itanium C++ names give their first path segment, an impl gives its type's crate, a v0 Rust name
	 * gives its crate, and anything else is grouped as C or assembly.
	 */
	@Test
	public void derivesCrateNamesFromMangledSymbols()
	{
		requireThat(MacosUnwindReport.crateOf("__ZN5tokio7runtime4task3raw4poll17h0123456789abcdefE"), "rust").
			isEqualTo("tokio");
		requireThat(MacosUnwindReport.crateOf("__ZN2v88internal7Isolate4InitEv"), "cpp").isEqualTo("v8");
		requireThat(MacosUnwindReport.crateOf(
			"__ZN64_$LT$alloc..vec..Vec$LT$T$GT$$u20$as$u20$core..ops..drop..Drop$GT$4drop17h01E"), "impl").
			isEqualTo("alloc");
		requireThat(MacosUnwindReport.crateOf("__RNvCs1234_10codex_core3run"), "v0").isEqualTo("codex_core");
		requireThat(MacosUnwindReport.crateOf("_sqlite3_step"), "other").isEqualTo("(C or assembly)");
	}

	/**
	 * Each FDE counts toward the crate of the nearest symbol at or below its first address, and the FDEs that start past
	 * compact unwind's 16 MB reach are counted separately.
	 *
	 * @throws IOException if the recorded dump cannot be read
	 */
	@Test
	public void attributesFdeToContainingSymbol() throws IOException
	{
		List<Symbol> symbols = List.of(new Symbol(0x100001000L, "__ZN5tokio3run17hE"),
			new Symbol(0x100002000L, "_sqlite3_step"), new Symbol(0x100002ff0L, "__ZN10codex_core3run17hE"));
		Attribution attribution = MacosUnwindReport.attribute(new BufferedReader(new StringReader(DWARFDUMP)), symbols);
		requireThat(attribution.totals(), "totals").isEqualTo(
			Map.of("tokio", 0x20L, "(C or assembly)", 0x28L, "codex_core", 0x30L));
		requireThat(attribution.beyond(), "beyond").isEqualTo(Map.of("codex_core", 1L));
	}

	/**
	 * Defined text symbols come from {@code nm -n}, sorted by address; other kinds and undefined symbols are skipped.
	 */
	@Test
	public void readsTextSymbolsFromNm()
	{
		String output = "0000000100002000 T _main\n0000000100001000 t __ZN5tokio3run17hE\n" +
			"0000000106000000 D _data\n                 U _write\n";
		requireThat(MacosUnwindReport.textSymbols(output), "symbols").isEqualTo(
			List.of(new Symbol(0x100001000L, "__ZN5tokio3run17hE"), new Symbol(0x100002000L, "_main")));
	}

	/**
	 * The walk passes only when it ends at the end of the stack after frames in the binary that reach {@code main}, and
	 * fails when it stops early, never runs, or never leaves the interposer's library.
	 */
	@Test
	public void walkPassesOnlyThroughBinaryToMain()
	{
		requireThat(MacosUnwindReport.walkPassed(MAIN_FRAMES + "unwind-end 5 4\n", "/bin/codex").passed(),
			"reachedMain").isTrue();
		requireThat(MacosUnwindReport.walkPassed(MAIN_FRAMES + "unwind-end 9 4\n", "/bin/codex").passed(),
			"stoppedEarly").isFalse();
		requireThat(MacosUnwindReport.walkPassed("", "/bin/codex").passed(), "neverRan").isFalse();
		requireThat(MacosUnwindReport.walkPassed(
			"unwind-frame 0x1 /tmp/x/trace.dylib traced_write\nunwind-end 5 1\n", "/bin/codex").passed(),
			"neverLeftTheInterposer").isFalse();
		requireThat(MacosUnwindReport.walkPassed(MAIN_FRAMES + "unwind-end 5 4\n", "/bin/codex").own().size(),
			"ownFrames").isEqualTo(3);
	}

	/**
	 * A command line that runs on a spawned thread passes when the walk reaches the end of the stack at
	 * {@code _pthread_start} after frames in the binary, and fails when it stops before {@code _pthread_start}.
	 */
	@Test
	public void walkOnSpawnedThreadPassesAtStart()
	{
		String frames = """
			unwind-frame 0x1 /tmp/x/trace.dylib traced_write
			unwind-frame 0x100002010 /bin/codex __ZN3std2io5stdio17hE
			unwind-frame 0x100002020 /bin/codex _RNvNvMs0_NtNtNtCs1_3std3sys6thread4unix6Thread3new12thread_start
			""";
		String threadStart = "unwind-frame 0x196aa3bc8 /usr/lib/system/libsystem_pthread.dylib _pthread_start\n";
		requireThat(MacosUnwindReport.walkPassed(frames + threadStart + "unwind-end 5 4\n", "/bin/codex").passed(),
			"reachedThreadStart").isTrue();
		requireThat(MacosUnwindReport.walkPassed(frames + "unwind-end 5 3\n", "/bin/codex").passed(),
			"stoppedBeforeThreadStart").isFalse();
	}

	/**
	 * A report on a passing walk prints the section sizes, the largest crates, the walk and the verdict, and runs the
	 * binary with the tracer interposed.
	 */
	@Test
	public void reportsAPassingWalk()
	{
		FakeTools tools = new FakeTools(MAIN_FRAMES + "unwind-end 5 4\n");
		Output output = run(tools, "/bin/codex", "--version");
		requireThat(output.status, "status").isEqualTo(0);
		requireThat(output.err, "err").isEqualTo("");
		requireThat(output.out, "out").isEqualTo("""
			__eh_frame: 18874368 bytes (18.0 MiB)
			__unwind_info: 1193046 bytes (1.1 MiB)
			3 FDEs, 120 bytes; 1 start past the 16 MB that compact unwind can reach
			__eh_frame bytes by crate or library (largest 30):
			          48  40.0%  codex_core  (1 FDEs past 16 MB)
			          40  33.3%  (C or assembly)  (0 FDEs past 16 MB)
			          32  26.7%  tokio  (0 FDEs past 16 MB)
			Stack walk from the first write to standard output (exit status 0):
			unwind-frame 0x1 /tmp/x/trace.dylib traced_write
			unwind-frame 0x100002010 /bin/codex __ZN3std2io5stdio17hE
			unwind-frame 0x100003010 /bin/codex __ZN10codex_core3run17hE
			unwind-frame 0x100002030 /bin/codex main
			unwind-end 5 4
			3 frames in the binary, 1 in functions whose FDE starts past 16 MB
			Stack walk passed
			""".replace("\n", System.lineSeparator()));
		requireThat(tools.traced, "traced").isEqualTo(List.of("/bin/codex", "--version"));
		requireThat(tools.tracerEnvironment.containsKey("DYLD_INSERT_LIBRARIES"), "interposed").isTrue();
		requireThat(tools.compiled, "compiled").isTrue();
	}

	/**
	 * A report on a walk that stops early prints the verdict and fails.
	 */
	@Test
	public void reportsAFailingWalk()
	{
		Output output = run(new FakeTools(MAIN_FRAMES + "unwind-end 9 4\n"), "/bin/codex", "--version");
		requireThat(output.status, "status").isEqualTo(1);
		requireThat(output.out, "out").endsWith("Stack walk FAILED: it did not reach the end of the stack through main " +
			"or at _pthread_start" + System.lineSeparator());
	}

	/**
	 * A report without a program argument prints the usage and fails with the invalid-arguments status.
	 */
	@Test
	public void rejectsMissingProgramArguments()
	{
		Output output = run(new FakeTools(""), "/bin/codex");
		requireThat(output.status, "status").isEqualTo(2);
		requireThat(output.out, "out").isEqualTo("");
		requireThat(output.err, "err").contains("Usage: codex-tooling macos-unwind-report BINARY ARGUMENT...");
	}

	/**
	 * A native tool that fails stops the report with its message.
	 */
	@Test
	public void reportsANativeToolFailure()
	{
		FakeTools tools = new FakeTools("");
		tools.failure = "otool failed with status 1: not a Mach-O file";
		Output output = run(tools, "/bin/codex", "--version");
		requireThat(output.status, "status").isEqualTo(1);
		requireThat(output.err, "err").isEqualTo("ERROR: otool failed with status 1: not a Mach-O file" +
			System.lineSeparator());
	}

	/**
	 * Runs the report.
	 *
	 * @param tools     the native tools
	 * @param arguments the command arguments
	 * @return the status and the printed streams
	 */
	private static Output run(FakeTools tools, String... arguments)
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ByteArrayOutputStream err = new ByteArrayOutputStream();
		int status;
		try (PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
			PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8))
		{
			status = MacosUnwindReport.run(arguments, tools, outStream, errStream);
		}
		return new Output(status, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
	}

	/**
	 * A command's status and printed streams.
	 *
	 * @param status the exit status
	 * @param out    the standard output
	 * @param err    the standard error
	 */
	private record Output(int status, String out, String err)
	{
	}

	/**
	 * Answers the native tools with recorded output.
	 */
	private static final class FakeTools implements MacosUnwindReport.Tools
	{
		/**
		 * The tracer's standard error.
		 */
		private final String trace;
		/**
		 * The message of the failure to raise from the first tool that runs, or null.
		 */
		private String failure;
		/**
		 * Whether the tracer was compiled.
		 */
		private boolean compiled;
		/**
		 * The command that ran with the tracer.
		 */
		private List<String> traced;
		/**
		 * The environment that the traced command received.
		 */
		private Map<String, String> tracerEnvironment = Map.of();

		/**
		 * Creates tools.
		 *
		 * @param trace the traced run's standard error
		 */
		private FakeTools(String trace)
		{
			this.trace = trace;
		}

		@Override
		public String capture(List<String> command) throws IOException
		{
			if (failure != null)
				throw new IOException(failure);
			return switch (command.getFirst())
			{
				case "otool" -> OTOOL;
				case "nm" -> NM;
				case "cc" ->
				{
					compiled = true;
					yield "";
				}
				default -> throw new IOException("Unexpected tool " + command.getFirst());
			};
		}

		@Override
		public BufferedReader stream(List<String> command)
		{
			return new BufferedReader(new StringReader(DWARFDUMP));
		}

		@Override
		public SystemCommands.Result run(List<String> command, Map<String, String> environment)
		{
			traced = command;
			tracerEnvironment = environment;
			return new SystemCommands.Result(0, "", trace);
		}
	}
}
