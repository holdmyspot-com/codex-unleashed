package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.JsonNode;

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
	/** Attachment acquisition with a guint process identity. */
	private MethodHandle attach = null;
	/** Synchronous session detachment. */
	private MethodHandle detach = null;
	/** Observation of native session detachment, including target exit. */
	private MethodHandle isDetached = null;
	/** Script options acquisition. */
	private MethodHandle newOptions = null;
	/** QuickJS selection on owned script options. */
	private MethodHandle setRuntime = null;
	/** Script acquisition. */
	private MethodHandle createScript = null;
	/** Synchronous script loading. */
	private MethodHandle loadScript = null;
	/** Synchronous script unloading. */
	private MethodHandle unloadScript = null;
	/** Observation of native script destruction, including target exit. */
	private MethodHandle isDestroyed = null;
	/** Delivery of serialized JSON without binary data. */
	private MethodHandle post = null;
	/** GLib signal registration using the platform's gulong carrier. */
	private MethodHandle connect = null;
	/** GLib signal disconnection using the same platform carrier. */
	private MethodHandle disconnect = null;
	/** Explicit resumption of a native-gated child. */
	private MethodHandle resume = null;
	/** Child gating enabled before a prepared target is released. */
	private MethodHandle enableChildGating = null;
	/** Reads the unsigned child PID while its borrowed notification remains valid. */
	private MethodHandle childPid = null;
	/** Reads the unsigned parent PID from a borrowed child notification. */
	private MethodHandle childParent = null;
	/** Reads the native fork, exec or spawn origin from a borrowed child notification. */
	private MethodHandle childOrigin = null;
	/** Reads the optional borrowed executable path, which the callback copies immediately. */
	private MethodHandle childPath = null;
	/** Native unsigned long layout; IDs use its exact bits, never signed ordering. */
	private final ValueLayout signalIdLayout = (ValueLayout) Linker.nativeLinker().canonicalLayouts().get("long");

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
		attach = function("frida_device_attach_sync",
			FunctionDescriptor.of(ADDRESS, ADDRESS, ValueLayout.JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
		detach = function("frida_session_detach_sync", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
		isDetached = function("frida_session_is_detached", FunctionDescriptor.of(ValueLayout.JAVA_INT, ADDRESS));
		newOptions = function("frida_script_options_new", FunctionDescriptor.of(ADDRESS));
		setRuntime = function("frida_script_options_set_runtime", FunctionDescriptor.ofVoid(ADDRESS, ValueLayout.JAVA_INT));
		createScript = function("frida_session_create_script_sync", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
		loadScript = function("frida_script_load_sync", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
		unloadScript = function("frida_script_unload_sync", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
		isDestroyed = function("frida_script_is_destroyed", FunctionDescriptor.of(ValueLayout.JAVA_INT, ADDRESS));
		post = function("frida_script_post", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
		connect = function("_frida_g_signal_connect_data", FunctionDescriptor.of(signalIdLayout, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ValueLayout.JAVA_INT));
		disconnect = function("_frida_g_signal_handler_disconnect", FunctionDescriptor.ofVoid(ADDRESS, signalIdLayout));

		resume = function("frida_device_resume_sync", FunctionDescriptor.ofVoid(ADDRESS, ValueLayout.JAVA_INT, ADDRESS, ADDRESS));
		enableChildGating = function("frida_session_enable_child_gating_sync", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
		childPid = function("frida_child_get_pid", FunctionDescriptor.of(ValueLayout.JAVA_INT, ADDRESS));
		childParent = function("frida_child_get_parent_pid", FunctionDescriptor.of(ValueLayout.JAVA_INT, ADDRESS));
		childOrigin = function("frida_child_get_origin", FunctionDescriptor.of(ValueLayout.JAVA_INT, ADDRESS));
		childPath = function("frida_child_get_path", FunctionDescriptor.of(ADDRESS, ADDRESS));

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
			throw new IOException("Frida global teardown previously failed; native lifetime remains unresolved", closeFailure);
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
		/** Sessions remain owned until their native references are released. */
		private final Set<Session> sessions = new LinkedHashSet<>();
		/** Native child-event receiver, retained independently of this manager's lifetime. */
		private ChildReceiver children = null;
		/** Device signal handler bits, zero until registration succeeds. */
		private long childHandler = 0;
		/** Indicates whether local-device acquisition finished without a native error. */
		private boolean started = false;

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
			children = new ChildReceiver(childPid, childParent, childOrigin, childPath);
			try
			{
				MethodHandle callback = MethodHandles.lookup().findVirtual(ChildReceiver.class, "onChild",
					MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class, MemorySegment.class)).bindTo(children);
				childHandler = registerSignal(device, "child-added", callback, FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
			}
			catch (ReflectiveOperationException failure)
			{
				throw new IOException("Cannot bind the native child-event receiver", failure);
			}
			started = true;
		}

		/**
		 * Reserves an attachment owner without attaching. The caller must own and verify the target process separately.
		 * @param pid positive process identity representable by Frida's 32-bit unsigned API
		 * @return a resource-free attachment owner that must close even after failed start
		 * @throws IllegalArgumentException if the process identity is outside 1 through 4294967295
		 * @throws IllegalStateException if the manager is not started and open on its owner thread
		 */
		Session newSession(long pid)
		{
			requireManager();
			Session session = new Session(pid);
			sessions.add(session);
			return session;
		}

		/**
		 * Returns a copied native child-added event, retaining callback errors even after close.
		 * An EXEC event alone does not verify credentials, image, containment or whole-job completion.
		 * @return next event, or empty when none is queued
		 * @throws IllegalStateException if called by another thread or before receiver acquisition
		 * @throws IOException if copying any callback failed
		 */
		Optional<Child> pollChild() throws IOException
		{
			requireThread();
			if (children == null)
				throw new IllegalStateException("Frida child receiver has not been acquired");
			return children.poll();
		}

		/**
		 * Resumes a native-gated process whose ownership and identity the caller has separately established.
		 * @param pid positive unsigned 32-bit process identity
		 * @throws IllegalArgumentException if pid is outside 1 through 4294967295
		 * @throws IllegalStateException if the manager is inactive or called by another thread
		 * @throws IOException if native resumption fails, preserving domain and code
		 */
		void resume(long pid) throws IOException
		{
			requireManager();
			requirePid(pid);
			try (Arena arena = Arena.ofConfined())
			{
				MemorySegment error = arena.allocate(ADDRESS);
				invoke(resume, device, (int) pid, MemorySegment.NULL, error);
				throwNativeFailure("frida_device_resume_sync", error.get(ADDRESS, 0));
			}
		}

		/**
		 * Requires a successfully acquired manager on its owner thread.
		 * @throws IllegalStateException if the manager or enclosing runtime is inactive
		 */
		private void requireManager()
		{
			requireActive();
			if (started == false || closed || closeFailure != null)
				throw new IllegalStateException("Frida manager must be started and open");
		}

		/**
		 * Closes only this manager, retires its reference and releases its runtime registration.
		 * Repeated successful close is a no-op; failed cleanup preserves its reference and original failure.
		 * @throws IllegalStateException if called by another thread
		 * @throws IOException if sessions remain or native close fails; native errors retain domain, code and message
		 */
		@Override
		public void close() throws IOException
		{
			requireThread();
			if (closed)
				return;
			if (closeFailure != null)
				throw new IOException("Frida manager cleanup previously failed; retain its runtime", closeFailure);
			if (sessions.isEmpty() == false)
				throw new IOException("Frida manager still owns sessions; close them first");
			try
			{
				if (children != null)
					children.stop();
				if (childHandler != 0)
				{
					disconnectSignal(device, childHandler);
					childHandler = 0;
				}
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

		/**
		 * Owns one native attachment and its scripts, independently of target process ownership.
		 */
		final class Session implements AutoCloseable
		{
			/** Verified target identity supplied by the process owner. */
			private final long pid;
			/** Native reference retained from attachment until successful detach and unref. */
			private MemorySegment handle = MemorySegment.NULL;
			/** Scripts whose ownership prevents session detachment. */
			private final Set<Script> scripts = new LinkedHashSet<>();
			/** Indicates whether the single attachment attempt has begun. */
			private boolean startAttempted = false;
			/** Indicates whether the one child-gating request has begun, including an uncertain failure. */
			private boolean childGatingAttempted = false;
			/** Indicates whether attachment returned successfully without a native error. */
			private boolean started = false;
			/** Indicates whether this owner has released its reference and registration. */
			private boolean closed = false;
			/** First native cleanup failure, retained without repeating uncertain effects. */
			private IOException closeFailure = null;

			/**
			 * Validates and retains the process identity without attaching.
			 * @param pid positive unsigned 32-bit target identity
			 * @throws IllegalArgumentException if the identity is outside 1 through 4294967295
			 */
			private Session(long pid)
			{
				requirePid(pid);
				this.pid = pid;
			}

			/**
			 * Attaches once, retaining acquired references even when the native call returns an error.
			 * @throws IllegalStateException if already attempted, closed, or the manager is inactive
			 * @throws IOException if attachment fails, retaining native domain and code
			 */
			void start() throws IOException
			{
				requireManager();
				if (startAttempted || closed)
					throw new IllegalStateException("Frida session start is permitted once on an open owner");
				startAttempted = true;
				try (Arena arena = Arena.ofConfined())
				{
					MemorySegment error = arena.allocate(ADDRESS);
					handle = (MemorySegment) invoke(attach, device, (int) pid, MemorySegment.NULL, MemorySegment.NULL, error);
					throwNativeFailure("frida_device_attach_sync", error.get(ADDRESS, 0));
					if (handle.equals(MemorySegment.NULL))
						throw new IOException("Frida attachment returned NULL without a native error");
				}
				started = true;
			}

			/**
			 * Enables native child gating once before the caller releases its prepared target.
			 * The caller must separately verify each event and explicitly resume or terminate its owned gated work.
			 * @throws IllegalStateException if this attachment is inactive or the request was already attempted
			 * @throws IOException if enabling fails, possibly after effects; session ownership remains retained
			 */
			void enableChildGating() throws IOException
			{
				requireSession();
				if (childGatingAttempted)
					throw new IllegalStateException("Child gating may be requested once per Frida session");
				childGatingAttempted = true;
				checkedVoid("frida_session_enable_child_gating_sync", enableChildGating, handle);
			}

			/**
			 * Reserves a QuickJS script owner without native acquisition.
			 * @param source script source without embedded NUL characters
			 * @return a resource-free script owner requiring close even after failed start
			 * @throws NullPointerException if source is null
			 * @throws IllegalArgumentException if source contains NUL
			 * @throws IllegalStateException if this attachment is inactive or closed
			 */
			Script newScript(String source)
			{
				requireSession();
				Script script = new Script(source);
				scripts.add(script);
				return script;
			}

			/**
			 * Requires a successfully acquired attachment on its owner thread.
			 * @throws IllegalStateException if this attachment or its manager is inactive
			 */
			private void requireSession()
			{
				requireManager();
				if (started == false || closed || closeFailure != null)
					throw new IllegalStateException("Frida session must be started and open");
			}

			/**
			 * Detaches if still attached, releases the native reference and removes this owner after all scripts close.
			 * Failed native cleanup is latched; refusal while scripts remain is recoverable by closing those scripts.
			 * @throws IllegalStateException if called by another thread
			 * @throws IOException if scripts remain or native cleanup fails
			 */
			@Override
			public void close() throws IOException
			{
				requireThread();
				if (closed)
					return;
				if (closeFailure != null)
					throw new IOException("Frida session cleanup previously failed; retain its manager", closeFailure);
				if (scripts.isEmpty() == false)
					throw new IOException("Frida session still owns scripts; close them first");
				try
				{
					if (handle.equals(MemorySegment.NULL) == false)
					{
						if ((int) invoke(isDetached, handle) == 0)
							checkedVoid("frida_session_detach_sync", detach, handle);
						invoke(unref, handle);
						handle = MemorySegment.NULL;
					}
					sessions.remove(this);
					closed = true;
				}
				catch (IOException failure)
				{
					closeFailure = failure;
					throw failure;
				}
				catch (RuntimeException | Error failure)
				{
					closeFailure = new IOException("Frida session cleanup did not complete; retain its manager", failure);
					throw closeFailure;
				}
			}

			/**
			 * Owns a QuickJS script, its message registration and copied JSON messages.
			 * Native callback stubs stay in the runtime's library arena until global teardown, even after this owner closes.
			 */
			final class Script implements AutoCloseable
			{
				/** Source executed only after registration of its message callback. */
				private final String source;
				/** Receiver contains no reference to this owner; its retained stub cannot retain the closed ownership tree. */
				private final Receiver receiver = new Receiver();
				/** Native script reference retained until successful cleanup. */
				private MemorySegment handle = MemorySegment.NULL;
				/** Owned QuickJS options reference retained until cleanup. */
				private MemorySegment options = MemorySegment.NULL;
				/** Native signal ID bits, zero until registration succeeds. */
				private long handler = 0;
				/** Indicates whether the single native acquisition attempt has begun. */
				private boolean startAttempted = false;
				/** Indicates whether native loading was entered, including an unsuccessful return. */
				private boolean loadAttempted = false;
				/** Indicates whether native loading returned successfully. */
				private boolean loaded = false;
				/** Indicates whether native references and the session registration have been released. */
				private boolean closed = false;
				/** First native cleanup failure, retained without repeating uncertain effects. */
				private IOException closeFailure = null;

				/**
				 * Retains validated script source without native acquisition.
				 * @param source QuickJS source without NUL characters
				 * @throws NullPointerException if source is null
				 * @throws IllegalArgumentException if source contains NUL
				 */
				private Script(String source)
				{
					this.source = requireCString(source, "Script source");
				}

				/**
				 * Creates and loads the script once, registering its protected native callback before execution.
				 * Failed start retains every acquired native reference for close.
				 * @throws IllegalStateException if already attempted, closed, or the attachment is inactive
				 * @throws IOException if native acquisition, callback registration or loading fails
				 */
				void start() throws IOException
				{
					requireSession();
					if (startAttempted || closed)
						throw new IllegalStateException("Frida script start is permitted once on an open owner");
					startAttempted = true;
					options = (MemorySegment) invoke(newOptions);
					if (options.equals(MemorySegment.NULL))
						throw new IOException("Frida script-options acquisition returned NULL");
					invoke(setRuntime, options, 1);
					try (Arena arena = Arena.ofConfined())
					{
						MemorySegment error = arena.allocate(ADDRESS);
						handle = (MemorySegment) invoke(createScript, Session.this.handle, arena.allocateFrom(source), options, MemorySegment.NULL, error);
						throwNativeFailure("frida_session_create_script_sync", error.get(ADDRESS, 0));
						if (handle.equals(MemorySegment.NULL))
							throw new IOException("Frida script acquisition returned NULL without a native error");
						MethodHandle callback;
						try
						{
							callback = MethodHandles.lookup().findVirtual(Receiver.class, "onMessage",
								MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class, MemorySegment.class, MemorySegment.class)).bindTo(receiver);
						}
						catch (ReflectiveOperationException failure)
						{
							throw new IOException("Cannot bind the native script message receiver", failure);
						}
						handler = registerSignal(handle, "message", callback, FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
					}
					loadAttempted = true;
					checkedVoid("frida_script_load_sync", loadScript, handle);
					loaded = true;
				}

				/**
				 * Returns the next copied JSON message; callback failures remain observable even after close.
				 * Closed receivers discard queued messages and reject later deliveries without dereferencing their arguments.
				 * @return the next message, or empty when none is queued
				 * @throws IllegalStateException if called by another thread
				 * @throws IOException if any native callback failed while copying a message
				 */
				Optional<String> pollMessage() throws IOException
				{
					requireThread();
					return receiver.poll();
				}

				/**
				 * Serializes a JSON tree and sends it to a loaded script without binary data.
				 * @param json JSON value to send, with text escaped by the existing Jackson serializer
				 * @throws NullPointerException if json is null
				 * @throws IllegalStateException if this script or its attachment is inactive
				 */
				void post(JsonNode json)
				{
					Objects.requireNonNull(json, "json may not be null");
					requireSession();
					if (loaded == false || closed || closeFailure != null)
						throw new IllegalStateException("Frida script must be loaded and open");
					try (Arena arena = Arena.ofConfined())
					{
						invoke(post, handle, arena.allocateFrom(json.toString()), MemorySegment.NULL);
					}
				}

				/**
				 * Stops Java delivery, disconnects the handler, unloads a live script and releases owned references.
				 * Callback memory remains retained by the runtime until global shutdown. Failure latches ownership.
				 * @throws IllegalStateException if called by another thread
				 * @throws IOException if native cleanup fails
				 */
				@Override
				public void close() throws IOException
				{
					requireThread();
					if (closed)
						return;
					if (closeFailure != null)
						throw new IOException("Frida script cleanup previously failed; retain its session", closeFailure);
					receiver.stop();
					try
					{
						if (handler != 0)
						{
							disconnectSignal(handle, handler);
							handler = 0;
						}
						if (handle.equals(MemorySegment.NULL) == false)
						{
							if (loadAttempted && (int) invoke(isDestroyed, handle) == 0)
								checkedVoid("frida_script_unload_sync", unloadScript, handle);
							invoke(unref, handle);
							handle = MemorySegment.NULL;
						}
						if (options.equals(MemorySegment.NULL) == false)
						{
							invoke(unref, options);
							options = MemorySegment.NULL;
						}
						scripts.remove(this);
						closed = true;
					}
					catch (IOException failure)
					{
						closeFailure = failure;
						throw failure;
					}
					catch (RuntimeException | Error failure)
					{
						closeFailure = new IOException("Frida script cleanup did not complete; retain its session", failure);
						throw closeFailure;
					}
				}
			}
		}
	}

	/**
	 * Copies a native child event while its borrowed source remains valid.
	 * @param pid unsigned native process identity
	 * @param parentPid unsigned native parent identity
	 * @param origin native FridaChildOrigin value: fork 0, exec 1, spawn 2
	 * @param path copied executable path, or null when the native event provides none
	 */
	record Child(long pid, long parentPid, int origin, String path) {}

	/** Copies child notifications without retaining the manager or borrowed native objects. */
	private static final class ChildReceiver
	{
		/** Verified accessor for the unsigned child process identity. */
		private final MethodHandle pid;
		/** Verified accessor for the unsigned parent process identity. */
		private final MethodHandle parent;
		/** Verified accessor for the native event origin. */
		private final MethodHandle origin;
		/** Verified accessor for the optional borrowed executable path. */
		private final MethodHandle path;
		/** Copied events awaiting the constructing-thread controller. */
		private final ConcurrentLinkedQueue<Child> events = new ConcurrentLinkedQueue<>();
		/** First callback failure; retained after the receiver closes. */
		private final AtomicReference<Throwable> failure = new AtomicReference<>();
		/** Guarded by this receiver's monitor; false rejects borrowed arguments before access. */
		private boolean accepting = true;

		/**
		 * Retains verified native accessors without borrowing the enclosing owner.
		 * @param pid child process identity accessor
		 * @param parent child parent identity accessor
		 * @param origin event origin accessor
		 * @param path optional native path accessor
		 */
		private ChildReceiver(MethodHandle pid, MethodHandle parent, MethodHandle origin, MethodHandle path)
		{
			this.pid = pid;
			this.parent = parent;
			this.origin = origin;
			this.path = path;
		}

		/**
		 * Copies all borrowed fields; Java failures never escape this native callback body.
		 * @param device borrowed native device, unused
		 * @param child borrowed native child valid only during this callback
		 * @param userData unused callback data
		 */
		private void onChild(MemorySegment device, MemorySegment child, MemorySegment userData)
		{
			try
			{
				synchronized (this)
				{
					if (accepting == false)
						return;
					long actualPid = Integer.toUnsignedLong((int) invoke(pid, child));
					long actualParent = Integer.toUnsignedLong((int) invoke(parent, child));
					int actualOrigin = (int) invoke(origin, child);
					MemorySegment actualPath = (MemorySegment) invoke(path, child);
					String copiedPath = null;
					if (actualPath.equals(MemorySegment.NULL) == false)
						copiedPath = string(actualPath);
					events.add(new Child(actualPid, actualParent, actualOrigin, copiedPath));
				}
			}
			catch (Throwable cause)
			{
				failure.compareAndSet(null, cause);
			}
		}

		/**
		 * Returns a copied event after checking retained errors.
		 * @return next event, or empty when none remains
		 * @throws IOException if native event copying failed
		 */
		private Optional<Child> poll() throws IOException
		{
			Throwable cause = failure.get();
			if (cause != null)
				throw new IOException("Native Frida child-event delivery failed", cause);
			return Optional.ofNullable(events.poll());
		}

		/** Waits for any active copy, rejects later callbacks and discards queued events. */
		private synchronized void stop()
		{
			accepting = false;
			events.clear();
		}
	}

	/**
	 * Retains a callback stub until global teardown and registers its native signal handler.
	 * @param object borrowed native object receiving the registration
	 * @param signal native signal name
	 * @param callback bound Java callback with the exact descriptor carrier types
	 * @param descriptor verified native callback signature
	 * @return nonzero native gulong identity bits
	 * @throws IOException if native registration returns zero
	 */
	private long registerSignal(MemorySegment object, String signal, MethodHandle callback, FunctionDescriptor descriptor) throws IOException
	{
		MemorySegment stub = Linker.nativeLinker().upcallStub(callback, descriptor, libraryArena);
		try (Arena arena = Arena.ofConfined())
		{
			Number id = (Number) invoke(connect, object, arena.allocateFrom(signal), stub, MemorySegment.NULL, MemorySegment.NULL, 0);
			long bits;
			if (signalIdLayout.byteSize() == Integer.BYTES)
				bits = Integer.toUnsignedLong(id.intValue());
			else
				bits = id.longValue();
			if (bits == 0)
				throw new IOException("Frida signal registration returned zero: " + signal);
			return bits;
		}
	}

	/**
	 * Disconnects a known registration using its original platform carrier.
	 * @param object native object which owns the handler
	 * @param bits nonzero gulong identity bits returned by registration
	 */
	private void disconnectSignal(MemorySegment object, long bits)
	{
		Object id;
		if (signalIdLayout.byteSize() == Integer.BYTES)
			id = Integer.valueOf((int) bits);
		else
			id = Long.valueOf(bits);
		invoke(disconnect, object, id);
	}

	/**
	 * Requires a positive unsigned 32-bit process identity before native conversion.
	 * @param pid native target identity
	 * @throws IllegalArgumentException if pid is outside 1 through 4294967295
	 */
	private static void requirePid(long pid)
	{
		if (pid < 1 || pid > 0xffff_ffffL)
			throw new IllegalArgumentException("Frida target PID must be between 1 and 4294967295");
	}

	/**
	 * Copies native notifications without retaining their script owner; its stub remains alive until native teardown.
	 */
	private static final class Receiver
	{
		/** Java-owned copied messages pending consumption by the controller thread. */
		private final ConcurrentLinkedQueue<String> messages = new ConcurrentLinkedQueue<>();
		/** First callback failure, retained independently of native ownership cleanup. */
		private final AtomicReference<Throwable> failure = new AtomicReference<>();
		/** Indicates whether copied notifications are still accepted. Guarded by this receiver's monitor. */
		private boolean accepting = true;

		/**
		 * Creates a resource-free receiver whose closure never retains a native owner.
		 */
		private Receiver()
		{
		}

		/**
		 * Copies borrowed JSON while its native callback is active; Java failures never escape the method body.
		 * @param script borrowed native script pointer, unused
		 * @param json borrowed NUL-terminated JSON string
		 * @param data borrowed binary data pointer; non-NULL is unsupported by this JSON-only boundary
		 * @param userData unused native callback data
		 */
		private void onMessage(MemorySegment script, MemorySegment json, MemorySegment data, MemorySegment userData)
		{
			try
			{
				synchronized (this)
				{
					if (accepting == false)
						return;
					if (data.equals(MemorySegment.NULL) == false)
						throw new IllegalStateException("Binary Frida messages are unsupported by the JSON receiver");
					messages.add(string(json));
				}
			}
			catch (Throwable cause)
			{
				failure.compareAndSet(null, cause);
			}
		}

		/**
		 * Returns the next copied message after checking the retained callback failure.
		 * @return the next message, or empty when none is queued
		 * @throws IOException if native delivery previously failed
		 */
		private Optional<String> poll() throws IOException
		{
			Throwable cause = failure.get();
			if (cause != null)
				throw new IOException("Native Frida message delivery failed", cause);
			return Optional.ofNullable(messages.poll());
		}

		/**
		 * Rejects later deliveries and discards copied notifications after any current copy leaves the monitor.
		 */
		private synchronized void stop()
		{
			accepting = false;
			messages.clear();
		}
	}

	/**
	 * Calls a native void operation with a cancellable and GError output, retaining typed errors before disposal.
	 * @param operation native operation name used in diagnostics
	 * @param function verified downcall handle
	 * @param owner native object receiving the operation
	 * @throws NativeFailure if the native operation returns an error
	 */
	private void checkedVoid(String operation, MethodHandle function, MemorySegment owner) throws NativeFailure
	{
		try (Arena arena = Arena.ofConfined())
		{
			MemorySegment error = arena.allocate(ADDRESS);
			invoke(function, owner, MemorySegment.NULL, error);
			throwNativeFailure(operation, error.get(ADDRESS, 0));
		}
	}

	/**
	 * Validates text before passing it to a native C-string operation.
	 * @param text input text
	 * @param description input's diagnostic name
	 * @return the unchanged text
	 * @throws NullPointerException if text is null
	 * @throws IllegalArgumentException if text contains NUL
	 */
	private static String requireCString(String text, String description)
	{
		Objects.requireNonNull(text, description + " may not be null");
		if (text.indexOf('\0') >= 0)
			throw new IllegalArgumentException(description + " must not contain NUL characters");
		return text;
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
