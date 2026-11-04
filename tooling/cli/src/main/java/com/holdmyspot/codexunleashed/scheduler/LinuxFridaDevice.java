package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.util.Objects;

/**
 * Owns the one started Frida device manager of a Linux Cargo build and copies its child-added events into the build's
 * routing.
 * <p>
 * The Cargo leader and every attempt's instrumentation borrow {@link #manager()} for their sessions and continuations,
 * so each held child is reported by exactly one device. This owner's routing registration is only the source of the
 * events it copies; its membership contains no process, so routing never selects it as a controller.
 * <p>
 * Instances are confined to their constructing thread, which must also own the runtime and the routing.
 */
final class LinuxFridaDevice implements AutoCloseable
{
	/**
	 * Most events copied by one {@link #copy()} call, bounding the time one pump spends on device events.
	 */
	private static final int COPY_LIMIT = 64;
	/**
	 * Exclusive controller thread.
	 */
	private final Thread owner = Thread.currentThread();
	/**
	 * Owned started manager, closed by this owner after every borrower closed its sessions.
	 */
	private final FridaRuntime.Manager manager;
	/**
	 * Invocation-owned routing that receives this device's copied events.
	 */
	private final AllocationHooks.Routing routing;
	/**
	 * Source registration naming this device as the sender of copied events.
	 */
	private final AllocationHooks.Routing.Registration source;
	/**
	 * Successful complete cleanup.
	 */
	private boolean closed;
	/**
	 * First cleanup failure, retained so that a repeated close reports it instead of retrying uncertain native cleanup.
	 */
	private IOException closeFailure;

	/**
	 * Retains already acquired owners.
	 * @param manager started manager
	 * @param routing invocation-owned routing
	 * @param source this device's source registration in {@code routing}
	 */
	private LinuxFridaDevice(FridaRuntime.Manager manager, AllocationHooks.Routing routing,
		AllocationHooks.Routing.Registration source)
	{
		this.manager = manager;
		this.routing = routing;
		this.source = source;
	}

	/**
	 * Acquires and starts the build's device manager and registers it as an event source of {@code routing}.
	 * On failure, everything acquired so far is closed and each cleanup failure is suppressed on the original failure.
	 * @param runtime started runtime that retains the manager until it closes
	 * @param routing invocation-owned routing on the calling thread
	 * @return the started device owner
	 * @throws NullPointerException  if an input is null
	 * @throws IllegalStateException if another thread owns the runtime or routing, or the runtime is inactive
	 * @throws IOException           if the native manager cannot be started
	 */
	static LinuxFridaDevice open(FridaRuntime runtime, AllocationHooks.Routing routing) throws IOException
	{
		Objects.requireNonNull(runtime, "runtime");
		Objects.requireNonNull(routing, "routing");
		FridaRuntime.Manager manager = runtime.newManager();
		try
		{
			manager.start();
			AllocationHooks.Routing.Registration source = routing.register(_ -> false, child ->
			{
				throw new AssertionError("A device source owns no process, so routing cannot deliver " + child + " to it");
			});
			return new LinuxFridaDevice(manager, routing, source);
		}
		catch (IOException | RuntimeException | Error failure)
		{
			try
			{
				manager.close();
			}
			catch (IOException | RuntimeException | Error cleanup)
			{
				failure.addSuppressed(cleanup);
			}
			throw failure;
		}
	}

	/**
	 * Returns the manager that the leader and attempts borrow for sessions and continuations.
	 * @return the started manager; borrowers never close it
	 * @throws IllegalStateException if called from another thread
	 */
	FridaRuntime.Manager manager()
	{
		requireOwner();
		return manager;
	}

	/**
	 * Routes a bounded number of copied child-added events from this device to their owning controllers, and releases
	 * each held child that no active owner will continue.
	 * @throws IllegalStateException if called from another thread
	 * @throws IOException           if callback transport failed, routing rejects an event, or a release fails
	 */
	void copy() throws IOException
	{
		requireOwner();
		for (int count = 0; count < COPY_LIMIT; ++count)
		{
			FridaRuntime.Child event = manager.pollChild().orElse(null);
			if (event == null)
				return;
			if (!routing.route(source, event))
				release(event.pid());
		}
	}

	/**
	 * Lets go of a held child that no owner will continue. A running child resumes; its owner's termination stops it.
	 * A child killed while held cannot be detached, so its traced exit is collected instead, which hands it back to its
	 * parent.
	 * @param pid the held child's process ID
	 * @throws IllegalStateException if called from another thread
	 * @throws IOException           if a running child cannot be resumed, or a traced exit cannot be collected
	 */
	void release(long pid) throws IOException
	{
		requireOwner();
		try
		{
			manager.resume(pid);
		}
		catch (IOException resumeFailure)
		{
			if (LinuxTracedZombies.release(pid) || !LinuxTracedZombies.running(pid))
				return;
			throw resumeFailure;
		}
	}

	/**
	 * Closes the manager and then withdraws this device's source registration.
	 * Fails while a borrower still holds a session on the manager. Repeated close after success is a no-op; after a
	 * failure it reports the first failure without retrying.
	 * @throws IllegalStateException if called from another thread
	 * @throws IOException           if sessions remain or native cleanup fails or previously failed
	 */
	@Override
	public void close() throws IOException
	{
		requireOwner();
		if (closeFailure != null)
			throw new IOException("Frida device cleanup remains unresolved", closeFailure);
		if (closed)
			return;
		try
		{
			manager.close();
			source.close();
			closed = true;
		}
		catch (IOException | RuntimeException cause)
		{
			closeFailure = new IOException("Frida device destruction failed", cause);
			throw closeFailure;
		}
		catch (Error cause)
		{
			closeFailure = new IOException("Frida device destruction did not complete", cause);
			throw cause;
		}
	}

	/**
	 * Rejects access from any thread other than the constructing controller.
	 * @throws IllegalStateException if called from another thread
	 */
	private void requireOwner()
	{
		if (Thread.currentThread() != owner)
			throw new IllegalStateException("Frida device belongs to its constructing thread");
	}
}
