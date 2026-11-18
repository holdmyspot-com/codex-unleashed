package com.holdmyspot.codexunleashed.tooling;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.io.file.PathUtils;

/**
 * Reports a macOS binary's unwind tables and checks that the unwinder can walk its stack.
 * <p>
 * {@code ld} warns when a binary's {@code __eh_frame} section is larger than the 16 MB that compact unwind's 24-bit
 * DWARF offsets can address. Functions whose FDE lies past that point are found by searching {@code __eh_frame}
 * instead. The report gives:
 * <ul>
 * <li>the sizes of {@code __eh_frame} and {@code __unwind_info} ({@code otool -l});</li>
 * <li>the {@code __eh_frame} bytes of each crate or library, from each FDE's first address and the symbol that
 * contains it ({@code dwarfdump --eh-frame} and {@code nm -n}), and how many FDEs of each start past 16 MB;</li>
 * <li>a stack walk through the running binary: a library interposed on {@code write()} calls
 * {@code _Unwind_Backtrace}, the unwinder that panics and Rust backtraces use, the first time the binary writes to
 * standard output. The write can come from the main thread, whose walk passes through the binary's {@code main}, or
 * from a spawned thread, whose walk ends at {@code _pthread_start}.</li>
 * </ul>
 */
public final class MacosUnwindReport
{
	/**
	 * The command name.
	 */
	public static final String NAME = "macos-unwind-report";

	/**
	 * Compact unwind encodes a function's FDE offset in 24 bits.
	 */
	private static final long COMPACT_REACH = 1L << 24;
	/**
	 * Groups the FDEs that no crate contains, such as C and assembly functions.
	 */
	private static final String OTHER = "(C or assembly)";
	/**
	 * Selects the section offset, length, CIE pointer and first address of an FDE line of {@code dwarfdump}.
	 */
	private static final Pattern FDE_LINE = Pattern.compile(
		"([0-9a-f]+) ([0-9a-f]+) ([0-9a-f]+) FDE cie=[0-9a-f]+ pc=(?:0x)?([0-9a-f]+)\\.\\.\\.");
	/**
	 * Selects the length of the first path segment of a legacy Rust or Itanium C++ mangled name.
	 */
	private static final Pattern LEGACY = Pattern.compile("_{1,2}ZN[KVrO]*(\\d+)");
	/**
	 * Selects the length of the crate name of a v0 Rust mangled name.
	 */
	private static final Pattern V0 = Pattern.compile("C(?:s[0-9A-Za-z]*_)?(\\d+)_?");
	/**
	 * Ends the type path of an impl's legacy mangled name.
	 */
	private static final Pattern IMPL_PATH_END = Pattern.compile("\\.\\.|\\$");
	/**
	 * The value that {@code _Unwind_Backtrace} returns when the walk reaches the outermost frame.
	 */
	private static final int END_OF_STACK = 5;
	/**
	 * The outermost function of a spawned thread's stack; codex runs its command line on one.
	 */
	private static final String THREAD_ENTRY = "_pthread_start";
	/**
	 * The number of crates that the report lists.
	 */
	private static final int REPORTED_CRATES = 30;
	/**
	 * The number of bytes in a mebibyte.
	 */
	private static final double MEBIBYTE = 1 << 20;
	/**
	 * The unwind-table sections whose sizes are reported.
	 */
	private static final List<String> SECTIONS = List.of("__eh_frame", "__unwind_info");
	/**
	 * Names the arguments that the command accepts.
	 */
	private static final String USAGE = """
		Usage: codex-tooling macos-unwind-report BINARY ARGUMENT...
		    Report on BINARY, running it with the arguments for the stack walk, which must write to standard output.
		    Exits 1 if the walk does not reach the end of the stack, through the binary's main or at a spawned thread's
		    entry.""";
	/**
	 * The C source of the library that walks the stack at the first write to standard output.
	 */
	private static final String TRACER = """

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
		    fprintf(stderr, "unwind-frame %#lx %s %s\\n", (unsigned long) address, image, name);
		    ++*(int *) count;
		    return _URC_NO_REASON;
		}

		static ssize_t traced_write(int descriptor, const void *buffer, size_t size) {
		    if (descriptor == 1 && !traced) {
		        traced = 1;
		        int count = 0;
		        _Unwind_Reason_Code reason = _Unwind_Backtrace(report_frame, &count);
		        fprintf(stderr, "unwind-end %d %d\\n", (int) reason, count);
		    }
		    return write(descriptor, buffer, size);
		}

		__attribute__((used, section("__DATA,__interpose"))) static const struct {
		    const void *replacement;
		    const void *original;
		} interpose_write = {(const void *) traced_write, (const void *) write};
		""";

	/**
	 * Runs the native tools that the report reads. Implementations run each command in the caller's working directory
	 * with the caller's environment, plus any overrides that the caller names.
	 */
	public interface Tools
	{
		/**
		 * Runs a command to completion and returns its standard output.
		 *
		 * @param command the executable followed by its arguments
		 * @return the standard output
		 * @throws NullPointerException if {@code command} or an element is null
		 * @throws IOException          if the command cannot run or exits unsuccessfully
		 */
		String capture(List<String> command) throws IOException;

		/**
		 * Starts a command and returns a reader of its standard output, so that output too large to hold in memory is
		 * processed as it is produced.
		 *
		 * @param command the executable followed by its arguments
		 * @return the reader; closing it waits for the command and fails if the command exits unsuccessfully
		 * @throws NullPointerException if {@code command} or an element is null
		 * @throws IOException          if the command cannot start
		 */
		BufferedReader stream(List<String> command) throws IOException;

		/**
		 * Runs a command to completion with additional environment variables and returns its status and output. A
		 * nonzero status is a result, not a failure.
		 *
		 * @param command     the executable followed by its arguments
		 * @param environment variables added to or replacing the inherited environment
		 * @return the exit status and the decoded standard output and standard error
		 * @throws NullPointerException if an argument or element is null
		 * @throws IOException          if the command cannot run
		 */
		SystemCommands.Result run(List<String> command, Map<String, String> environment) throws IOException;
	}

	/**
	 * One frame description entry of {@code __eh_frame}.
	 *
	 * @param offset the entry's offset within the section
	 * @param size   the entry's size, including its length field
	 * @param start  the first address of the function that the entry describes
	 */
	public record Fde(long offset, long size, long start)
	{
	}

	/**
	 * A defined text symbol.
	 *
	 * @param address the symbol's address
	 * @param name    the mangled name
	 */
	public record Symbol(long address, String name)
	{
	}

	/**
	 * The {@code __eh_frame} bytes that each crate owns.
	 *
	 * @param totals  the bytes of the FDEs that belong to each crate, in order of first appearance
	 * @param beyond  the number of each crate's FDEs that start past compact unwind's reach
	 * @param entries every FDE, in dump order
	 */
	public record Attribution(Map<String, Long> totals, Map<String, Long> beyond, List<Fde> entries)
	{
		/**
		 * Copies the collections.
		 *
		 * @param totals  the bytes of the FDEs that belong to each crate
		 * @param beyond  the number of each crate's FDEs that start past compact unwind's reach
		 * @param entries every FDE
		 */
		public Attribution
		{
			totals = Map.copyOf(totals);
			beyond = Map.copyOf(beyond);
			entries = List.copyOf(entries);
		}
	}

	/**
	 * A stack frame in the binary that the walk reported.
	 *
	 * @param address the frame's return address as printed by the tracer, with a {@code 0x} prefix
	 * @param image   the path of the image that contains the address
	 * @param symbol  the name of the function that contains the address
	 */
	public record Frame(String address, String image, String symbol)
	{
	}

	/**
	 * The outcome of a stack walk.
	 *
	 * @param passed whether the unwinder reached the end of the stack after frames in the binary, through the binary's
	 *               {@code main} or at a spawned thread's entry
	 * @param own    the frames in the binary
	 */
	public record Walk(boolean passed, List<Frame> own)
	{
		/**
		 * Copies the frames.
		 *
		 * @param passed whether the walk passed
		 * @param own    the frames in the binary
		 */
		public Walk
		{
			own = List.copyOf(own);
		}
	}

	/**
	 * Prevents construction.
	 */
	private MacosUnwindReport()
	{
	}

	/**
	 * Reports on a binary using the host's native tools.
	 *
	 * @param arguments the binary, then the arguments with which to run it for the stack walk
	 * @param out       the report
	 * @param err       diagnostics
	 * @return zero if the stack walk passed, one if it failed or a native tool failed, or two for invalid command
	 *         arguments
	 * @throws NullPointerException if any argument is null
	 */
	public static int run(String[] arguments, PrintStream out, PrintStream err)
	{
		return run(arguments, new SystemTools(), out, err);
	}

	/**
	 * Reports on a binary using the supplied native tools.
	 *
	 * @param arguments the binary, then the arguments with which to run it for the stack walk
	 * @param tools     runs {@code otool}, {@code nm}, {@code dwarfdump}, {@code cc} and the binary
	 * @param out       the report
	 * @param err       diagnostics
	 * @return zero if the stack walk passed, one if it failed or a native tool failed, or two for invalid command
	 *         arguments
	 * @throws NullPointerException if any argument is null
	 */
	public static int run(String[] arguments, Tools tools, PrintStream out, PrintStream err)
	{
		Objects.requireNonNull(arguments, "arguments");
		Objects.requireNonNull(tools, "tools");
		Objects.requireNonNull(out, "out");
		Objects.requireNonNull(err, "err");

		if (arguments.length < 2)
		{
			err.println(USAGE);
			return 2;
		}

		try
		{
			String binary = arguments[0];
			printSectionSizes(tools, binary, out);
			List<Symbol> symbols = textSymbols(tools.capture(List.of("nm", "-n", "--defined-only", binary)));
			Attribution attribution;
			try (BufferedReader dump = tools.stream(List.of("dwarfdump", "--eh-frame", binary)))
			{
				attribution = attribute(dump, symbols);
			}
			List<Fde> starts = printAttribution(attribution, out);

			SystemCommands.Result result = runTraced(tools, List.of(arguments));
			Walk walk = walkPassed(result.stderr(), binary);
			out.println("Stack walk from the first write to standard output (exit status " + result.status() + "):");
			List<String> traceLines = result.stderr().lines().filter(line -> line.startsWith("unwind-")).toList();
			if (traceLines.isEmpty())
				out.println();
			for (String line : traceLines)
				out.println(line);
			int past = countPastReach(walk.own(), starts);
			out.println(walk.own().size() + " frames in the binary, " + past +
				" in functions whose FDE starts past 16 MB");
			if (walk.passed())
			{
				out.println("Stack walk passed");
				return 0;
			}
			out.println("Stack walk FAILED: it did not reach the end of the stack through main or at " + THREAD_ENTRY);
			return 1;
		}
		catch (IOException | IllegalArgumentException failure)
		{
			err.println("ERROR: " + failure.getMessage());
			return 1;
		}
	}

	/**
	 * Returns the size of each named section that {@code otool -l} lists.
	 *
	 * @param otoolOutput the output of {@code otool -l}
	 * @param names       the sections of interest
	 * @return each listed section's size in bytes; a section that the output does not list is absent
	 * @throws NullPointerException     if an argument is null
	 * @throws IllegalArgumentException if a size is not hexadecimal
	 */
	public static Map<String, Long> sectionSizes(String otoolOutput, Collection<String> names)
	{
		Objects.requireNonNull(otoolOutput, "otoolOutput");
		Objects.requireNonNull(names, "names");

		Map<String, Long> sizes = new LinkedHashMap<>();
		String current = null;
		for (String line : otoolOutput.lines().toList())
		{
			List<String> fields = split(line, -1);
			if (fields.size() == 2 && fields.getFirst().equals("sectname"))
				current = fields.get(1);
			else if (fields.size() == 2 && fields.getFirst().equals("size") && current != null &&
				names.contains(current))
			{
				sizes.put(current, parseHex(fields.get(1)));
				current = null;
			}
		}
		return sizes;
	}

	/**
	 * Reads the FDE that a line of {@code dwarfdump --eh-frame} describes.
	 *
	 * @param line one output line
	 * @return the FDE, or empty if the line does not start an FDE, as a CIE's does not
	 * @throws NullPointerException if {@code line} is null
	 */
	public static Optional<Fde> parseFde(String line)
	{
		Matcher match = FDE_LINE.matcher(line);
		if (!match.lookingAt())
			return Optional.empty();
		return Optional.of(new Fde(parseHex(match.group(1)), parseHex(match.group(2)) + 4, parseHex(match.group(4))));
	}

	/**
	 * Returns the crate, or the C++ namespace, that a mangled symbol belongs to.
	 *
	 * @param symbol the mangled symbol name
	 * @return the first path segment of a legacy Rust or Itanium C++ name, the crate of the type's path for an impl, the
	 *         crate of a v0 Rust name, or {@code (C or assembly)} for any other name
	 * @throws NullPointerException if {@code symbol} is null
	 */
	public static String crateOf(String symbol)
	{
		Matcher legacy = LEGACY.matcher(symbol);
		if (legacy.lookingAt())
		{
			String identifier = segment(symbol, legacy.end(), legacy.group(1));
			if (identifier.startsWith("_$LT$"))
			{
				// An impl, such as <alloc::vec::Vec<T> as Drop>::drop, belongs to the crate of its type's path.
				identifier = IMPL_PATH_END.split(identifier.substring("_$LT$".length()), 2)[0];
			}
			if (identifier.isEmpty())
				return OTHER;
			return identifier;
		}
		if (symbol.startsWith("_R") || symbol.startsWith("__R"))
		{
			Matcher v0 = V0.matcher(symbol);
			if (v0.find())
				return segment(symbol, v0.end(), v0.group(1));
		}
		return OTHER;
	}

	/**
	 * Returns the defined text symbols of {@code nm -n} output.
	 *
	 * @param nmOutput the output of {@code nm -n --defined-only}
	 * @return the {@code t} and {@code T} symbols sorted by address and then name; other kinds and undefined symbols
	 *         are skipped
	 * @throws NullPointerException     if {@code nmOutput} is null
	 * @throws IllegalArgumentException if a symbol's address is not hexadecimal
	 */
	public static List<Symbol> textSymbols(String nmOutput)
	{
		Objects.requireNonNull(nmOutput, "nmOutput");

		List<Symbol> symbols = new ArrayList<>();
		for (String line : nmOutput.lines().toList())
		{
			List<String> fields = split(line, 2);
			if (fields.size() == 3 && (fields.get(1).equals("t") || fields.get(1).equals("T")))
				symbols.add(new Symbol(parseHex(fields.getFirst()), fields.get(2)));
		}
		symbols.sort(Comparator.comparing(Symbol::address, Long::compareUnsigned).thenComparing(Symbol::name));
		return symbols;
	}

	/**
	 * Gives each FDE of a {@code dwarfdump --eh-frame} dump to the symbol at or below its first address.
	 *
	 * @param dump    the dump's lines; remains open
	 * @param symbols the text symbols, sorted by address
	 * @return the bytes and the number of FDEs past compact unwind's reach for each crate
	 * @throws NullPointerException     if an argument is null
	 * @throws IllegalArgumentException if a line holds an FDE with a number that does not fit
	 * @throws IOException              if the dump cannot be read
	 */
	public static Attribution attribute(BufferedReader dump, List<Symbol> symbols) throws IOException
	{
		Objects.requireNonNull(dump, "dump");
		Objects.requireNonNull(symbols, "symbols");

		long[] addresses = symbols.stream().mapToLong(Symbol::address).toArray();
		Map<String, Long> totals = new LinkedHashMap<>();
		Map<String, Long> beyond = new LinkedHashMap<>();
		List<Fde> entries = new ArrayList<>();
		for (String line = dump.readLine(); line != null; line = dump.readLine())
		{
			Optional<Fde> fde = parseFde(line);
			if (fde.isEmpty())
				continue;
			Fde entry = fde.get();
			entries.add(entry);
			int index = countAtOrBelow(addresses, entry.start()) - 1;
			String crate = OTHER;
			if (index >= 0)
				crate = crateOf(symbols.get(index).name());
			totals.merge(crate, entry.size(), Long::sum);
			if (entry.offset() >= COMPACT_REACH)
				beyond.merge(crate, 1L, Long::sum);
		}
		return new Attribution(totals, beyond, entries);
	}

	/**
	 * Judges the tracer's output.
	 *
	 * @param trace  the standard error of the traced run
	 * @param binary the path of the binary that was run
	 * @return whether the walk passed, and the frames in the binary. It passes when the unwinder reached the end of the
	 *         stack after at least two frames in the binary, and the walk passed through the binary's own {@code main}
	 *         or ended at a spawned thread's entry.
	 * @throws NullPointerException     if an argument is null
	 * @throws IllegalArgumentException if the walk's end line has no result code or a code that is not an integer
	 */
	public static Walk walkPassed(String trace, String binary)
	{
		Objects.requireNonNull(trace, "trace");
		Objects.requireNonNull(binary, "binary");

		Path binaryPath = resolve(binary);
		List<List<String>> ends = new ArrayList<>();
		List<List<String>> frames = new ArrayList<>();
		for (String line : trace.lines().toList())
		{
			if (line.startsWith("unwind-end "))
				ends.add(split(line, -1));
			else if (line.startsWith("unwind-frame "))
				frames.add(split(line, 3));
		}
		List<Frame> own = new ArrayList<>();
		boolean reachedMain = false;
		for (List<String> frame : frames)
		{
			if (frame.size() == 4 && resolve(frame.get(2)).equals(binaryPath))
			{
				own.add(new Frame(frame.get(1), frame.get(2), frame.get(3)));
				if (frame.get(3).equals("main"))
					reachedMain = true;
			}
		}
		boolean reachedThreadEntry = !frames.isEmpty() && frames.getLast().size() == 4 &&
			frames.getLast().get(3).equals(THREAD_ENTRY);
		boolean passed = ends.size() == 1 && endCode(ends.getFirst()) == END_OF_STACK &&
			(reachedMain || reachedThreadEntry) && own.size() >= 2;
		return new Walk(passed, own);
	}

	/**
	 * Prints the sizes of the unwind-table sections.
	 *
	 * @param tools  runs {@code otool}
	 * @param binary the binary
	 * @param out    the report
	 * @throws IOException if {@code otool} fails
	 */
	private static void printSectionSizes(Tools tools, String binary, PrintStream out) throws IOException
	{
		Map<String, Long> sizes = sectionSizes(tools.capture(List.of("otool", "-l", binary)), SECTIONS);
		for (String name : SECTIONS)
		{
			Long size = sizes.get(name);
			if (size == null)
				out.println(name + ": absent");
			else
				out.println(name + ": " + size + " bytes (" + fixed(size / MEBIBYTE, 1) + " MiB)");
		}
	}

	/**
	 * Prints the {@code __eh_frame} bytes of the largest crates.
	 *
	 * @param attribution the bytes that each crate owns
	 * @param out         the report
	 * @return the FDEs sorted by first address and then offset
	 */
	private static List<Fde> printAttribution(Attribution attribution, PrintStream out)
	{
		long total = attribution.totals().values().stream().mapToLong(Long::longValue).sum();
		long pastReach = attribution.beyond().values().stream().mapToLong(Long::longValue).sum();
		out.println(attribution.entries().size() + " FDEs, " + total + " bytes; " + pastReach + " start past the " +
			(COMPACT_REACH >> 20) + " MB that compact unwind can reach");
		out.println("__eh_frame bytes by crate or library (largest " + REPORTED_CRATES + "):");
		List<Map.Entry<String, Long>> crates = new ArrayList<>(attribution.totals().entrySet());
		crates.sort(Map.Entry.<String, Long>comparingByValue().reversed());
		for (Map.Entry<String, Long> crate : crates.stream().limit(REPORTED_CRATES).toList())
		{
			long size = crate.getValue();
			out.println("  " + pad(Long.toString(size), 10) + " " + pad(fixed(100L * size / (double) total, 1), 5) + "%  " +
				crate.getKey() + "  (" + attribution.beyond().getOrDefault(crate.getKey(), 0L) + " FDEs past 16 MB)");
		}

		List<Fde> starts = new ArrayList<>(attribution.entries());
		starts.sort(Comparator.comparing(Fde::start, Long::compareUnsigned).thenComparing(Fde::offset));
		return starts;
	}

	/**
	 * Compiles the tracer and runs the binary with it interposed.
	 *
	 * @param tools   runs {@code cc} and the binary
	 * @param command the binary followed by the arguments that make it write to standard output
	 * @return the binary's status and output
	 * @throws IOException if the tracer cannot be built or the binary cannot be run
	 */
	private static SystemCommands.Result runTraced(Tools tools, List<String> command) throws IOException
	{
		Path directory = Files.createTempDirectory("macos-unwind-");
		try
		{
			Path source = directory.resolve("trace-write.c");
			Files.writeString(source, TRACER);
			Path library = directory.resolve("trace-write.dylib");
			tools.capture(List.of("cc", "-dynamiclib", "-o", library.toString(), source.toString()));
			return tools.run(command, Map.of("DYLD_INSERT_LIBRARIES", library.toString()));
		}
		finally
		{
			PathUtils.deleteDirectory(directory);
		}
	}

	/**
	 * Counts the walk's frames in the binary whose function's FDE starts past compact unwind's reach.
	 *
	 * @param own    the frames in the binary
	 * @param starts the FDEs sorted by first address and then offset
	 * @return the number of such frames
	 * @throws IllegalArgumentException if a frame's address is not hexadecimal
	 */
	private static int countPastReach(List<Frame> own, List<Fde> starts)
	{
		long[] firstAddresses = starts.stream().mapToLong(Fde::start).toArray();
		int past = 0;
		for (Frame frame : own)
		{
			long address = parseHex(frame.address());
			if (address == 0)
				continue;
			// A return address lies after its call, so look up the byte before it.
			int index = countAtOrBelow(firstAddresses, address - 1) - 1;
			if (index >= 0 && starts.get(index).offset() >= COMPACT_REACH)
				++past;
		}
		return past;
	}

	/**
	 * Returns the result code that a walk's end line reports.
	 *
	 * @param end the fields of the end line
	 * @return the code
	 * @throws IllegalArgumentException if the line has no code or the code is not an integer
	 */
	private static int endCode(List<String> end)
	{
		if (end.size() < 2)
			throw new IllegalArgumentException("The unwind-end line has no result code");
		return Integer.parseInt(end.get(1));
	}

	/**
	 * Returns the part of a mangled name that a length prefix delimits.
	 *
	 * @param symbol the mangled name
	 * @param start  the index after the length prefix
	 * @param length the decimal length prefix
	 * @return the segment, shortened where the name ends first
	 */
	private static String segment(String symbol, int start, String length)
	{
		int count = Integer.MAX_VALUE;
		if (length.length() < 10)
			count = Integer.parseInt(length);
		return symbol.substring(start, (int) Math.min(symbol.length(), (long) start + count));
	}

	/**
	 * Counts the leading elements that are at or below a key, which is the index at which the key would follow them.
	 *
	 * @param sorted the elements in unsigned ascending order
	 * @param key    the key
	 * @return the number of elements that are at or below the key
	 */
	private static int countAtOrBelow(long[] sorted, long key)
	{
		int low = 0;
		int high = sorted.length;
		while (low < high)
		{
			int middle = (low + high) >>> 1;
			if (Long.compareUnsigned(sorted[middle], key) <= 0)
				low = middle + 1;
			else
				high = middle;
		}
		return low;
	}

	/**
	 * Splits text at runs of whitespace.
	 *
	 * @param text     the text
	 * @param maxSplit the maximum number of splits, after which the rest of the text is the last field with its trailing
	 *                 whitespace; negative for no limit
	 * @return the fields
	 */
	private static List<String> split(String text, int maxSplit)
	{
		List<String> fields = new ArrayList<>();
		int index = 0;
		while (true)
		{
			while (index < text.length() && Character.isWhitespace(text.charAt(index)))
				++index;
			if (index >= text.length())
				return fields;
			if (fields.size() == maxSplit)
			{
				fields.add(text.substring(index));
				return fields;
			}
			int start = index;
			while (index < text.length() && !Character.isWhitespace(text.charAt(index)))
				++index;
			fields.add(text.substring(start, index));
		}
	}

	/**
	 * Parses a hexadecimal number that may have a {@code 0x} prefix.
	 *
	 * @param text the number
	 * @return the value, as an unsigned 64-bit number
	 * @throws IllegalArgumentException if the text is not a hexadecimal number
	 */
	private static long parseHex(String text)
	{
		if (text.startsWith("0x") || text.startsWith("0X"))
			return Long.parseUnsignedLong(text.substring(2), 16);
		return Long.parseUnsignedLong(text, 16);
	}

	/**
	 * Resolves a path through symbolic links as far as it exists, so that two spellings of one file compare equal.
	 *
	 * @param text the path
	 * @return the absolute path with the symbolic links of its existing part resolved
	 */
	private static Path resolve(String text)
	{
		Path absolute = Path.of(text).toAbsolutePath().normalize();
		Path existing = absolute;
		while (existing != null && !Files.exists(existing))
			existing = existing.getParent();
		if (existing == null)
			return absolute;
		try
		{
			return existing.toRealPath().resolve(existing.relativize(absolute));
		}
		catch (IOException _)
		{
			return absolute;
		}
	}

	/**
	 * Formats a number with a fixed count of decimals, rounding its exact value half to even.
	 *
	 * @param value    the number
	 * @param decimals the number of decimals
	 * @return the decimal text
	 */
	private static String fixed(double value, int decimals)
	{
		return BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_EVEN).toPlainString();
	}

	/**
	 * Right-aligns text.
	 *
	 * @param text  the text
	 * @param width the minimum width
	 * @return the text with leading spaces
	 */
	private static String pad(String text, int width)
	{
		return " ".repeat(Math.max(0, width - text.length())) + text;
	}

	/**
	 * Runs the host's native tools.
	 */
	private static final class SystemTools implements Tools
	{
		/**
		 * The working directory of every command, which is the caller's.
		 */
		private final Path workingDirectory = Path.of("").toAbsolutePath();
		/**
		 * The parent of the directories that hold a command's captured output.
		 */
		private final Path temporaryDirectory = Path.of(System.getProperty("java.io.tmpdir"));

		/**
		 * Creates the host's tools.
		 */
		private SystemTools()
		{
		}

		@Override
		public String capture(List<String> command) throws IOException
		{
			SystemCommands.Result result = SystemCommands.capture(command, workingDirectory, temporaryDirectory);
			if (result.status() != 0)
			{
				throw new IOException(command.getFirst() + " failed with status " + result.status() + ": " +
					result.stderr().strip());
			}
			return result.stdout();
		}

		@Override
		public BufferedReader stream(List<String> command) throws IOException
		{
			Process process = SystemCommands.createBuilder(command).directory(workingDirectory.toFile()).
				redirectInput(ProcessBuilder.Redirect.INHERIT).redirectError(ProcessBuilder.Redirect.INHERIT).start();
			return new ProcessReader(process, command.getFirst());
		}

		@Override
		public SystemCommands.Result run(List<String> command, Map<String, String> environment) throws IOException
		{
			return SystemCommands.capture(command, workingDirectory, temporaryDirectory, environment);
		}
	}

	/**
	 * Reads a process's standard output and, on close, requires the process to have succeeded.
	 */
	private static final class ProcessReader extends BufferedReader
	{
		/**
		 * The process whose standard output is read.
		 */
		private final Process process;
		/**
		 * The process's executable, for diagnostics.
		 */
		private final String tool;

		/**
		 * Reads the process's standard output.
		 *
		 * @param process the started process
		 * @param tool    the process's executable
		 */
		private ProcessReader(Process process, String tool)
		{
			super(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
			this.process = process;
			this.tool = tool;
		}

		@Override
		public void close() throws IOException
		{
			try
			{
				super.close();
				int status = process.waitFor();
				if (status != 0)
					throw new IOException(tool + " failed with status " + status);
			}
			catch (InterruptedException failure)
			{
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while waiting for " + tool, failure);
			}
			finally
			{
				process.destroyForcibly();
			}
		}
	}
}
