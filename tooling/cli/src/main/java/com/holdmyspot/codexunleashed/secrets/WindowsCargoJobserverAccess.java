package com.holdmyspot.codexunleashed.secrets;

import com.holdmyspot.codexunleashed.scheduler.WindowsCargoJobserver;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * Acquires the Windows Cargo jobserver with controlled native outcomes.
 * The class that owns the behavior registers the implementation through {@link WindowsCargoJobserverSecrets}.
 */
public interface WindowsCargoJobserverAccess
{
	/**
	 * Runs normal acquisition with owned external operations after validating capacity.
	 *
	 * @param jobs positive capacity including the implicit slot
	 * @param calls access transferred only after input validation
	 * @return normally owned jobserver
	 * @throws IOException if acquisition or cleanup fails
	 */
	WindowsCargoJobserver create(int jobs, WindowsCargoJobserver.Calls calls) throws IOException;

	/**
	 * Retains controlled native functions through the same production creation and cleanup operations.
	 *
	 * @param arena owned native-access scope
	 * @param errors captured native error storage
	 * @param errorOffset offset of the captured DWORD
	 * @param functions typed native functions that receive the captured-state storage
	 * @return owned access through the production native adapter
	 */
	WindowsCargoJobserver.Calls nativeCalls(Arena arena, MemorySegment errors, long errorOffset,
		WindowsCargoJobserver.NativeFunctions functions);
}
