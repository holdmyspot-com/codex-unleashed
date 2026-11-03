/** Tests of the tooling module, which reach its packages through exports qualified to this module only. */
module com.holdmyspot.codexunleashed.tooling.test
{
	requires com.holdmyspot.codexunleashed.tooling;
	requires org.testng;
	requires io.github.cowwoc.requirements13.java;
	requires io.github.cowwoc.pouch10.core;
	requires tools.jackson.databind;
	requires tools.jackson.dataformat.yaml;
	requires org.apache.commons.io;
	requires io.grpc;
	requires io.grpc.stub;
	requires io.grpc.netty.shaded;
	requires com.google.protobuf;
	requires com.google.common;
	requires java.net.http;
	requires java.compiler;

	exports com.holdmyspot.codexunleashed.scheduler.test to org.testng;
	exports com.holdmyspot.codexunleashed.tooling.test to org.testng;
	exports com.holdmyspot.codexunleashed.distribution.test to org.testng;
}
