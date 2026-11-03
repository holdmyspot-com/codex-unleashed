package com.holdmyspot.codexunleashed.secrets;

import com.holdmyspot.codexunleashed.scheduler.LinuxAuthorityReader;
import com.holdmyspot.codexunleashed.scheduler.LinuxMeasurements;
import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * Exercises the memory-authority reader decisions without granting native authority.
 * The class that owns the behavior registers the implementation through {@link LinuxAuthorityReaderSecrets}.
 */
public interface LinuxAuthorityReaderAccess
{
	/**
	 * Exercises real directory enumeration and native leaf acquisition without granting memory authority.
	 * This fixture boundary checks topology and native absence only; retained cgroup identities and global
	 * authority remain independently required by the production observation path.
	 *
	 * @param scope invocation-owned native bindings
	 * @param root retained filesystem root for the isolated directory fixture
	 * @param directory expected outer workload directory below root
	 * @param leaf expected workload leaf below directory
	 * @return true only for the exact missing-leaf topology with independently verified native absence
	 * @throws IOException if enumeration, acquisition, cleanup or another topology check fails
	 */
	boolean requiresLeafRecheck(ToolingScope scope, Path root, Path directory, Path leaf) throws IOException;

	/**
	 * Exercises the production all-workload verification decision.
	 *
	 * @param checks controlled workload observations
	 * @throws IOException if any required observation fails
	 */
	void verifyWorkloads(List<LinuxAuthorityReader.Check> checks) throws IOException;

	/**
	 * Exercises global checks around the complete workload observation decision.
	 *
	 * @param context global authority check
	 * @param checks complete controlled workload observations
	 * @throws IOException if authority or a registered observation is unavailable
	 */
	void verifyObservation(LinuxAuthorityReader.Check context, List<LinuxAuthorityReader.Check> checks) throws IOException;

	/**
	 * Models the already-verified missing-leaf outcome, not native absence detection.
	 *
	 * @param attempt registered action identity
	 * @return modeled reader outcome
	 */
	IOException removedLeaf(LinuxMeasurements.Attempt attempt);

	/**
	 * Exercises native-failure recovery through the production complete-verification boundary.
	 *
	 * @param operation requested reader operation
	 * @param errno controlled native operation failure
	 * @param cleanup whether native cleanup also failed and makes recovery unsafe
	 * @param verification complete controlled authority verification
	 * @return original native failure or verified removed-leaf rejection
	 * @throws IOException if the complete authority verification fails differently
	 */
	IOException observationFailure(String operation, int errno, boolean cleanup,
		LinuxAuthorityReader.Check verification) throws IOException;

	/**
	 * Exercises observation-failure classification using the original Java or native failure.
	 *
	 * @param operation requested reader operation
	 * @param failure original observation failure, including uncertain cleanup
	 * @param verification complete controlled authority verification
	 * @return original failure or verified removed-leaf rejection
	 * @throws IOException if the complete authority verification fails differently
	 */
	IOException observationFailure(String operation, IOException failure,
		LinuxAuthorityReader.Check verification) throws IOException;

	/**
	 * Exercises the actual rejection serializer after controlled workload checks.
	 *
	 * @param failure observation result to serialize
	 * @return structured failed reply
	 */
	Properties rejection(IOException failure);

	/**
	 * Selects public open flags without acquiring native resources.
	 *
	 * @param architecture supported JVM architecture name
	 * @param directory whether a directory is required
	 * @return architecture-specific flags
	 * @throws UnsupportedOperationException if this architecture is unsupported
	 */
	int openFlags(String architecture, boolean directory);

	/**
	 * Runs the actual held-directory classifier on one owned descriptor; it never clones or changes a mount.
	 *
	 * @param scope invocation-owned shared values
	 * @param directory directory whose actual-root evidence is being checked
	 * @throws IOException if this directory cannot establish actual-root evidence
	 */
	void requireActualRoot(ToolingScope scope, Path directory) throws IOException;
}
