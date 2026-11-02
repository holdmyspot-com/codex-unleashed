package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Generates the {@code make} front end that lets GNU Make before 4.4 join a jobserver that Cargo knows by FIFO path.
 * <p>
 * Cargo's jobserver crate configures children without a pre-exec hook only for a {@code fifo:} auth value; numeric
 * descriptors make it add a hook that forces fork, and concurrent forks under Frida child gating abort the forking
 * process. Make before 4.4 accepts only numeric descriptors. The front end, first on the lease's {@code PATH},
 * replaces the FIFO auth value in the make variables with fixed descriptor numbers and executes the real make.
 */
final class CargoMakeFrontEnd
{
	/** Characters that the generated script carries inside single quotes without escaping. */
	private static final String SAFE_WORD = "[A-Za-z0-9_./:+=,@%-]+";

	/**
	 * Prevents construction.
	 */
	private CargoMakeFrontEnd() { }

	/**
	 * Finds the make that a build script running {@code make} would execute.
	 * @param search colon-separated executable search path
	 * @return real path of the first executable {@code make}, or null when none exists
	 * @throws NullPointerException if {@code search} is null
	 * @throws IOException if a found make cannot be resolved
	 */
	static Path findMake(String search) throws IOException
	{
		for (String entry : Objects.requireNonNull(search, "search").split(":", -1))
		{
			if (!entry.startsWith("/"))
				continue;
			Path candidate = Path.of(entry, "make");
			if (Files.isRegularFile(candidate) && Files.isExecutable(candidate))
				return candidate.toRealPath();
		}
		return null;
	}

	/**
	 * Returns the front-end script.
	 * @param make real path of the make to execute
	 * @param fifo absolute FIFO path that the make variables name as {@code fifo:PATH}
	 * @param read descriptor number that replaces the FIFO path for token reads
	 * @param write descriptor number that replaces the FIFO path for token writes
	 * @param openDescriptors true to open the FIFO onto both descriptor numbers before executing make, which requires
	 *   Bash; false when a trusted leader has already opened them, which works with any POSIX shell
	 * @return complete script text
	 * @throws NullPointerException if a path is null
	 * @throws IllegalArgumentException if a path contains characters the script cannot carry
	 */
	static String script(Path make, Path fifo, int read, int write, boolean openDescriptors)
	{
		String auth = "fifo:" + Objects.requireNonNull(fifo, "fifo");
		for (String word : new String[] {Objects.requireNonNull(make, "make").toString(), auth})
			if (!word.matches(SAFE_WORD))
				throw new IllegalArgumentException("Make front end cannot quote " + word);
		String open;
		if (openDescriptors)
			open = "exec " + read + "<>'" + fifo + "' " + write + "<>'" + fifo + "'\n";
		else
			open = "";
		return """
			#!%5$s
			# Owned by one Cargo jobserver lease. GNU Make before 4.4 reads jobserver descriptors, not a FIFO path.
			replace() {
			  rest=$1 result=
			  while :; do
			    case $rest in
			      *'%1$s'*) result=$result${rest%%%%'%1$s'*}'%2$s'; rest=${rest#*'%1$s'} ;;
			      *) break ;;
			    esac
			  done
			  printf '%%s' "$result$rest"
			}
			for name in CARGO_MAKEFLAGS MAKEFLAGS MFLAGS; do
			  eval "set=\\${$name+x} value=\\${$name-}"
			  if [ -n "$set" ]; then
			    value=$(replace "$value")
			    eval "$name=\\$value"
			    export "$name"
			  fi
			done
			%4$sexec '%3$s' "$@"
			""".formatted(auth, read + "," + write, make, open, openDescriptors ? "/bin/bash" : "/bin/sh");
	}
}
