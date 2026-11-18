package com.holdmyspot.codexunleashed.tooling;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/** Collects nextest and Bazel test reports without invoking external tools. */
public final class TestReports
{
	/** Selects the report conventions used by a runner. */
	public enum Runner
	{
		/** nextest's JUnit XML and suite streams. */
		NEXTEST,
		/** Bazel test XML, logs, and target labels. */
		BAZEL
	}

	/**
	 * A stream captured from a testcase or suite for skip analysis.
	 *
	 * @param source report file containing this stream
	 * @param test testcase or suite associated with the stream
	 * @param text captured stream content
	 */
	public record Stream(Path source, String test, String text)
	{
	}

	/**
	 * Immutable collection of discovered report files and their test outcomes.
	 *
	 * @param files discovered report files
	 * @param executed executed test IDs
	 * @param failed failed test or target IDs
	 * @param timedOut timed out test or target IDs
	 * @param streams captured testcase and suite output
	 */
	public record Report(List<Path> files, Set<String> executed, Set<String> failed, Set<String> timedOut,
		List<Stream> streams)
	{
		/**
		 * Freezes collection values at the report boundary.
		 *
		 * @param files discovered report files
		 * @param executed executed test IDs
		 * @param failed failed test or target IDs
		 * @param timedOut timed out test or target IDs
		 * @param streams captured testcase and suite output
		 */
		public Report
		{
			files = List.copyOf(files);
			executed = Set.copyOf(executed);
			failed = Set.copyOf(failed);
			timedOut = Set.copyOf(timedOut);
			streams = List.copyOf(streams);
		}

		/**
		 * Returns failures and timeouts as one verdict set.
		 *
		 * @return immutable union of failed and timed-out IDs
		 */
		public Set<String> failing()
		{
			Set<String> result = new LinkedHashSet<>(failed);
			result.addAll(timedOut);
			return Set.copyOf(result);
		}
	}

	/** Removes terminal color escapes from runner output. */
	private static final Pattern ANSI_ESCAPE = Pattern.compile("[\\x1b\\ufffd?]\\[[0-9;]*[A-Za-z]");
	/** Captures an exact libtest failure line. */
	private static final Pattern LIBTEST_FAILED = Pattern.compile("^test (\\S+) \\.\\.\\. FAILED\\s*$");
	/** Recognizes timeout descriptions in a failure type. */
	private static final Pattern TIMEOUT = Pattern.compile("time(?:d)?[ -]?out", Pattern.CASE_INSENSITIVE);
	/** Report basenames searched in each supplied directory. */
	private static final List<String> REPORT_NAMES = List.of("junit.xml", "test.xml", "test.log");

	/** Prevents construction. */
	private TestReports()
	{
	}

	/**
	 * Finds and parses report files in the supplied paths.
	 *
	 * @param paths explicit reports or directories to search
	 * @param runner report format
	 * @return collected reports in deterministic discovery order
	 * @throws IOException if an input is absent, unreadable, or malformed
	 */
	public static Report collect(List<Path> paths, Runner runner) throws IOException
	{
		Objects.requireNonNull(paths, "paths");
		Objects.requireNonNull(runner, "runner");
		List<Path> files = discover(paths);
		Set<String> executed = new LinkedHashSet<>();
		Set<String> failed = new LinkedHashSet<>();
		Set<String> timedOut = new LinkedHashSet<>();
		List<Stream> streams = new ArrayList<>();
		for (Path path : files)
		{
			if (runner == Runner.BAZEL)
			{
				String label = bazelLabel(path);
				String target = label;
				if (target == null)
					target = path.getParent().toString();
				if (path.toString().endsWith(".xml"))
					parseXml(path, target, true, executed, failed,
						timedOut, streams);
				else
					scanBazelLog(path, target, failed);
			}
			else if (path.toString().endsWith(".xml"))
				parseXml(path, null, false, executed, failed, timedOut, streams);
		}
		return new Report(files, executed, failed, timedOut, streams);
	}

	/**
	 * Discovers files with the same directory search order as the Python gates.
	 *
	 * @param paths report paths or directories to search
	 * @return discovered files in deterministic order
	 * @throws IOException if a path does not exist or cannot be read
	 */
	private static List<Path> discover(List<Path> paths) throws IOException
	{
		List<Path> result = new ArrayList<>();
		for (Path path : paths)
		{
			if (Files.isDirectory(path))
			{
				for (String name : REPORT_NAMES)
					try (java.util.stream.Stream<Path> found = Files.find(path, Integer.MAX_VALUE,
						(candidate, attributes) -> attributes.isRegularFile() && candidate.getFileName().toString().equals(name)))
					{
						result.addAll(found.sorted(Comparator.comparing(Path::toString)).toList());
					}
			}
			else if (Files.isRegularFile(path))
				result.add(path);
			else
				throw new IOException("No such file or directory: " + path);
		}
		return result;
	}

	/**
	 * Derives a Bazel label from the last testlogs root and removes trailing shard/run/attempt segments.
	 *
	 * @param path report path below a Bazel testlogs directory
	 * @return Bazel label, or {@code null} when no label can be derived
	 */
	static String bazelLabel(Path path)
	{
		List<String> parts = new ArrayList<>();
		for (Path part : path.getParent())
			parts.add(part.toString());
		int root = -1;
		for (int index = parts.size() - 1; index >= 0; --index)
			if (parts.get(index).equals("testlogs") || parts.get(index).equals("bazel-testlogs"))
			{
				root = index;
				break;
			}
		if (root < 0)
			return null;
		parts = new ArrayList<>(parts.subList(root + 1, parts.size()));
		while (!parts.isEmpty() && parts.getLast().matches("shard_[0-9]+_of_[0-9]+|run_[0-9]+_of_[0-9]+|attempt_[0-9]+"))
			parts.removeLast();
		if (parts.size() < 2)
			return null;
		return "//" + String.join("/", parts.subList(0, parts.size() - 1)) + ":" + parts.getLast();
	}

	/**
	 * Parses one XML report using a SAX reader with external document access disabled.
	 *
	 * @param path report file
	 * @param label Bazel target label, or {@code null} for nextest
	 * @param bazel whether Bazel target semantics apply
	 * @param executed destination for executed IDs
	 * @param failed destination for failure IDs
	 * @param timedOut destination for timeout IDs
	 * @param streams destination for captured output streams
	 * @throws IOException if XML is malformed or cannot be read
	 */
	private static void parseXml(Path path, String label, boolean bazel, Set<String> executed, Set<String> failed,
		Set<String> timedOut, List<Stream> streams) throws IOException
	{
		try
		{
			SAXParserFactory factory = SAXParserFactory.newInstance();
			factory.setNamespaceAware(true);
			factory.setXIncludeAware(false);
			factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			XMLReader reader = factory.newSAXParser().getXMLReader();
			reader.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			reader.setFeature("http://xml.org/sax/features/external-general-entities", false);
			reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
			reader.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
			reader.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
			reader.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
			ReportHandler handler = new ReportHandler(path, label, bazel, executed, failed, timedOut, streams);
			reader.setContentHandler(handler);
			reader.setErrorHandler(handler);
			try (InputStream input = Files.newInputStream(path))
			{
				reader.parse(new InputSource(input));
			}
		}
		catch (Exception exception)
		{
			throw new IOException("Cannot parse " + path + ": " + exception.getMessage(), exception);
		}
	}

	/** Records testcase outcomes and suite/testcase streams as SAX events arrive. */
	private static final class ReportHandler extends DefaultHandler
	{
		/** XML report currently being parsed. */
		private final Path path;
		/** Bazel label used for target-level IDs. */
		private final String label;
		/** Whether Bazel target identity rules apply. */
		private final boolean bazel;
		/** Destination for executed IDs. */
		private final Set<String> executed;
		/** Destination for failure IDs. */
		private final Set<String> failed;
		/** Destination for timeout IDs. */
		private final Set<String> timedOut;
		/** Destination for captured streams. */
		private final List<Stream> streams;
		/** Names of open suites. */
		private final List<String> suiteNames = new ArrayList<>();
		/** Depths of open suites. */
		private final List<Integer> suiteDepths = new ArrayList<>();
		/**
		 * Depth of the current testcase, or {@code -1} outside one.
		 */
		private int caseDepth = -1;
		/**
		 * Depth of the current stream element, or {@code -1} outside one.
		 */
		private int streamDepth = -1;
		/** ID of the current testcase. */
		private String caseId;
		/** Stream-facing ID of the current testcase. */
		private String streamCaseId;
		/** ID associated with the current stream. */
		private String streamId;
		/** Type attribute of the first failure or error child. */
		private String failureType;
		/** Whether the current testcase was skipped. */
		private boolean skipped;
		/** Whether the current testcase has a failure or error child. */
		private boolean caseFailed;
		/** Characters captured from the active output element. */
		private final StringBuilder text = new StringBuilder();

		/**
		 * Creates a handler that appends SAX events to the supplied result collections.
		 *
		 * @param path report file being parsed
		 * @param label Bazel label, or {@code null} for nextest
		 * @param bazel whether Bazel identity rules apply
		 * @param executed destination for executed IDs
		 * @param failed destination for failure IDs
		 * @param timedOut destination for timeout IDs
		 * @param streams destination for captured streams
		 */
		private ReportHandler(Path path, String label, boolean bazel, Set<String> executed, Set<String> failed,
			Set<String> timedOut, List<Stream> streams)
		{
			this.path = path; this.label = label; this.bazel = bazel; this.executed = executed;
			this.failed = failed; this.timedOut = timedOut; this.streams = streams;
		}

		/**
		 * Processes suite, testcase, failure, skip, and stream start events.
		 *
		 * @param uri SAX namespace URI
		 * @param localName local element name
		 * @param qName qualified element name
		 * @param attributes element attributes
		 */
		@Override public void startElement(String uri, String localName, String qName, Attributes attributes)
		{
			String name = localName;
			if (name.isEmpty())
				name = qName;
			if (name.equals("testsuite"))
			{
				suiteDepths.add(depth);
				suiteNames.add(attributes.getValue("name"));
			}
			if (name.equals("testcase"))
			{
				caseDepth = depth;
				String testName = attributes.getValue("name");
				String className = attributes.getValue("classname");
			String classPrefix = className;
			if (classPrefix == null)
				classPrefix = "";
			String nameSuffix = testName;
			if (nameSuffix == null)
				nameSuffix = "";
			if (bazel)
				caseId = label;
			else
				caseId = classPrefix + " " + nameSuffix;
			caseId = caseId.trim();
			streamCaseId = (classPrefix + "::" + nameSuffix).replaceAll("^:+|:+$", "");
				failureType = null;
				skipped = false;
				caseFailed = false;
			}
			if (caseDepth >= 0 && depth == caseDepth + 1)
			{
				if ((name.equals("failure") || name.equals("error")) && !caseFailed)
				{
					caseFailed = true;
					failureType = attributes.getValue("type");
				}
				if (name.equals("skipped")) skipped = true;
			}
		boolean testcaseStream = caseDepth >= 0 && depth == caseDepth + 1;
		boolean suiteStream = caseDepth < 0 && !suiteDepths.isEmpty() &&
			depth == suiteDepths.getLast() + 1;
		if ((name.equals("system-out") || name.equals("system-err")) && (testcaseStream || suiteStream))
		{
			streamDepth = depth;
			if (caseDepth >= 0)
				streamId = streamCaseId;
			else if (!suiteNames.isEmpty() && suiteNames.getLast() != null)
				streamId = suiteNames.getLast();
			else
				streamId = "";
			text.setLength(0);
			}
			++depth;
		}
		/** Current SAX element depth. */
		private int depth;

		/**
		 * Appends characters belonging to an output stream.
		 *
		 * @param chars input character buffer
		 * @param start first character offset
		 * @param length number of characters to append
		 */
		@Override public void characters(char[] chars, int start, int length)
		{
		if (streamDepth >= 0)
			text.append(chars, start, length);
		}

		/**
		 * Finalizes testcase and stream state at closing elements.
		 *
		 * @param uri SAX namespace URI
		 * @param localName local element name
		 * @param qName qualified element name
		 */
		@Override public void endElement(String uri, String localName, String qName)
		{
			--depth;
			String name = localName;
			if (name.isEmpty())
				name = qName;
			if (depth == streamDepth && (name.equals("system-out") || name.equals("system-err")))
			{
			String test = streamId;
			if (test == null)
				test = "";
			streams.add(new Stream(path, test, text.toString()));
				streamDepth = -1;
			}
			if (depth == caseDepth && name.equals("testcase"))
			{
				if (!skipped)
				{
					executed.add(caseId);
				if (caseFailed)
				{
					if (failureType != null && TIMEOUT.matcher(failureType).find())
						timedOut.add(caseId);
					else
						failed.add(caseId);
				}
				}
				caseDepth = -1;
			}
			if (!suiteDepths.isEmpty() && depth == suiteDepths.getLast() && name.equals("testsuite"))
			{
				suiteDepths.removeLast();
				if (!suiteNames.isEmpty()) suiteNames.removeLast();
			}
		}

		/**
		 * Rethrows a fatal XML parse error to the report parser.
		 *
		 * @param exception fatal SAX parse error
		 * @throws SAXParseException the supplied parse error
		 */
		@Override public void fatalError(SAXParseException exception) throws SAXParseException
		{
			throw exception;
		}
	}

	/**
	 * Adds exact ANSI-stripped libtest failure records from a Bazel log.
	 *
	 * @param path Bazel test log
	 * @param label Bazel target label
	 * @param failed destination for failed case IDs
	 * @throws IOException if the log cannot be read
	 */
	private static void scanBazelLog(Path path, String label, Set<String> failed) throws IOException
	{
		try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8))
		{
			for (String line = reader.readLine(); line != null; line = reader.readLine())
			{
				Matcher match = LIBTEST_FAILED.matcher(ANSI_ESCAPE.matcher(line).replaceAll("").strip());
				if (match.matches()) failed.add(label + " " + match.group(1));
			}
		}
	}
}
