package com.holdmyspot.codexunleashed.secrets;

import com.holdmyspot.codexunleashed.scheduler.CargoMemoryEstimates;
import com.holdmyspot.codexunleashed.scheduler.LinuxBorrowedStreams;
import com.holdmyspot.codexunleashed.scheduler.LinuxCargoRegistration;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * Authenticates Linux Cargo registrations with controlled native observations.
 * The class that owns the behavior registers the implementation through {@link LinuxCargoRegistrationSecrets}.
 */
public interface LinuxCargoRegistrationAccess
{
	/**
	 * Constructs the real authentication policy with controlled independent native observations.
	 *
	 * @param configuration validated trusted policy
	 * @param cargo retained Cargo identity
	 * @param observer independent observation source
	 * @return authenticator executing the production policy
	 */
	LinuxCargoRegistration create(LinuxCargoRegistration.Configuration configuration, LinuxBorrowedStreams.Peer cargo,
		LinuxCargoRegistration.Observer observer);

	/**
	 * Exercises ready-image parent composition with independent native observations.
	 *
	 * @param configuration trusted invocation policy
	 * @param parent independently retained ready parent image
	 * @param observer controlled native observation boundary
	 * @return production authenticator for that retained image
	 */
	LinuxCargoRegistration create(LinuxCargoRegistration.Configuration configuration, LinuxCargoRegistration.Parent parent,
		LinuxCargoRegistration.Observer observer);

	/**
	 * Exercises native authentication followed by explicit production profile lookup.
	 *
	 * @param configuration trusted immutable policy
	 * @param cargo retained native Cargo identity
	 * @param observer controlled independent native observations
	 * @param estimates actual owned profile source
	 * @return production authenticator with profile composition
	 */
	LinuxCargoRegistration create(LinuxCargoRegistration.Configuration configuration, LinuxBorrowedStreams.Peer cargo,
		LinuxCargoRegistration.Observer observer, CargoMemoryEstimates estimates);

	/**
	 * Exercises the production native argument reader with a caller-owned bounded fixture stream.
	 *
	 * @param input caller-owned raw argument vector
	 * @return exact decoded argument entries
	 * @throws IOException if native framing or decoding fails
	 */
	List<String> arguments(InputStream input) throws IOException;

	/**
	 * Exercises the production native environment reader with a caller-owned fixture stream.
	 *
	 * @param input caller-owned raw environment vector
	 * @return exact decoded environment
	 * @throws IOException if native framing, names or decoding fails
	 */
	Map<String, String> environment(InputStream input) throws IOException;
}
