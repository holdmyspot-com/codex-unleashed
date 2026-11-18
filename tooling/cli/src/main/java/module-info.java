/** Tooling services for source caching, retention previews and upstream action extraction. */
module com.holdmyspot.codexunleashed.tooling
{
	requires java.xml;
	requires io.github.cowwoc.requirements13.java;
	requires tools.jackson.dataformat.toml;
	requires io.github.cowwoc.pouch10.core;
	requires tools.jackson.databind;
	requires tools.jackson.dataformat.yaml;
	requires org.apache.commons.io;
	requires org.apache.commons.compress;
	requires io.github.javadiffutils;

	// Only the module's own tests see this package; no other module can read it.
	exports com.holdmyspot.codexunleashed.tooling to com.holdmyspot.codexunleashed.tooling.test;
}
