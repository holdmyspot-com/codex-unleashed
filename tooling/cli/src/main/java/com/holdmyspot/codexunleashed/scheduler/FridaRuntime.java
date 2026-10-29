package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import static java.lang.foreign.ValueLayout.ADDRESS;

/**
 * Owns one Frida native runtime and its manager owners on the constructing thread.
 * Construction acquires nothing. Call start inside a scope that always closes this owner, including after failed start.
 * The trusted producer must export the pinned devkit ABI; only Linux x86-64 has native acceptance so far.
 * <p>
 * The controller must load this type once and use no other initializer for this producer in the JVM. Native
 * initialization is permitted only once; successful shutdown does not permit reinitialization. Managers must close before
 * this runtime. A refused close while managers remain does not change state; release those managers and close again.
 * The native runtime owns its dispatcher. This type neither starts a second dispatcher nor owns instrumented processes.
 */
final class FridaRuntime implements AutoCloseable
{
	/** Version whose devkit exports and runtime lifecycle the scheduler has evaluated. */
	private static final String VERSION = "17.24.3";
	/** Process-wide native one-time initialization cannot be reversed by deinit. Guarded by the class monitor. */
	private static boolean initializationClaimed = false;
	/** Trusted native file selected and verified by the caller's acquisition workflow. */
	private final Path producer;
	/** The only thread permitted to operate on this runtime and its manager owners. */
	private final Thread owner = Thread.currentThread();
	/** Managers remain owned here until their explicit cleanup succeeds. */
	private final Set<Manager> managers = new LinkedHashSet<>();
	/** Native file scope; retained after partial start until close succeeds. */
	private Arena libraryArena = null;
	/** Explicit producer lookup, available only after native file loading succeeds. */
	private SymbolLookup library = null;
	/** Indicates whether the one permitted acquisition attempt has begun. */
	private boolean startAttempted = false;
	/** Indicates whether native initialization returned successfully. */
	private boolean initialized = false;
	/** Indicates whether initialization was entered without a proven return; native lifetime remains unresolved. */
	private boolean initializing = false;
	/** Indicates whether global shutdown has begun, preventing further manager acquisition. */
	private boolean closing = false;
	/** Indicates whether every owned native resource has been released. */
	private boolean closed = false;
	/** Latched global shutdown failure; repeated close reports it without repeating uncertain native effects. */
	private IOException closeFailure = null;
	/** Native initialization, resolved before any global effect. */
	private MethodHandle init = null;
	/** Native dispatcher shutdown, resolved before any global effect. */
	private MethodHandle shutdown = null;
	/** Native runtime teardown, resolved before any global effect. */
	private MethodHandle deinit = null;
	/** Manager acquisition, resolved before any global effect. */
	private MethodHandle newManager = null;
	/** Synchronous per-manager close, resolved before any global effect. */
	private MethodHandle closeManager = null;
	/** Local-device acquisition initializes the manager backend before it can serve an attempt. */
	private MethodHandle findLocalDevice = null;
	/** Reference retirement on the native runtime's dispatcher. */
	private MethodHandle unref = null;
	/** Error disposal, resolved before errors can be returned. */
	private MethodHandle errorFree = null;
	/** Conversion of native error-domain identifiers to their borrowed names. */
	private MethodHandle quarkToString = null;

	/**
	 * Retains the explicit trusted producer path without acquiring resources.
	 * @param producer native library file selected by the caller; relative paths use the process working directory
	 * @throws NullPointerException if {@code producer} is null
	 */
	FridaRuntime(Path producer)
	{
		this.producer = Objects.requireNonNull(producer, "producer may not be null");
	}

	/**
	 * Loads the producer, requires version 17.24.3, resolves every used export and initializes the native dispatcher.
	 * Call at most once; failed acquisition remains owned by this object and requires close.
	 * @throws IllegalStateException if called by another thread, after a start attempt or after close
	 * @throws IOException if loading, symbol resolution, version validation or the JVM-wide initialization claim fails
	 */
	void start() throws IOException
	{
		requireThread();
		if (startAttempted || closed)
			throw new IllegalStateException("Frida runtime start is permitted once on an open owner");
		startAttempted = true;
		libraryArena = Arena.ofShared();
		try
		{
			library = SymbolLookup.libraryLookup(producer, libraryArena);
		}
		catch (IllegalArgumentException failure)
		{
			throw new IOException("Cannot load Frida producer: " + producer, failure);
		}

		MethodHandle version = function("frida_version_string", FunctionDescriptor.of(ADDRESS));
		String actualVersion = string((MemorySegment) invoke(version));
		if (VERSION.equals(actualVersion) == false)
			throw new IOException("Frida producer version must be " + VERSION + "; found " + actualVersion);
		init = function("frida_init", FunctionDescriptor.ofVoid());
		shutdown = function("frida_shutdown", FunctionDescriptor.ofVoid());
		deinit = function("frida_deinit", FunctionDescriptor.ofVoid());
		newManager = function("frida_device_manager_new", FunctionDescriptor.of(ADDRESS));
		closeManager = function("frida_device_manager_close_sync", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
		findLocalDevice = function("frida_device_manager_get_device_by_type_sync",
			FunctionDescriptor.of(ADDRESS, ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ADDRESS, ADDRESS));
		unref = function("frida_unref", FunctionDescriptor.ofVoid(ADDRESS));
		errorFree = function("_frida_g_error_free", FunctionDescriptor.ofVoid(ADDRESS));
		quarkToString = function("_frida_g_quark_to_string", FunctionDescriptor.of(ADDRESS, ValueLayout.JAVA_INT));

		synchronized (FridaRuntime.class)
		{
			if (initializationClaimed)
				throw new IOException("Frida native initialization was already claimed in this JVM; use its existing runtime");
			initializationClaimed = true;
			initializing = true;
			invoke(init);
			initialized = true;
			initializing = false;
		}
	}

	/**
	 * Reserves a resource-free manager owner. Its start acquires a distinct native manager.
	 * The returned owner must close even when never started or when its start fails.
	 * @return a distinct owner retained by this runtime until cleanup succeeds
	 * @throws IllegalStateException if called by another thread or the runtime is unstarted, closing or closed
	 */
	Manager newManager()
	{
		requireActive();
		Manager manager = new Manager();
		managers.add(manager);
		return manager;
	}

	/**
	 * Closes native resources after all manager owners have closed. Unstarted or failed pre-initialization owners
	 * release only their library scope. Repeated successful close is a no-op; a global teardown failure is retained.
	 * @throws IllegalStateException if called by another thread
	 * @throws IOException if managers remain, initialization has no proven return, or global teardown failed
	 */
	@Override
	public void close() throws IOException
	{
		requireThread();
		if (closed)
			return;
		if (closeFailure != null)
			throw closeFailure;
		if (managers.isEmpty() == false)
			throw new IOException("Frida runtime still owns " + managers.size() + " managers; close them before global shutdown");
		if (initializing)
			throw new IOException("Frida initialization has no proven return; retain the runtime and native library");
		closing = true;
		try
		{
			if (initialized)
			{
				invoke(shutdown);
				invoke(deinit);
			}
			if (libraryArena != null)
				libraryArena.close();
			closed = true;
		}
		catch (RuntimeException | Error failure)
		{
			closeFailure = new IOException("Frida global teardown did not complete; native lifetime remains unresolved", failure);
			throw closeFailure;
		}
	}

	/**
	 * Owns one manager without owning the shared dispatcher. Operations are confined to the runtime's owner thread.
	 * A failed close retains its native reference and failure, preventing global teardown; it never retries silently.
	 */
	final class Manager implements AutoCloseable
	{
		/** Manager reference assigned immediately after acquisition; NULL before acquisition succeeds. */
		private MemorySegment handle = MemorySegment.NULL;
		/** Owned local device reference; NULL until backend selection returns it. */
		private MemorySegment device = MemorySegment.NULL;
		/** Indicates whether this owner has attempted native acquisition. */
		private boolean startAttempted = false;
		/** Indicates whether this owner has released all resources and its runtime registration. */
		private boolean closed = false;
		/** First cleanup failure retained without releasing an unresolved manager. */
		private IOException closeFailure = null;

		/**
		 * Creates a resource-free owner; the enclosing runtime registers it before handing it to its caller.
		 */
		private Manager()
		{
		}

		/**
		 * Acquires a distinct native manager and selects its local device once. Failed acquisition remains owned and requires close.
		 * @throws IllegalStateException if called by another thread, after start or close, or while the runtime is inactive
		 * @throws IOException if native acquisition returns no manager or local-device selection fails
		 */
		void start() throws IOException
		{
			requireActive();
			if (startAttempted || closed)
				throw new IllegalStateException("Frida manager start is permitted once on an open owner");
			startAttempted = true;
			handle = (MemorySegment) invoke(newManager);
			if (handle.equals(MemorySegment.NULL))
				throw new IOException("Frida manager acquisition returned NULL");

			try (Arena arena = Arena.ofConfined())
			{
				MemorySegment error = arena.allocate(ADDRESS);
				device = (MemorySegment) invoke(findLocalDevice, handle, 0, 0, MemorySegment.NULL, error);
				throwNativeFailure("frida_device_manager_get_device_by_type_sync", error.get(ADDRESS, 0));
				if (device.equals(MemorySegment.NULL))
					throw new IOException("Frida local-device selection returned NULL without a native error");
			}
		}

		/**
		 * Closes only this manager, retires its reference and releases its runtime registration.
		 * Repeated successful close is a no-op; failed cleanup preserves its reference and original failure.
		 * @throws IllegalStateException if called by another thread
		 * @throws IOException if native close fails; native domain, code and message are retained by NativeFailure
		 */
		@Override
		public void close() throws IOException
		{
			requireThread();
			if (closed)
				return;
			if (closeFailure != null)
				throw closeFailure;
			try
			{
				if (handle.equals(MemorySegment.NULL) == false)
				{
					try (Arena arena = Arena.ofConfined())
					{
						MemorySegment error = arena.allocate(ADDRESS);
						invoke(closeManager, handle, MemorySegment.NULL, error);
						throwNativeFailure("frida_device_manager_close_sync", error.get(ADDRESS, 0));
					}
					if (device.equals(MemorySegment.NULL) == false)
					{
						invoke(unref, device);
						device = MemorySegment.NULL;
					}
					invoke(unref, handle);
					handle = MemorySegment.NULL;
				}
				managers.remove(this);
				closed = true;
			}
			catch (IOException failure)
			{
				closeFailure = failure;
				throw failure;
			}
			catch (RuntimeException | Error failure)
			{
				closeFailure = new IOException("Frida manager cleanup did not complete; retain its runtime", failure);
				throw closeFailure;
			}
		}
	}

	/**
	 * Native operation failure whose code is interpreted only within its native domain.
	 */
	static final class NativeFailure extends IOException
	{
		/** Native operation that returned the error. */
		private final String operation;
		/** Native error-domain name, copied before the GError was freed. */
		private final String domain;
		/** Domain-specific native error code, not an operating-system errno. */
		private final int code;

		/**
		 * Copies the native error details into Java-owned values.
		 * @param operation native function that failed
		 * @param domain native error-domain name
		 * @param code native error code within that domain
		 * @param message native diagnostic
		 */
		private NativeFailure(String operation, String domain, int code, String message)
		{
			super(operation + " failed: domain=" + domain + ", code=" + code + ", message=" + message);
			this.operation = operation;
			this.domain = domain;
			this.code = code;
		}

		/**
		 * Returns the native function that failed.
		 * @return the operation name
		 */
		String operation()
		{
			return operation;
		}

		/**
		 * Returns the copied native error-domain name.
		 * @return the domain qualifying the error code
		 */
		String domain()
		{
			return domain;
		}

		/**
		 * Returns the domain-specific native error code.
		 * @return the code within domain(), not errno
		 */
		int code()
		{
			return code;
		}
	}

	/**
	 * Copies and frees a returned GError, then raises its typed failure; NULL means success.
	 * @param operation native function that returned the error
	 * @param error owned GError pointer, or NULL for success
	 * @throws NativeFailure if the error is non-NULL
	 */
	private void throwNativeFailure(String operation, MemorySegment error) throws NativeFailure
	{
		if (error.equals(MemorySegment.NULL))
			return;
		try
		{
			MemorySegment fields = error.reinterpret(8 + ADDRESS.byteSize());
			int domain = fields.get(ValueLayout.JAVA_INT, 0);
			int code = fields.get(ValueLayout.JAVA_INT, 4);
			String domainName = string((MemorySegment) invoke(quarkToString, domain));
			String message = string(fields.get(ADDRESS, 8));
			throw new NativeFailure(operation, domainName, code, message);
		}
		finally
		{
			invoke(errorFree, error);
		}
	}

	/**
	 * Resolves an exact export from the explicitly loaded producer.
	 * @param name devkit export name, including its documented prefix
	 * @param descriptor C ABI descriptor for that export
	 * @return the matching downcall handle
	 * @throws IOException if the producer lacks the required export
	 */
	private MethodHandle function(String name, FunctionDescriptor descriptor) throws IOException
	{
		MemorySegment symbol = library.find(name).orElseThrow(() ->
			new IOException("Frida producer lacks required export " + name + ": " + producer));
		return Linker.nativeLinker().downcallHandle(symbol, descriptor);
	}

	/**
	 * Invokes a pre-resolved C function. Checked Java exceptions cannot originate from these C entry points.
	 * @param function downcall handle with its verified ABI
	 * @param arguments arguments matching that ABI
	 * @return the native value, or null for void
	 * @throws AssertionError if the FFM invocation raises an unexpected checked exception
	 */
	private static Object invoke(MethodHandle function, Object... arguments)
	{
		try
		{
			return function.invokeWithArguments(arguments);
		}
		catch (RuntimeException | Error failure)
		{
			throw failure;
		}
		catch (Throwable failure)
		{
			throw new AssertionError("Native Frida downcall raised a checked Java exception", failure);
		}
	}

	/**
	 * Copies a borrowed native NUL-terminated string before its owner changes or releases it.
	 * @param text valid native string pointer, or NULL
	 * @return the copied text, or an empty string for NULL
	 */
	private static String string(MemorySegment text)
	{
		if (text.equals(MemorySegment.NULL))
			return "";
		return text.reinterpret(Long.MAX_VALUE).getString(0);
	}

	/**
	 * Requires an initialized runtime that has not begun global shutdown.
	 * @throws IllegalStateException if called by another thread or the runtime is inactive
	 */
	private void requireActive()
	{
		requireThread();
		if (initialized == false || closing || closed)
			throw new IllegalStateException("Frida runtime must be initialized and open");
	}

	/**
	 * Requires all operations, including cleanup, to use the constructing thread.
	 * @throws IllegalStateException if called by another thread
	 */
	private void requireThread()
	{
		if (Thread.currentThread() != owner)
			throw new IllegalStateException("Frida runtime operations must use its constructing thread");
	}
}
