package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/** Explicit opt-in native driver for the actual Linux factory, queue and measurement authority.
 * It requires a disposable privileged Linux host and a root-only impossible-allocation fixture.
 * Merely compiling this class proves no native gate. Lifecycle receipts must be retained with independent observers.
 */
public final class LinuxAttemptProbe
{
	/** Prevents construction of the command entry point. */
	private LinuxAttemptProbe() {}

	/** Runs one authentic blocked allocation then closes the real queue while unfinished.
	 * @param args scratch, Frida library, admission parent, helper parent, cgroup mount, Java, tooling JAR,
	 * fixture, uid, gid, unshare and libcgroup-v3 library
	 * @throws Exception if setup, native behavior or complete cleanup cannot be established
	 */
	public static void main(String[] args) throws Exception
	{
		if (args.length != 12) throw new IllegalArgumentException("Expected twelve explicit native probe inputs");
		Path scratch = absolute(args[0]);
		Path frida = absolute(args[1]);
		Path admission = absolute(args[2]);
		Path helper = absolute(args[3]);
		Path mount = absolute(args[4]);
		Path java = absolute(args[5]);
		Path jar = absolute(args[6]);
		Path fixture = absolute(args[7]);
		int uid = Integer.parseInt(args[8]);
		int gid = Integer.parseInt(args[9]);
		Duration timeout = Duration.ofSeconds(30);
		Path input = Files.createFile(scratch.resolve("stdin"));
		Path output = Files.createFile(scratch.resolve("stdout"));
		Path error = Files.createFile(scratch.resolve("stderr"));
		// Work directory is explicitly prepared by the disposable-host setup for the workload identity.
		Path work = scratch.resolve("work");
		if (Files.isDirectory(work) == false) throw new IOException("Owned workload directory must already exist");
		FridaRuntime runtime = new FridaRuntime(frida);
		LinuxAuthority authority = null;
		LinuxAttempts attempts = null;
		JobQueue<LinuxSupervisor.Result> queue = null;
		Throwable first = null;
		Map<String, Object> receipt = new LinkedHashMap<>();
		try
		{
			runtime.start();
			try
			{
				authority = LinuxAuthority.open(new LinuxAuthority.Settings(java, absolute(args[10]), absolute(args[11]),
					scratch, mount, Path.of("/proc"), admission, helper, timeout));
			}
			catch (LinuxAuthority.AcquisitionFailure cause) { authority = cause.owner(); throw cause; }
			LinuxMeasurements measurements = LinuxMeasurements.open(authority);
			Controller.Memory host = measurements.readHost();
			if (host.availableBytes() < 536870912L) throw new IOException("Native gate requires 512 MiB verified headroom");
			var launch = new LinuxProcessTree.Launch(admission, scratch, java, jar, List.of(fixture.toString()),
				Map.of("PATH", "/usr/bin:/bin"), work, uid, gid, LinuxProcessTree.ExecutionMode.ATTACHED_LEADER_EXEC);
			var command = new LinuxAttempts.Command(launch, new LinuxProcessTree.StandardFiles(input, output, error));
			attempts = new LinuxAttempts(UUID.randomUUID(), runtime, authority, Map.of("fixture", command), timeout);
			queue = new JobQueue<>(new Policy.Budget(268435456L, 1), measurements, attempts);
			queue.submit("fixture", 268435456L);
			long began = System.nanoTime();
			boolean blocked = false;
			while (blocked == false)
			{
				attempts.pumpAll();
				var notification = attempts.pollBlocked();
				while (notification.isPresent())
				{
					var event = notification.get();
					blocked |= queue.allocationBlocked(event.name(), event.attemptSequence(), event.waitSequence());
					notification = attempts.pollBlocked();
				}
				if (blocked) break;
				if (System.nanoTime() - began >= timeout.toNanos()) throw new IOException("No authentic blocked queue event");
				queue.cycle();
				Thread.sleep(10);
			}
			Policy.Job job = queue.jobs().getFirst();
			measurements.readHost();
			long resident = measurements.readResidentBytes(job);
			measurements.acceptReadings();
			receipt.put("job", job);
			receipt.put("residentBytes", resident);
			receipt.put("descendantsBefore", observeDescendants());
			Files.writeString(scratch.resolve("before.json"), JsonMapper.builder().build().writeValueAsString(receipt));
			queue.close();
			if (queue.completedAttempts().isEmpty() == false || attempts.hasOpenAttempts())
				throw new IOException("Unfinished close invented completion or retained an owner");
			receipt.put("completedAfterClose", queue.completedAttempts().size());
			// The next actual measurement batch verifies that the old registration was removed.
			measurements.readHost();
			measurements.acceptReadings();
			var manager = runtime.newManager();
			manager.start();
			manager.close();
			receipt.put("borrowedRuntimeUsable", true);
		}
		catch (Throwable cause) { first = cause; }
		finally
		{
			if (queue != null)
				try { queue.close(); } catch (Throwable cause) { first = combine(first, cause); }
			boolean ownersRemain = attempts != null && attempts.hasOpenAttempts();
			if (ownersRemain == false)
			{
				if (authority != null)
					try { authority.close(); } catch (Throwable cause) { first = combine(first, cause); }
				try { runtime.close(); } catch (Throwable cause) { first = combine(first, cause); }
			}
			else first = combine(first, new IOException("Retained native owners prohibit borrowed runtime shutdown"));
			try { receipt.put("descendantsAfter", observeDescendants()); }
			catch (Throwable cause) { first = combine(first, cause); }
			receipt.put("failure", first == null ? "" : first.toString());
			try { Files.writeString(scratch.resolve("result.json"), JsonMapper.builder().build().writeValueAsString(receipt)); }
			catch (Throwable cause) { first = combine(first, cause); }
		}
		if (first instanceof Error fatal) throw fatal;
		if (first instanceof Exception failure) throw failure;
		if (first != null) throw new IOException("Native probe retained an unclassified failure", first);
		System.out.println("LINUX_ATTEMPT_QUEUE_UNFINISHED_CLOSE_PASSED");
	}

	/** Captures independent proc identity/cgroup observations, not adapter-provided outcomes.
	 * @return descendant identity records
	 * @throws IOException if a retained descendant cannot be observed consistently
	 */
	private static List<Map<String, String>> observeDescendants() throws IOException
	{
		var records = new java.util.ArrayList<Map<String, String>>();
		try (var descendants = ProcessHandle.current().descendants())
		{
			for (ProcessHandle process : descendants.toList())
			{
				Path proc = Path.of("/proc", Long.toString(process.pid()));
				records.add(Map.of("pid", Long.toString(process.pid()), "stat", Files.readString(proc.resolve("stat")),
					"status", Files.readString(proc.resolve("status")), "group", Files.readString(proc.resolve("cgroup")),
					"executable", Files.readSymbolicLink(proc.resolve("exe")).toString()));
			}
		}
		return List.copyOf(records);
	}

	/** Validates a caller-supplied probe boundary.
	 * @param value required absolute normalized path
	 * @return validated path
	 */
	private static Path absolute(String value)
	{
		Path path = Path.of(value);
		if (path.isAbsolute() == false || path.normalize().equals(path) == false)
			throw new IllegalArgumentException("Native probe paths must be absolute and normalized");
		return path;
	}
	/** Preserves the original failure while retaining independent cleanup diagnostics.
	 * @param first original fault, or null
	 * @param later independent cleanup fault
	 * @return retained first fault
	 */
	private static Throwable combine(Throwable first, Throwable later)
	{
		if (first == null) return later;
		if (first != later) first.addSuppressed(later);
		return first;
	}
}
