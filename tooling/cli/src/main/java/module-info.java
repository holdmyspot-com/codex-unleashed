/** Tooling services and explicitly selected optional Bazel Runner transport dependencies. */
module com.holdmyspot.codexunleashed.tooling
{
	requires org.tomlj;
	requires io.github.cowwoc.pouch10.core;
	requires tools.jackson.databind;
	requires tools.jackson.dataformat.yaml;
	requires org.apache.commons.io;
	requires com.github.oshi.ffm;
	// Optional Runner transport is loaded only by the packaged Bazel classpath companion.
	requires static io.grpc;
	requires static io.grpc.stub;
	requires static io.grpc.protobuf;
	requires static io.grpc.netty.shaded;
	requires static com.google.protobuf;
	requires static com.google.common;
	requires static java.annotation;

	// Only the module's own tests see these packages; no other module can read them.
	exports com.holdmyspot.codexunleashed.scheduler to com.holdmyspot.codexunleashed.tooling.test;
	exports com.holdmyspot.codexunleashed.tooling to com.holdmyspot.codexunleashed.tooling.test;
	exports buildbarn.runner to com.holdmyspot.codexunleashed.tooling.test;
}
