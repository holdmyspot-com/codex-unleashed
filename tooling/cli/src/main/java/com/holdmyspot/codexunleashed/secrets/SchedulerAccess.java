package com.holdmyspot.codexunleashed.secrets;

import com.holdmyspot.codexunleashed.scheduler.Scheduler;
import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import java.io.InputStream;
import java.io.PrintStream;

/**
 * Dispatches scheduler commands with controlled platform and native boundaries.
 * The class that owns the behavior registers the implementation through {@link SchedulerSecrets}.
 */
public interface SchedulerAccess
{
	/**
	 * Runs the production dispatcher with controlled external platform and native execution boundaries.
	 *
	 * @param scope borrowed services
	 * @param arguments scheduler options and tool arguments
	 * @param in borrowed input
	 * @param out borrowed output
	 * @param err borrowed diagnostics
	 * @param platform explicit operating-system observation
	 * @param adapter native execution boundary
	 * @return unchanged native status or production preflight failure status
	 */
	int run(ToolingScope scope, String[] arguments, InputStream in, PrintStream out, PrintStream err, String platform,
		Scheduler.Adapter adapter);
}
