package com.holdmyspot.codexunleashed.secrets;

import com.holdmyspot.codexunleashed.scheduler.LinuxAuthority;
import com.holdmyspot.codexunleashed.scheduler.LinuxMeasurements;
import java.io.IOException;
import java.util.Map;
import java.util.Properties;

/**
 * Decides memory-authority replies with controlled retained-owner observations.
 * The class that owns the behavior registers the implementation through {@link LinuxAuthoritySecrets}.
 */
public interface LinuxAuthorityAccess
{
	/**
	 * Exercises the actual reply decision with controlled retained-owner observations.
	 *
	 * @param response acknowledged reply
	 * @param operation request operation
	 * @param barriers registered retained-zero suppliers
	 * @throws IOException if the reply is rejected
	 */
	void requireSuccess(Properties response, String operation,
		Map<LinuxMeasurements.Attempt, LinuxAuthority.ZeroBarrier> barriers) throws IOException;
}
