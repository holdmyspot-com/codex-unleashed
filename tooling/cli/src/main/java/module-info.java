/** Tooling services for source caching, retention previews and upstream action extraction. */
module com.holdmyspot.codexunleashed.tooling
{
	requires org.tomlj;
	requires io.github.cowwoc.pouch10.core;
	requires tools.jackson.databind;
	requires tools.jackson.dataformat.yaml;
	requires org.apache.commons.io;

	// Only the module's own tests see this package; no other module can read it.
	exports com.holdmyspot.codexunleashed.tooling to com.holdmyspot.codexunleashed.tooling.test;
}
