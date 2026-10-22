/**
 * Build, release, and repository policy tooling.
 */
module com.holdmyspot.codexunleashed.tooling
{
	requires io.github.cowwoc.pouch10.core;
	requires ch.qos.logback.classic;
	requires tools.jackson.databind;
	requires tools.jackson.dataformat.toml;
	requires info.picocli;
	requires java.net.http;
	requires jdk.management;
		requires org.apache.commons.compress;
		requires org.apache.commons.io;

	exports com.holdmyspot.codexunleashed.tooling;
	exports com.holdmyspot.codexunleashed.tooling.release;
}
