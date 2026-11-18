package com.holdmyspot.codexunleashed.tooling;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Writes a direct Linux, portable Unix or Windows process diagnostic report.
 */
public final class QuietBuildDump
{
	/**
	 * Linux task stacks printed before the remaining state-selected tasks.
	 */
	private static final int TASK_STACK_LIMIT = 16;
	/**
	 * Maximum captured text retained in one read buffer.
	 */
	private static final int COPY_BUFFER_SIZE = 8192;
	/**
	 * Time allowed for the portable process listing command.
	 */
	private static final Duration PS_TIMEOUT = Duration.ofSeconds(60);
	/**
	 * Time allowed for the Windows process query.
	 */
	private static final Duration WINDOWS_QUERY_TIMEOUT = Duration.ofSeconds(300);
	/**
	 * Time allowed for a JVM thread dump.
	 */
	private static final Duration JVM_DUMP_TIMEOUT = Duration.ofSeconds(30);
	/**
	 * Time allowed for a mixed Java and native JVM dump.
	 */
	private static final Duration MIXED_DUMP_TIMEOUT = Duration.ofSeconds(300);
	/**
	 * Time allowed for one native sample of a selected process.
	 */
	private static final Duration SAMPLE_TIMEOUT = Duration.ofSeconds(120);
	/**
	 * Exact portable process listing request used by the frozen diagnostic report.
	 */
	private static final List<String> PS_COMMAND = List.of("ps", "-axww", "-o",
		"pid=,ppid=,uid=,state=,pgid=,sess=,tt=,wchan=,command=");
	/**
	 * Process query used by the frozen Windows diagnostic report.
	 */
	private static final String WINDOWS_PROCESS_QUERY = """
		$threads = Get-CimInstance Win32_Thread | Group-Object ProcessHandle -AsHashTable -AsString
		Get-CimInstance Win32_Process | ForEach-Object {
		  $own = if ($threads) { @($threads[[string]$_.ProcessId]) } else { @() }
		  $own = @($own | Where-Object { $_ })
		  [pscustomobject]@{ pid = $_.ProcessId; ppid = $_.ParentProcessId; name = $_.Name; command = $_.CommandLine;
		    threads = $own.Count; suspended = @($own | Where-Object { $_.ThreadWaitReason -eq 5 }).Count }
		} | ConvertTo-Json -Compress
		""";
	/**
	 * Injected tooling scope providing the shared JSON parser; borrowed for this dump.
	 */
	private final ToolingScope scope;
	/**
	 * Process-capture access borrowed for this dump.
	 */
	private final QuietBuildCapture.Access capture;
	/**
	 * Complete child-process context borrowed for diagnostic commands.
	 */
	private final QuietBuildCapture.Context context;
	/**
	 * Platform selected by the host composition boundary or injected process-tree test.
	 */
	private final Platform platform;
	/**
	 * Injected procfs root used by Linux process and task reads.
	 */
	private final Path procfs;
	/**
	 * Native identity and Darwin process facts borrowed for this dump.
	 */
	private final QuietBuildNativeFacts.Access nativeFacts;

	/**
	 * Supported host process report platforms.
	 */
	public enum Platform
	{
		/**
		 * Linux procfs report.
		 */
		LINUX,
		/**
		 * Darwin portable process listing and native stop facts.
		 */
		DARWIN,
		/**
		 * Windows PowerShell process report.
		 */
		WINDOWS
	}

	/**
	 * Creates a process dump operation using the supplied borrowed collaborators.
	 *
	 * @param scope       invocation scope providing the shared JSON mapper
	 * @param capture     child diagnostic process capture
	 * @param context     child invocation paths and explicitly supplied variables
	 * @param platform    report platform
	 * @param procfs      Linux process root, or a path whose absence selects the portable report
	 * @param nativeFacts effective identity and Darwin stop-fact access
	 * @throws NullPointerException if any required argument is null
	 */
	public QuietBuildDump(ToolingScope scope, QuietBuildCapture.Access capture, QuietBuildCapture.Context context,
		Platform platform, Path procfs, QuietBuildNativeFacts.Access nativeFacts)
	{
		requireThat(scope, "scope").isNotNull();
		requireThat(capture, "capture").isNotNull();
		requireThat(context, "context").isNotNull();
		requireThat(platform, "platform").isNotNull();
		requireThat(procfs, "procfs").isNotNull();
		requireThat(nativeFacts, "nativeFacts").isNotNull();
		this.scope = scope;
		this.capture = capture;
		this.context = context;
		this.platform = platform;
		this.procfs = procfs;
		this.nativeFacts = nativeFacts;
	}

	/**
	 * Creates a process dump operation with native facts for the actual host platform.
	 *
	 * @param scope    invocation scope providing shared native and JSON services
	 * @param capture  child diagnostic process capture
	 * @param context  child invocation paths and explicitly supplied variables
	 * @param platform actual host platform
	 * @param procfs   Linux process root, or a path whose absence selects the portable report
	 * @return host dump operation borrowing the supplied collaborators
	 * @throws NullPointerException          if any required argument is null
	 * @throws UnsupportedOperationException if native facts do not support the actual host ABI
	 */
	public static QuietBuildDump create(ToolingScope scope, QuietBuildCapture.Access capture,
		QuietBuildCapture.Context context, Platform platform, Path procfs)
	{
		return new QuietBuildDump(scope, capture, context, platform, procfs,
			new QuietBuildNativeFacts(scope, platform).access());
	}

	/**
	 * Writes and flushes one direct host report without closing the report writer.
	 *
	 * @param jcmd JVM diagnostic executable, or null when no JVM thread dump is requested
	 * @param output destination report writer
	 * @throws NullPointerException if {@code output} is null
	 * @throws IOException if report output, spool access or owned cleanup fails
	 */
	public void dump(Path jcmd, Writer output) throws IOException
	{
		requireThat(output, "output").isNotNull();
		if (platform == Platform.WINDOWS)
			dumpWindows(jcmd, output);
		else if (Files.isDirectory(procfs))
			dumpLinux(jcmd, output);
		else
			dumpWithoutProcfs(jcmd, output);
		output.flush();
	}

	/**
	 * Performs data for dumpLinux.
	 *
	 * @param jcmd supplied jcmd
	 * @param output supplied output
	 * @throws IOException when the operation fails
	 */
	private void dumpLinux(Path jcmd, Writer output) throws IOException
	{
		try (QuietBuildDumpSpool spool = new QuietBuildDumpSpool(context.temporaryDirectory()))
		{
			Path processIds = spool.sortedIds(procfs);
			writeLinuxProcesses(processIds, output);
			writeLinuxDetails(processIds, spool, jcmd, output);
		}
	}

	/**
	 * Performs data for writeLinuxProcesses.
	 *
	 * @param processIds supplied processIds
	 * @param output supplied output
	 * @throws IOException when the operation fails
	 */
	private void writeLinuxProcesses(Path processIds, Writer output) throws IOException
	{
		try (BufferedReader identifiers = Files.newBufferedReader(processIds, StandardCharsets.UTF_8))
		{
			for (String line = identifiers.readLine(); line != null; line = identifiers.readLine())
			{
				long pid = Long.parseLong(line);
				Map<String, String> fields = statusFields(procfs.resolve(line).resolve("status"));
				String command = processName(procfs.resolve(line), fields);
				if (command == null)
					continue;
				String state = firstToken(fields.getOrDefault("State", "?"));
				String uid = firstToken(fields.getOrDefault("Uid", "-1"));
				String process = procfs.resolve(line).toString();
				String wchan = readText(Path.of(process, "wchan")).strip();
				if (wchan.isEmpty())
					wchan = "0";
				String syscall = firstToken(readText(Path.of(process, "syscall"))).strip();
				output.write("pid=%d ppid=%s tracer=%s uid=%s state=%s wchan=%s syscall=%s fds=%s nofile=%s cmd=%s\n".
					formatted(pid, fields.getOrDefault("PPid", "?"), fields.getOrDefault("TracerPid", "?"), uid,
						state, wchan, syscall, descriptorCount(Path.of(process, "fd")),
						descriptorLimit(Path.of(process, "limits")), truncateCodePoints(command, 400)));
			}
		}
	}

	/**
	 * Performs data for writeLinuxDetails.
	 *
	 * @param processIds supplied processIds
	 * @param spool supplied spool
	 * @param jcmd supplied jcmd
	 * @param output supplied output
	 * @throws IOException if process or report data cannot be read or written
	 */
	private void writeLinuxDetails(Path processIds, QuietBuildDumpSpool spool, Path jcmd, Writer output)
		throws IOException
	{
		try (BufferedReader identifiers = Files.newBufferedReader(processIds, StandardCharsets.UTF_8))
		{
			for (String line = identifiers.readLine(); line != null; line = identifiers.readLine())
			{
				Path process = procfs.resolve(line);
				Map<String, String> fields = statusFields(process.resolve("status"));
				String command = processName(process, fields);
				if (command == null || fields.getOrDefault("State", "").startsWith("Z"))
					continue;
				boolean javaProcess = isJavaCommand(command);
				Path tasksDirectory = process.resolve("task");
				Path tasks;
				try
				{
					tasks = spool.sortedIds(tasksDirectory);
				}
				catch (IOException _)
				{
					continue;
				}
				long taskCount = countLines(tasks);
				output.write("--- pid=%s threads=%d cmd=%s\n".formatted(line, taskCount,
					truncateCodePoints(command, 200)));
				writeLinuxTasks(tasks, tasksDirectory, javaProcess, output);
				if (jcmd != null && javaProcess)
				{
					long uid = firstLong(fields.getOrDefault("Uid", "-1"), -1);
					writeJavaCommand(uid, List.of(jcmd.toString(), line, "Thread.print", "-l"), JVM_DUMP_TIMEOUT,
						output);
					Path jhsdb = jcmd.resolveSibling("jhsdb");
					if (Files.isRegularFile(jhsdb))
					{
						output.write("--- jhsdb jstack --mixed pid=%s\n".formatted(line));
						writeJavaCommand(uid, List.of(jhsdb.toString(), "jstack", "--mixed", "--pid", line),
							MIXED_DUMP_TIMEOUT, output);
					}
				}
			}
		}
	}

	/**
	 * Performs data for writeLinuxTasks.
	 *
	 * @param sortedTasks supplied sortedTasks
	 * @param tasksDirectory supplied tasksDirectory
	 * @param javaProcess supplied javaProcess
	 * @param output supplied output
	 * @throws IOException if task or report data cannot be read or written
	 */
	private void writeLinuxTasks(Path sortedTasks, Path tasksDirectory, boolean javaProcess, Writer output)
		throws IOException
	{
		try (BufferedReader identifiers = Files.newBufferedReader(sortedTasks, StandardCharsets.UTF_8))
		{
			int index = 0;
			for (String line = identifiers.readLine(); line != null; line = identifiers.readLine())
			{
				Path task = tasksDirectory.resolve(line);
				Map<String, String> fields = statusFields(task.resolve("status"));
				String state = firstToken(fields.getOrDefault("State", "?"));
				boolean beyondTaskLimit = index >= TASK_STACK_LIMIT;
				index = index + 1;
				if (beyondTaskLimit && !state.equals("D") && !javaProcess)
					continue;
				String comm = readText(task.resolve("comm")).strip();
				output.write("task=%s comm=%s state=%s tracer=%s wchan=%s\n".formatted(line, comm, state,
					fields.getOrDefault("TracerPid", "?"), readText(task.resolve("wchan")).strip()));
				output.write(readText(task.resolve("stack")));
			}
		}
	}

	/**
	 * Performs data for dumpWithoutProcfs.
	 *
	 * @param jcmd supplied jcmd
	 * @param output supplied output
	 * @throws IOException when the operation fails
	 */
	private void dumpWithoutProcfs(Path jcmd, Writer output) throws IOException
	{
		try (QuietBuildDumpSpool spool = new QuietBuildDumpSpool(context.temporaryDirectory()))
		{
			QuietBuildCapture.Captured listing;
			try
			{
				listing = capture.capture(PS_COMMAND, context, Optional.of(PS_TIMEOUT));
			}
			catch (IOException failure)
			{
				writeFailure("ps", PS_TIMEOUT, failure, output);
				return;
			}
			try (listing)
			{
				Path processList = spool.copy(listing.stdout());
				copyFile(processList, output);
				copy(listing.stderr(), output);
				writePortableDetails(processList, jcmd, output);
			}
		}
	}

	/**
	 * Performs data for writePortableDetails.
	 *
	 * @param processList supplied processList
	 * @param jcmd supplied jcmd
	 * @param output supplied output
	 * @throws IOException if process or report data cannot be read or written
	 */
	private void writePortableDetails(Path processList, Path jcmd, Writer output)
		throws IOException
	{
		Path sample = Path.of("/usr/bin/sample");
		boolean hasSample = Files.isRegularFile(sample);
		try (BufferedReader lines = Files.newBufferedReader(processList, StandardCharsets.UTF_8))
		{
			for (String line = lines.readLine(); line != null; line = lines.readLine())
			{
				PsRow row = parsePsRow(line);
				if (row == null || (!isJavaCommand(row.command()) && !row.state().startsWith("T") &&
					!hasStoppedParent(processList, row.parentPid())))
					continue;
				output.write("--- pid=%s state=%s cmd=%s\n".formatted(row.pid(), row.state(),
					truncateCodePoints(row.command(), 200)));
				if (platform == Platform.DARWIN && row.state().startsWith("T"))
					writeDarwinStop(row.pid(), output);
				if (jcmd != null && isJavaCommand(row.command()))
					writeJavaCommand(parseUid(row.uid()), List.of(jcmd.toString(), row.pid(), "Thread.print", "-l"),
						JVM_DUMP_TIMEOUT, output);
				if (hasSample)
					writeJavaCommand(0, List.of(sample.toString(), row.pid(), "3"), SAMPLE_TIMEOUT, output);
			}
		}
	}

	/**
	 * Performs data for writeDarwinStop.
	 *
	 * @param pid supplied pid
	 * @param output supplied output
	 * @throws IOException when the operation fails
	 */
	private void writeDarwinStop(String pid, Writer output) throws IOException
	{
		long processId;
		try
		{
			processId = Long.parseLong(pid);
		}
		catch (NumberFormatException _)
		{
			output.write("<stop facts failed: invalid process ID>\n");
			return;
		}
		TrackingWriter tracking = new TrackingWriter(output);
		try
		{
			nativeFacts.writeDarwinStop(processId, tracking);
		}
		catch (IOException failure)
		{
			if (tracking.failure() != null)
				throw tracking.failure();
			output.write("<stop facts failed: %s>\n".formatted(safeDescription(failure)));
		}
	}

	/**
	 * Performs data for dumpWindows.
	 *
	 * @param jcmd supplied jcmd
	 * @param output supplied output
	 * @throws IOException when the operation fails
	 */
	private void dumpWindows(Path jcmd, Writer output) throws IOException
	{
		try (QuietBuildDumpSpool spool = new QuietBuildDumpSpool(context.temporaryDirectory()))
		{
			List<String> command = List.of("powershell", "-NoProfile", "-NonInteractive", "-Command",
				WINDOWS_PROCESS_QUERY);
			QuietBuildCapture.Captured query;
			try
			{
				query = capture.capture(command, context, Optional.of(WINDOWS_QUERY_TIMEOUT));
			}
			catch (IOException failure)
			{
				writeFailure("powershell", WINDOWS_QUERY_TIMEOUT, failure, output, "process query");
				return;
			}
			try (query)
			{
				Path processJson = spool.copy(query.stdout());
				Path javaRows = Files.createTempFile(processJson.getParent(), "java-processes-", ".jsonl");
				try
				{
					visitWindowsRows(processJson, row -> writeJavaRow(row, javaRows));
				}
				catch (IOException | RuntimeException malformedJson)
				{
					writeFailure("powershell", WINDOWS_QUERY_TIMEOUT, malformedJson, output, "process query");
					return;
				}
				visitWindowsRows(processJson, row -> writeWindowsProcess(row, output));
				writeWindowsJavaRows(javaRows, jcmd, output);
			}
		}
	}

	/**
	 * Performs data for visitWindowsRows.
	 *
	 * @param processJson supplied processJson
	 * @param consumer supplied consumer
	 * @throws IOException when the operation fails
	 */
	private void visitWindowsRows(Path processJson, WindowsRowConsumer consumer) throws IOException
	{
		try (JsonParser parser = scope.getJsonMapper().createParser(Files.newBufferedReader(processJson,
			StandardCharsets.UTF_8)))
		{
			JsonToken firstToken = parser.nextToken();
			if (firstToken == JsonToken.START_OBJECT)
				consumer.accept(readWindowsRow(parser));
			else if (firstToken == JsonToken.START_ARRAY)
			{
				while (true)
				{
					JsonToken token = parser.nextToken();
					if (token == JsonToken.END_ARRAY)
						break;
					if (token != JsonToken.START_OBJECT)
						throw new IOException("Expected a Windows process object in the query array");
					consumer.accept(readWindowsRow(parser));
				}
			}
			else
				throw new IOException("Expected a Windows process object or array");
			if (parser.nextToken() != null)
				throw new IOException("Unexpected trailing content after Windows process JSON");
		}
	}

	/**
	 * Performs data for readWindowsRow.
	 *
	 * @param parser supplied parser
	  *
	  * @return the requested result
	 * @throws IOException when the operation fails
	 */
	private WindowsRow readWindowsRow(JsonParser parser) throws IOException
	{
		String pid = null;
		String parentPid = null;
		String threads = null;
		String suspended = null;
		String name = null;
		String command = null;
		while (parser.nextToken() != JsonToken.END_OBJECT)
		{
			String field = parser.currentName();
			JsonToken value = parser.nextToken();
			switch (field)
			{
				case "pid" -> pid = requiredNumber(parser, value, field);
				case "ppid" -> parentPid = requiredNumber(parser, value, field);
				case "threads" -> threads = requiredNumber(parser, value, field);
				case "suspended" -> suspended = requiredNumber(parser, value, field);
				case "name" -> name = nullableText(parser, value, field);
				case "command" -> command = nullableText(parser, value, field);
				default -> parser.skipChildren();
			}
		}
		if (pid == null || parentPid == null || threads == null || suspended == null)
			throw new IOException("Windows process query is missing a required numeric field");
		return new WindowsRow(pid, parentPid, threads, suspended, name, command);
	}

	/**
	 * Validates and reads data for requiredNumber.
	 *
	 * @param parser supplied parser
	 * @param token supplied token
	 * @param field supplied field
	  *
	  * @return the requested result
	 * @throws IOException when the operation fails
	 */
	private String requiredNumber(JsonParser parser, JsonToken token, String field) throws IOException
	{
		if (token != JsonToken.VALUE_NUMBER_INT && token != JsonToken.VALUE_NUMBER_FLOAT)
			throw new IOException("Windows process field " + field + " must be numeric");
		return parser.getText();
	}

	/**
	 * Reads data for nullableText.
	 *
	 * @param parser supplied parser
	 * @param token supplied token
	 * @param field supplied field
	  *
	  * @return the requested result
	 * @throws IOException when the operation fails
	 */
	private String nullableText(JsonParser parser, JsonToken token, String field) throws IOException
	{
		if (token == JsonToken.VALUE_NULL)
			return null;
		if (token != JsonToken.VALUE_STRING)
			throw new IOException("Windows process field " + field + " must be text or null");
		return parser.getValueAsString();
	}

	/**
	 * Performs data for writeJavaRow.
	 *
	 * @param row supplied row
	 * @param javaRows supplied javaRows
	 * @throws IOException when the operation fails
	 */
	private void writeJavaRow(WindowsRow row, Path javaRows) throws IOException
	{
		if (!"java.exe".equalsIgnoreCase(row.name()))
			return;
		long pid;
		try
		{
			pid = Long.parseLong(row.pid());
		}
		catch (NumberFormatException invalidPid)
		{
			throw new IOException("Windows Java process ID is outside the supported range", invalidPid);
		}
		JsonNode internalRow = scope.getJsonMapper().createObjectNode().put("format_version", 1).
			put("pid", pid).put("name", row.name());
		try (BufferedWriter writer = Files.newBufferedWriter(javaRows, StandardCharsets.UTF_8,
			java.nio.file.StandardOpenOption.APPEND))
		{
			writer.write(scope.getJsonMapper().writeValueAsString(internalRow));
			writer.newLine();
		}
	}

	/**
	 * Performs data for writeWindowsProcess.
	 *
	 * @param row supplied row
	 * @param output supplied output
	 * @throws IOException when the operation fails
	 */
	private void writeWindowsProcess(WindowsRow row, Writer output) throws IOException
	{
		boolean held = new BigDecimal(row.threads()).signum() > 0 &&
			new BigDecimal(row.threads()).compareTo(new BigDecimal(row.suspended())) == 0;
		String command = row.command();
		if (command == null || command.isEmpty())
			command = row.name();
		if (command == null || command.isEmpty())
			command = "?";
		command = command.replace('\r', ' ').replace('\n', ' ');
		String heldText = "no";
		if (held)
			heldText = "yes";
		output.write("pid=%s ppid=%s threads=%s suspended=%s held=%s cmd=%s\n".formatted(row.pid(),
			row.parentPid(), row.threads(), row.suspended(), heldText, truncateCodePoints(command, 400)));
	}

	/**
	 * Performs data for writeWindowsJavaRows.
	 *
	 * @param javaRows supplied javaRows
	 * @param jcmd supplied jcmd
	 * @param output supplied output
	 * @throws IOException when the operation fails
	 */
	private void writeWindowsJavaRows(Path javaRows, Path jcmd, Writer output) throws IOException
	{
		try (JsonParser parser = scope.getJsonMapper().createParser(Files.newBufferedReader(javaRows,
			StandardCharsets.UTF_8)))
		{
			while (parser.nextToken() != null)
			{
				if (parser.currentToken() != JsonToken.START_OBJECT)
					throw new IOException("Internal Windows Java process record must be an object");
				JavaRow row = readJavaRow(parser);
				output.write("--- pid=%d %s\n".formatted(row.pid(), row.name()));
				if (jcmd == null)
					output.write("<no jcmd>\n");
				else
					writeJavaCommand(0, List.of(jcmd.toString(), Long.toString(row.pid()), "Thread.print", "-l"),
						JVM_DUMP_TIMEOUT, output);
			}
		}
	}

	/**
	 * Performs data for readJavaRow.
	 *
	 * @param parser supplied parser
	  *
	  * @return the requested result
	 * @throws IOException when the operation fails
	 */
	private JavaRow readJavaRow(JsonParser parser) throws IOException
	{
		Long pid = null;
		Integer formatVersion = null;
		String name = null;
		while (parser.nextToken() != JsonToken.END_OBJECT)
		{
			String field = parser.currentName();
			JsonToken token = parser.nextToken();
			switch (field)
			{
				case "format_version" -> formatVersion = integerField(parser, token, field).intValueExact();
				case "pid" -> pid = integerField(parser, token, field).longValueExact();
				case "name" -> name = nullableText(parser, token, field);
				default -> throw new IOException("Unknown internal Windows Java process field " + field);
			}
		}
		if (formatVersion == null || formatVersion != 1 || pid == null || name == null)
			throw new IOException("Internal Windows Java process record is missing a required field");
		return new JavaRow(pid, name);
	}

	/**
	 * Reads data for integerField.
	 *
	 * @param parser supplied parser
	 * @param token supplied token
	 * @param field supplied field
	  *
	  * @return the requested result
	 * @throws IOException when the operation fails
	 */
	private BigDecimal integerField(JsonParser parser, JsonToken token, String field) throws IOException
	{
		if (token != JsonToken.VALUE_NUMBER_INT)
			throw new IOException("Internal Windows Java process field " + field + " must be an integer");
		return new BigDecimal(parser.getText());
	}

	/**
	 * Performs data for writeJavaCommand.
	 *
	 * @param uid supplied uid
	 * @param command supplied command
	 * @param timeout supplied timeout
	 * @param output supplied output
	 * @throws IOException when the operation fails
	 */
	private void writeJavaCommand(long uid, List<String> command, Duration timeout, Writer output) throws IOException
	{
		long effectiveUid = nativeFacts.effectiveUid();
		new QuietBuildJavaDump(capture, context, platform, effectiveUid).write(uid, command, timeout, output);
	}

	/**
	 * Performs data for writeFailure.
	 *
	 * @param executable supplied executable
	 * @param timeout supplied timeout
	 * @param failure supplied failure
	 * @param output supplied output
	 * @throws IOException if diagnostic failure text cannot be written
	 */
	private static void writeFailure(String executable, Duration timeout, IOException failure, Writer output)
		throws IOException
	{
		writeFailure(executable, timeout, failure, output, null);
	}

	/**
	 * Performs data for writeFailure.
	 *
	 * @param executable supplied executable
	 * @param timeout supplied timeout
	 * @param failure supplied failure
	 * @param output supplied output
	 * @param operation supplied operation
	 * @throws IOException when the operation fails
	 */
	private static void writeFailure(String executable, Duration timeout, Exception failure, Writer output,
		String operation) throws IOException
	{
		String detail;
		if (failure instanceof QuietBuildProcessAccess.CaptureTimeoutException)
			detail = "timed out after " + timeout.toSeconds() + " seconds";
		else
			detail = safeDescription(failure);
		String commandName = executable;
		if (operation != null)
			output.write("<" + operation + " failed: " + detail + ">\n");
		else
			output.write("<" + commandName + " failed: " + detail + ">\n");
	}

	/**
	 * Returns data for safeDescription.
	 *
	 * @param failure supplied failure
	  *
	  * @return the requested result
	 */
	private static String safeDescription(Exception failure)
	{
		String description = failure.getMessage();
		if (description == null || description.isBlank())
			description = failure.getClass().getSimpleName();
		return description.replace('\r', ' ').replace('\n', ' ');
	}

	/**
	 * Performs data for copyFile.
	 *
	 * @param file supplied file
	 * @param output supplied output
	 * @throws IOException when the operation fails
	 */
	private static void copyFile(Path file, Writer output) throws IOException
	{
		try (Reader input = utf8Reader(file))
		{
			copy(input, output);
		}
	}

	/**
	 * Copies data for copy.
	 *
	 * @param input supplied input
	 * @param output supplied output
	 * @throws IOException when the operation fails
	 */
	private static void copy(Reader input, Writer output) throws IOException
	{
		char[] buffer = new char[COPY_BUFFER_SIZE];
		for (int count = input.read(buffer); count != -1; count = input.read(buffer))
		{
			output.write(buffer, 0, count);
		}
	}

	/**
	 * Performs data for utf8Reader.
	 *
	 * @param path supplied path
	  *
	  * @return the requested result
	 * @throws IOException when the operation fails
	 */
	private static Reader utf8Reader(Path path) throws IOException
	{
		return new BufferedReader(new InputStreamReader(Files.newInputStream(path), StandardCharsets.UTF_8.newDecoder().
			onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)));
	}

	/**
	 * Performs data for statusFields.
	 *
	 * @param statusPath supplied statusPath
	  *
	  * @return the requested result
	 */
	private static Map<String, String> statusFields(Path statusPath)
	{
		Map<String, String> fields = new HashMap<>();
		try (BufferedReader lines = new BufferedReader(new StringReader(readText(statusPath))))
		{
			for (String line = lines.readLine(); line != null; line = lines.readLine())
			{
				int separator = line.indexOf(':');
				if (separator < 0)
					continue;
				String name = line.substring(0, separator);
				if (name.equals("State") || name.equals("PPid") || name.equals("TracerPid") || name.equals("Uid"))
					fields.put(name, line.substring(separator + 1).strip());
			}
		}
		catch (IOException impossibleStringReaderFailure)
		{
			throw new AssertionError(impossibleStringReaderFailure);
		}
		return fields;
	}

	/**
	 * Performs data for processName.
	 *
	 * @param process supplied process
	 * @param fields supplied fields
	  *
	  * @return the requested result
	 */
	private static String processName(Path process, Map<String, String> fields)
	{
		String command = readText(process.resolve("cmdline")).replace('\0', ' ').strip();
		if (!command.isEmpty() && !command.startsWith("<"))
			return command;
		if (fields.getOrDefault("State", "").startsWith("Z"))
			return "[%s] <defunct>".formatted(readText(process.resolve("comm")).strip());
		return null;
	}

	/**
	 * Performs data for readText.
	 *
	 * @param path supplied path
	  *
	  * @return the requested result
	 */
	private static String readText(Path path)
	{
		try (Reader reader = utf8Reader(path))
		{
			StringBuilder text = new StringBuilder();
			char[] buffer = new char[COPY_BUFFER_SIZE];
			for (int count = reader.read(buffer); count != -1; count = reader.read(buffer))
			{
				text.append(buffer, 0, count);
			}
			return text.toString();
		}
		catch (IOException failure)
		{
			return "<" + readFailure(failure) + ">";
		}
	}

	/**
	 * Performs data for readFailure.
	 *
	 * @param failure supplied failure
	  *
	  * @return the requested result
	 */
	private static String readFailure(IOException failure)
	{
		if (failure instanceof AccessDeniedException)
			return "Permission denied";
		if (failure instanceof NoSuchFileException)
			return "No such file or directory";
		if (failure instanceof FileSystemException fileFailure && fileFailure.getReason() != null)
			return fileFailure.getReason();
		return safeDescription(failure);
	}

	/**
	 * Counts data for descriptorCount.
	 *
	 * @param descriptors supplied descriptors
	  *
	  * @return the requested result
	 */
	private static String descriptorCount(Path descriptors)
	{
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(descriptors))
		{
			long count = 0;
			for (Path ignored : entries)
				count = count + 1;
			return Long.toString(count);
		}
		catch (IOException | SecurityException _)
		{
			return "?";
		}
	}

	/**
	 * Reads data for descriptorLimit.
	 *
	 * @param limits supplied limits
	  *
	  * @return the requested result
	 */
	private static String descriptorLimit(Path limits)
	{
		try (BufferedReader lines = new BufferedReader(new StringReader(readText(limits))))
		{
			for (String line = lines.readLine(); line != null; line = lines.readLine())
			{
				if (line.startsWith("Max open files"))
				{
					String[] fields = line.strip().split("\\s+");
					if (fields.length > 3)
						return fields[3];
				}
			}
		}
		catch (IOException impossibleStringReaderFailure)
		{
			throw new AssertionError(impossibleStringReaderFailure);
		}
		return "?";
	}

	/**
	 * Returns data for firstToken.
	 *
	 * @param text supplied text
	  *
	  * @return the requested result
	 */
	private static String firstToken(String text)
	{
		int separator = 0;
		while (separator < text.length() && !Character.isWhitespace(text.charAt(separator)))
			separator = separator + 1;
		return text.substring(0, separator);
	}

	/**
	 * Parses data for firstLong.
	 *
	 * @param text supplied text
	 * @param fallback supplied fallback
	  *
	  * @return the requested result
	 */
	private static long firstLong(String text, long fallback)
	{
		String first = firstToken(text);
		try
		{
			return Long.parseLong(first);
		}
		catch (NumberFormatException _)
		{
			return fallback;
		}
	}

	/**
	 * Identifies data for isJavaCommand.
	 *
	 * @param command supplied command
	  *
	  * @return the requested result
	 */
	private static boolean isJavaCommand(String command)
	{
		String first = firstToken(command);
		int separator = first.lastIndexOf('/');
		return first.substring(separator + 1).equals("java");
	}

	/**
	 * Parses data for parseUid.
	 *
	 * @param uid supplied uid
	  *
	  * @return the requested result
	 */
	private static long parseUid(String uid)
	{
		try
		{
			return Long.parseLong(uid);
		}
		catch (NumberFormatException _)
		{
			return -1;
		}
	}

	/**
	 * Truncates data for truncateCodePoints.
	 *
	 * @param value supplied value
	 * @param maximum supplied maximum
	  *
	  * @return the requested result
	 */
	private static String truncateCodePoints(String value, int maximum)
	{
		int codePointCount = value.codePointCount(0, value.length());
		if (codePointCount <= maximum)
			return value;
		return value.substring(0, value.offsetByCodePoints(0, maximum));
	}

	/**
	 * Counts data for countLines.
	 *
	 * @param file supplied file
	  *
	  * @return the requested result
	 * @throws IOException when the operation fails
	 */
	private static long countLines(Path file) throws IOException
	{
		try (BufferedReader lines = Files.newBufferedReader(file, StandardCharsets.UTF_8))
		{
			long count = 0;
			while (lines.readLine() != null)
				count = count + 1;
			return count;
		}
	}

	/**
	 * Performs data for parsePsRow.
	 *
	 * @param line supplied line
	  *
	  * @return the requested result
	 */
	private static PsRow parsePsRow(String line)
	{
		String text = line.stripLeading();
		List<String> fields = new ArrayList<>(9);
		int position = 0;
		for (int fieldIndex = 0; fieldIndex < 8; fieldIndex = fieldIndex + 1)
		{
			while (position < text.length() && Character.isWhitespace(text.charAt(position)))
				position = position + 1;
			int start = position;
			while (position < text.length() && !Character.isWhitespace(text.charAt(position)))
				position = position + 1;
			if (start == position)
				return null;
			fields.add(text.substring(start, position));
		}
		while (position < text.length() && Character.isWhitespace(text.charAt(position)))
			position = position + 1;
		if (position == text.length())
			return null;
		fields.add(text.substring(position).stripTrailing());
		return new PsRow(fields.get(0), fields.get(1), fields.get(2), fields.get(3), fields.get(8));
	}

	/**
	 * Checks data for hasStoppedParent.
	 *
	 * @param processList supplied processList
	 * @param parentPid supplied parentPid
	  *
	  * @return the requested result
	 * @throws IOException when the operation fails
	 */
	private static boolean hasStoppedParent(Path processList, String parentPid) throws IOException
	{
		try (BufferedReader lines = Files.newBufferedReader(processList, StandardCharsets.UTF_8))
		{
			for (String line = lines.readLine(); line != null; line = lines.readLine())
			{
				PsRow candidate = parsePsRow(line);
				if (candidate != null && candidate.pid().equals(parentPid) && candidate.state().startsWith("T"))
					return true;
			}
		}
		return false;
	}

	/**
	 * Portable process-list fields used to select diagnostic targets.
	 *
	 * @param pid process identifier
	 * @param parentPid parent process identifier
	 * @param uid owner user identifier
	 * @param state process state
	 * @param command command line
	 */
	private record PsRow(String pid, String parentPid, String uid, String state, String command)
	{
	}

	/**
	 * Process fields decoded from one Windows query object.
	 *
	 * @param pid process identifier
	 * @param parentPid parent process identifier
	 * @param threads thread count
	 * @param suspended suspended thread count
	 * @param name executable name
	 * @param command command line
	 */
	private record WindowsRow(String pid, String parentPid, String threads, String suspended, String name,
		String command)
	{
	}

	/**
	 * JVM process identity selected for a thread dump.
	 *
	 * @param pid process identifier
	 * @param name executable name
	 */
	private record JavaRow(long pid, String name)
	{
	}

	/**
	 * Receives each decoded Windows process row.
	 */
	@FunctionalInterface
	private interface WindowsRowConsumer
	{
	/**
	 * Accepts data for accept.
	 *
	 * @param row supplied row
	 * @throws IOException when the operation fails
	 */
		void accept(WindowsRow row) throws IOException;
	}

	/**
	 * Tracks report output failures while preserving the caller-owned writer.
	 */
	private static final class TrackingWriter extends Writer
	{
		/**
	 * Caller-owned report writer wrapped by this tracker.
	 */
	private final Writer delegate;
		/**
	 * First report output failure observed by this tracker.
	 */
	private IOException failure;

	/**
	 * Performs data for TrackingWriter.
	 *
	 * @param delegate supplied delegate
	 */
		private TrackingWriter(Writer delegate)
		{
			this.delegate = delegate;
		}

	/**
	 * Returns data for failure.
	  *
	  * @return the requested result
	 */
		private IOException failure()
		{
			return failure;
		}

		@Override
		public void write(char[] buffer, int offset, int length) throws IOException
		{
			try
			{
				delegate.write(buffer, offset, length);
			}
			catch (IOException writeFailure)
			{
				failure = writeFailure;
				throw writeFailure;
			}
		}

		@Override
		public void flush() throws IOException
		{
			try
			{
				delegate.flush();
			}
			catch (IOException writeFailure)
			{
				failure = writeFailure;
				throw writeFailure;
			}
		}

		@Override
		public void close()
		{
		}
	}
}
