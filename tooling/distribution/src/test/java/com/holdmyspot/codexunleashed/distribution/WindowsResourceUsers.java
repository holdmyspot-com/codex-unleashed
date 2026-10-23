package com.holdmyspot.codexunleashed.distribution;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleProxies;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Observes current exact-file users through Windows Restart Manager without stopping them.
 * Public visibility permits the public native-call interfaces required by {@link MethodHandleProxies}.
 */
public final class WindowsResourceUsers
{
	// Microsoft RestartManager.h defines DWORD/FILETIME, WCHAR[256], WCHAR[64], then four 32-bit fields.
	// https://learn.microsoft.com/en-us/windows/win32/api/restartmanager/ns-restartmanager-rm_process_info
	private static final MemoryLayout FILETIME = MemoryLayout.structLayout(
		ValueLayout.JAVA_INT.withName("low"), ValueLayout.JAVA_INT.withName("high"));
	private static final MemoryLayout UNIQUE_PROCESS = MemoryLayout.structLayout(
		ValueLayout.JAVA_INT.withName("pid"), FILETIME.withName("creation"));
	private static final MemoryLayout PROCESS_INFO = MemoryLayout.structLayout(
		UNIQUE_PROCESS.withName("process"), MemoryLayout.sequenceLayout(256, ValueLayout.JAVA_CHAR).withName("app"),
		MemoryLayout.sequenceLayout(64, ValueLayout.JAVA_CHAR).withName("service"),
		ValueLayout.JAVA_INT.withName("type"), ValueLayout.JAVA_INT.withName("status"),
		ValueLayout.JAVA_INT.withName("session"), ValueLayout.JAVA_INT.withName("restartable"));
	private static final int ERROR_MORE_DATA = 234;

	/**
	 * Prevents construction.
	 */
	private WindowsResourceUsers()
	{
	}

	/**
	 * Observes one exact absolute file on Windows; other systems and failed queries retain unknown ownership.
	 *
	 * @param file the failed deletion's file, never its parent directory
	 * @return the interval, API statuses, current users and explicit inspection failures
	 */
	static Observation observe(Path file)
	{
		file = file.toAbsolutePath();
		Instant started = Instant.now();
		List<String> calls = new ArrayList<>();
		List<User> users = new ArrayList<>();
		List<IOException> errors = new ArrayList<>();
		if (System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows"))
		{
			try (Arena arena = Arena.ofConfined())
			{
				Binding binding = new Binding(arena);
				MemorySegment session = arena.allocate(ValueLayout.JAVA_INT);
				// CCH_RM_SESSION_KEY is sizeof(GUID) * 2; the API also writes a terminating WCHAR.
				MemorySegment key = arena.allocate(MemoryLayout.sequenceLayout(33, ValueLayout.JAVA_CHAR));
				int status = binding.start.invoke(session, 0, key);
				if (recordStatus("RmStartSession", status, calls, errors))
				{
					int handle = session.get(ValueLayout.JAVA_INT, 0);
					try
					{
						MemorySegment filename = arena.allocateFrom(ValueLayout.JAVA_CHAR, (file + "\0").toCharArray());
						MemorySegment filenames = arena.allocate(ValueLayout.ADDRESS);
						filenames.set(ValueLayout.ADDRESS, 0, filename);
						status = binding.register.invoke(handle, 1, filenames, 0, MemorySegment.NULL, 0, MemorySegment.NULL);
						if (recordStatus("RmRegisterResources", status, calls, errors))
							readUsers(binding, handle, arena, calls, users, errors);
					}
					catch (RuntimeException | UnsatisfiedLinkError failure)
					{
						errors.add(new IOException("Restart Manager resource query failed for " + file, failure));
					}
					finally
					{
						recordStatus("RmEndSession", binding.end.invoke(handle), calls, errors);
					}
				}
			}
			catch (RuntimeException | UnsatisfiedLinkError failure)
			{
				errors.add(new IOException("Restart Manager observation failed for " + file, failure));
			}
		}
		else
			errors.add(new IOException("Restart Manager requires Windows; ownership is unknown"));
		return new Observation(file, started, Instant.now(), List.copyOf(calls), List.copyOf(users), List.copyOf(errors));
	}

	/**
	 * Records a native return status; success never implies that the file is unlocked.
	 *
	 * @param operation the API call
	 * @param status    the returned DWORD bit pattern
	 * @param calls     the ordered API observations
	 * @param errors    the failed API observations
	 * @return whether the API succeeded
	 */
	private static boolean recordStatus(String operation, int status, List<String> calls, List<IOException> errors)
	{
		String observation = operation + " status=" + Integer.toUnsignedString(status);
		calls.add(observation);
		if (status == 0)
			return true;
		errors.add(new IOException(observation));
		return false;
	}

	/**
	 * Negotiates and reads one process buffer, retaining a changing-size response as an explicit incomplete query.
	 *
	 * @param binding the arena-owned native functions
	 * @param handle  the owned Restart Manager session
	 * @param arena   the allocation lifetime
	 * @param calls   the ordered API observations
	 * @param users   the returned exact-resource users
	 * @param errors  the failed inspections
	 */
	private static void readUsers(Binding binding, int handle, Arena arena, List<String> calls, List<User> users,
		List<IOException> errors)
	{
		MemorySegment needed = arena.allocate(ValueLayout.JAVA_INT);
		MemorySegment count = arena.allocate(ValueLayout.JAVA_INT);
		MemorySegment reboot = arena.allocate(ValueLayout.JAVA_INT);
		int status = binding.list.invoke(handle, needed, count, MemorySegment.NULL, reboot);
		if (status != ERROR_MORE_DATA)
		{
			recordStatus("RmGetList(size)", status, calls, errors);
			calls.add("rebootReasons=" + Integer.toUnsignedString(reboot.get(ValueLayout.JAVA_INT, 0)));
			return;
		}
		calls.add("RmGetList(size) status=" + ERROR_MORE_DATA + " (buffer negotiation)");
		int capacity = needed.get(ValueLayout.JAVA_INT, 0);
		MemorySegment entries = arena.allocate(PROCESS_INFO, Integer.toUnsignedLong(capacity));
		count.set(ValueLayout.JAVA_INT, 0, capacity);
		status = binding.list.invoke(handle, needed, count, entries, reboot);
		calls.add("rebootReasons=" + Integer.toUnsignedString(reboot.get(ValueLayout.JAVA_INT, 0)));
		if (recordStatus("RmGetList(read)", status, calls, errors))
		{
			int returned = count.get(ValueLayout.JAVA_INT, 0);
			if (Integer.compareUnsigned(returned, capacity) > 0)
			{
				errors.add(new IOException("RmGetList returned more entries than its supplied buffer"));
				return;
			}
			for (int index = 0; index < returned; ++index)
				users.add(readUser(entries.asSlice(index * PROCESS_INFO.byteSize(), PROCESS_INFO.byteSize()), errors));
		}
	}

	/**
	 * Decodes named header fields and records available PID identity without assuming PID reuse cannot occur.
	 *
	 * @param entry  one RM_PROCESS_INFO structure
	 * @param errors failed process identity inspections
	 * @return the native unique identity and descriptive information
	 */
	private static User readUser(MemorySegment entry, List<IOException> errors)
	{
		long pid = Integer.toUnsignedLong(entry.get(ValueLayout.JAVA_INT, PROCESS_INFO.byteOffset(
			MemoryLayout.PathElement.groupElement("process"), MemoryLayout.PathElement.groupElement("pid"))));
		long creationOffset = PROCESS_INFO.byteOffset(MemoryLayout.PathElement.groupElement("process"),
			MemoryLayout.PathElement.groupElement("creation"));
		long creation = Integer.toUnsignedLong(entry.get(ValueLayout.JAVA_INT, creationOffset)) |
			(Integer.toUnsignedLong(entry.get(ValueLayout.JAVA_INT, creationOffset + Integer.BYTES)) << Integer.SIZE);
		String identity;
		try
		{
			identity = ProcessHandle.of(pid).map(process -> "alive=" + process.isAlive() + " " + process.info()).
				orElse("PID no longer available");
		}
		catch (RuntimeException failure)
		{
			identity = "PID identity inspection failed";
			errors.add(new IOException("Cannot inspect Restart Manager PID " + pid, failure));
		}
		return new User(pid, creation, readText(entry, "app", 256), readText(entry, "service", 64),
			readInt(entry, "type"), readInt(entry, "status"), readInt(entry, "session"),
			readInt(entry, "restartable"), identity);
	}

	/**
	 * Reads a named DWORD, BOOL or enum32 field from the documented layout.
	 *
	 * @param entry the native structure
	 * @param name  the header member
	 * @return its unchanged 32-bit representation
	 */
	private static int readInt(MemorySegment entry, String name)
	{
		return entry.get(ValueLayout.JAVA_INT, PROCESS_INFO.byteOffset(MemoryLayout.PathElement.groupElement(name)));
	}

	/**
	 * Reads a bounded Windows WCHAR array without changing its UTF-16 code units.
	 *
	 * @param entry    the native structure
	 * @param name     the header member
	 * @param capacity the header's WCHAR array length
	 * @return the text up to its terminator or array boundary
	 */
	private static String readText(MemorySegment entry, String name, int capacity)
	{
		long offset = PROCESS_INFO.byteOffset(MemoryLayout.PathElement.groupElement(name));
		StringBuilder text = new StringBuilder(capacity);
		for (int index = 0; index < capacity; ++index)
		{
			char value = entry.get(ValueLayout.JAVA_CHAR, offset + (long) index * Character.BYTES);
			if (value == '\0')
				break;
			text.append(value);
		}
		return text.toString();
	}

	/**
	 * Retains native identity even when a later PID lookup exits or refers to a reused PID.
	 *
	 * @param pid               the native unsigned process identifier
	 * @param creationFiletime  the native unsigned creation FILETIME bit pattern
	 * @param application       the Restart Manager application name
	 * @param service           the service short name
	 * @param type              the RM_APP_TYPE value
	 * @param status            the application status bits
	 * @param session           the terminal services session bits
	 * @param restartable       the native BOOL value
	 * @param availableIdentity the later PID lookup, requiring comparison with the native creation time
	 */
	record User(long pid, long creationFiletime, String application, String service, int type, int status, int session,
		int restartable, String availableIdentity)
	{
	}

	/**
	 * Retains the entire observation interval; an empty or failed query leaves ownership unknown.
	 *
	 * @param file     the exact registered absolute file
	 * @param started  the UTC query start
	 * @param finished the UTC query end
	 * @param calls    ordered native statuses and reboot reasons
	 * @param users    current users returned for this exact file
	 * @param errors   explicit query and identity failures
	 */
	record Observation(Path file, Instant started, Instant finished, List<String> calls, List<User> users,
		List<IOException> errors)
	{
		/**
		 * Creates a suppressed diagnostic without replacing the fatal deletion exception.
		 *
		 * @return the observation and each individual inspection failure
		 */
		IOException diagnostic()
		{
			StringBuilder text = new StringBuilder(512).append("Restart Manager file=").append(file).
				append(" started=").append(started).append(" finished=").append(finished).
				append(" calls=").append(calls).append('\n');
			if (users.isEmpty() || !errors.isEmpty())
				text.append("Ownership unknown: empty or incomplete observation; this does not mean unlocked.\n");
			for (User user : users)
			{
				text.append("pid=").append(user.pid()).append(" creationFiletime=").
					append(Long.toUnsignedString(user.creationFiletime())).append(" application=").append(user.application()).
					append(" service=").append(user.service()).append(" type=").append(user.type()).
					append(" status=").append(user.status()).append(" session=").append(user.session()).
					append(" restartable=").append(user.restartable()).append(" laterPidLookup=").
					append(user.availableIdentity()).append(" (compare creation time before correlating)\n");
			}
			text.append("Current resource use does not prove the cause of the earlier deletion failure.");
			IOException diagnostic = new IOException(text.toString());
			for (IOException error : errors)
				diagnostic.addSuppressed(error);
			return diagnostic;
		}
	}

	/**
	 * Fixed RmStartSession ABI.
	 */
	@FunctionalInterface
	public interface Start
	{
		/**
		 * Starts a session without shutdown or restart requests.
		 *
		 * @param session the output DWORD session handle
		 * @param flags   the reserved zero flags
		 * @param key     the output WCHAR session-key buffer
		 * @return the native status
		 */
		int invoke(MemorySegment session, int flags, MemorySegment key);
	}

	/**
	 * Fixed RmRegisterResources ABI.
	 */
	@FunctionalInterface
	public interface Register
	{
		/**
		 * Registers only the exact file passed by the deletion catch.
		 *
		 * @param session      the owned session handle
		 * @param files        the file count
		 * @param filenames    the LPCWSTR array
		 * @param applications the zero application count
		 * @param processes    the null application array
		 * @param services     the zero service count
		 * @param names        the null service array
		 * @return the native status
		 */
		int invoke(int session, int files, MemorySegment filenames, int applications, MemorySegment processes,
			int services, MemorySegment names);
	}

	/**
	 * Fixed RmGetList ABI.
	 */
	@FunctionalInterface
	public interface GetList
	{
		/**
		 * Observes current resource users without affecting them.
		 *
		 * @param session the owned session handle
		 * @param needed  the output UINT required capacity
		 * @param count   the input capacity and output entry count
		 * @param entries the RM_PROCESS_INFO array or null during negotiation
		 * @param reboot  the output DWORD reboot reasons
		 * @return the native status
		 */
		int invoke(int session, MemorySegment needed, MemorySegment count, MemorySegment entries, MemorySegment reboot);
	}

	/**
	 * Fixed RmEndSession ABI.
	 */
	@FunctionalInterface
	public interface End
	{
		/**
		 * Ends the owned session without affecting registered resource users.
		 *
		 * @param session the owned session handle
		 * @return the native status
		 */
		int invoke(int session);
	}

	/**
	 * Owns bindings only while the calling arena keeps Rstrtmgr.dll loaded, using the running JVM's ABI.
	 */
	private static final class Binding
	{
		private final Start start;
		private final Register register;
		private final GetList list;
		private final End end;

		/**
		 * Binds the documented Windows signatures; the distribution test JVM grants this module native access.
		 *
		 * @param arena the library lifetime, enclosing every session and call
		 */
		@SuppressWarnings("restricted")
		private Binding(Arena arena)
		{
			SymbolLookup symbols = SymbolLookup.libraryLookup("Rstrtmgr.dll", arena);
			start = MethodHandleProxies.asInterfaceInstance(Start.class, function(symbols, "RmStartSession",
				ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
			register = MethodHandleProxies.asInterfaceInstance(Register.class, function(symbols, "RmRegisterResources",
				ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
				ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
			list = MethodHandleProxies.asInterfaceInstance(GetList.class, function(symbols, "RmGetList",
				ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
			end = MethodHandleProxies.asInterfaceInstance(End.class, function(symbols, "RmEndSession", ValueLayout.JAVA_INT));
		}

		/**
		 * Creates a fixed DWORD-returning downcall with explicitly documented argument layouts.
		 *
		 * @param symbols   the loaded system library
		 * @param name      the documented export
		 * @param arguments the documented Win32 argument layouts
		 * @return the native function using the running JVM's linker
		 */
		@SuppressWarnings("restricted")
		private static MethodHandle function(SymbolLookup symbols, String name, MemoryLayout... arguments)
		{
			return Linker.nativeLinker().downcallHandle(symbols.find(name).
				orElseThrow(() -> new UnsupportedOperationException("Missing Restart Manager export " + name)),
				FunctionDescriptor.of(ValueLayout.JAVA_INT, arguments));
		}
	}
}
