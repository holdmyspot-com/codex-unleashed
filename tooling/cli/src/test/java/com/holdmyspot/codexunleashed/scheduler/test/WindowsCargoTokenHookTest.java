package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.AllocationHook;
import com.holdmyspot.codexunleashed.scheduler.WindowsCargoJobserver;
import com.holdmyspot.codexunleashed.scheduler.WindowsCargoTokenHook;
import com.holdmyspot.codexunleashed.scheduler.WindowsCargoTokens;
import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.secrets.WindowsCargoJobserverSecrets;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import static com.holdmyspot.codexunleashed.scheduler.test.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Checks controller reserve-before-grant and return-before-ack decisions, not native Windows interception. */
public final class WindowsCargoTokenHookTest
{
 /** Creates method-owned token protocol fixtures without acquiring native resources. */
 public WindowsCargoTokenHookTest() {}

 /**
  * Gives immediate empty replies and reserves before grant, allowing cancellation to return exactly that grant.
  * @throws Exception if accounting or framing fails
  */
 @Test public void reserveAndCancelKeepCapacityConserved() throws Exception
 {
  Pool pool = new Pool();
  try (DefaultToolingScope scope = new DefaultToolingScope();
       WindowsCargoJobserver owner = WindowsCargoJobserverSecrets.getWindowsCargoJobserverAccess().create(2, pool);
       WindowsCargoJobserver.CargoLease lease = owner.retainCargo(Map.of());
       WindowsCargoTokens tokens = new WindowsCargoTokens(lease))
  {
   UUID attempt = UUID.randomUUID();
   WindowsCargoTokens.Domain domain = tokens.retain(attempt);
   Connection connection = new Connection(pool);
   WindowsCargoTokenHook hook = new WindowsCargoTokenHook(scope, attempt, 1, 42, "Local\\test", connection,
    domain, "program", Duration.ofSeconds(1), Clock.systemUTC());
   hook.start();
   connection.frames.add(frame(scope, attempt, "ready", 0, null));
   connection.frames.add(frame(scope, attempt, "acquire", 1, null));
   connection.frames.add(frame(scope, attempt, "acquire", 2, null));
   hook.pump();
   requireThat(hook.ready(), "nativeReadyObserved").isTrue();
   requireThat(connection.controls.size(), "immediateReplies").isEqualTo(2, "expected");
   requireThat(connection.controls.get(1).path("outcome").asString(), "emptyReply").isEqualTo("empty", "expected");
   requireThat(domain.outstanding(), "reservedBeforeDelivery").isEqualTo(1, "expected");
   requireThat(connection.capacityAtPost.getFirst(), "actualCapacityBeforeGrantPublication")
    .isEqualTo(0, "expected");
   String grant = connection.controls.getFirst().path("grant").asString();
   connection.frames.add(frame(scope, attempt, "probe", 3, null));
   hook.pump();
   requireThat(connection.controls.getLast().path("available").intValue(), "probeExcludesHeldGrant")
    .isEqualTo(0, "expected");
   connection.frames.add(frame(scope, attempt, "cancel", 4, grant));
   hook.pump();
   requireThat(pool.available, "returnedBeforeCancelAcknowledgment").isEqualTo(1, "expected");
   requireThat(connection.capacityAtPost.get(3), "actualCapacityBeforeCancellationAcknowledgment")
    .isEqualTo(1, "expected");
   requireThat(domain.outstanding(), "cancelledGrants").isEqualTo(0, "expected");
   connection.frames.add(frame(scope, attempt, "probe", 5, null));
   hook.pump();
   requireThat(connection.controls.getLast().path("available").intValue(), "probeRestoresOnlyObservedCapacity")
    .isEqualTo(1, "expected");
   requireThat(pool.available, "probeDoesNotMintOrLoseCapacity").isEqualTo(1, "expected");
   requireException(IOException.class, () -> hook.close(() -> false));
   requireThat(connection.closes, "noNativeCloseBeforeJobZero").isEqualTo(0, "expected");
   hook.close(() -> true);
   requireThat(connection.closes, "nativeCloseAfterJobZero").isEqualTo(1, "expected");
   domain.retire(() -> true);
  }
 }
 /**
  * Rejects replay before another count-changing effect and retains a lost grant until whole-Job retirement.
  * @throws Exception if accounting fails
  */
 @Test public void lostGrantRemainsOwnedUntilJobZero() throws Exception
 {
  Pool pool = new Pool();
  try (DefaultToolingScope scope = new DefaultToolingScope();
       WindowsCargoJobserver owner = WindowsCargoJobserverSecrets.getWindowsCargoJobserverAccess().create(2, pool);
       WindowsCargoJobserver.CargoLease lease = owner.retainCargo(Map.of());
       WindowsCargoTokens tokens = new WindowsCargoTokens(lease))
  {
   UUID attempt = UUID.randomUUID();
   WindowsCargoTokens.Domain domain = tokens.retain(attempt);
   Connection connection = new Connection(pool);
   WindowsCargoTokenHook hook = new WindowsCargoTokenHook(scope, attempt, 1, 42, "Local\\test", connection,
    domain, "program", Duration.ofSeconds(1), Clock.systemUTC());
   hook.start();
   connection.frames.add(frame(scope, attempt, "ready", 0, null));
   connection.frames.add(frame(scope, attempt, "acquire", 1, null));
   connection.failPost = true;
   requireException(IOException.class, hook::pump);
   requireThat(domain.outstanding(), "uncertainGrantRetained").isEqualTo(1, "expected");
   requireException(IOException.class, hook::pump);
   requireThat(connection.posts, "singleGrantPublicationAttempt").isEqualTo(1, "expected");
   hook.close(() -> true);
   domain.retire(() -> true);
   requireThat(pool.available, "wholeJobReclaimed").isEqualTo(1, "expected");
  }
 }

 /**
  * Rejects a duplicate request before another token can be reserved.
  * @throws Exception if accounting fails
  */
 @Test public void replayCannotReserveAgain() throws Exception
 {
  Pool pool = new Pool();
  try (DefaultToolingScope scope = new DefaultToolingScope();
       WindowsCargoJobserver owner = WindowsCargoJobserverSecrets.getWindowsCargoJobserverAccess().create(3, pool);
       WindowsCargoJobserver.CargoLease lease = owner.retainCargo(Map.of());
       WindowsCargoTokens tokens = new WindowsCargoTokens(lease))
  {
   UUID attempt = UUID.randomUUID();
   WindowsCargoTokens.Domain domain = tokens.retain(attempt);
   Connection connection = new Connection(pool);
   WindowsCargoTokenHook hook = new WindowsCargoTokenHook(scope, attempt, 1, 42, "Local\\test", connection,
    domain, "program", Duration.ofSeconds(1), Clock.systemUTC());
   hook.start();
   connection.frames.add(frame(scope, attempt, "ready", 0, null));
   String acquire = frame(scope, attempt, "acquire", 1, null);
   connection.frames.add(acquire);
   connection.frames.add(acquire);
   requireException(IOException.class, hook::pump);
   requireThat(domain.outstanding(), "oneIssuedReservation").isEqualTo(1, "expected");
   requireThat(pool.available, "replayDidNotConsumeRemainingCapacity").isEqualTo(1, "expected");
   hook.close(() -> true);
   domain.retire(() -> true);
  }
 }

 /**
  * Rejects numeric identity representations before readiness can authorize a grant.
  * @throws Exception if accounting fails
  */
 @Test public void numericIdentityCannotAuthorizeNativeImage() throws Exception
 {
  Pool pool = new Pool();
  try (DefaultToolingScope scope = new DefaultToolingScope();
       WindowsCargoJobserver owner = WindowsCargoJobserverSecrets.getWindowsCargoJobserverAccess().create(2, pool);
       WindowsCargoJobserver.CargoLease lease = owner.retainCargo(Map.of());
       WindowsCargoTokens tokens = new WindowsCargoTokens(lease))
  {
   UUID attempt = UUID.randomUUID();
   WindowsCargoTokens.Domain domain = tokens.retain(attempt);
   Connection connection = new Connection(pool);
   WindowsCargoTokenHook hook = new WindowsCargoTokenHook(scope, attempt, 1, 42, "Local\\test", connection,
    domain, "program", Duration.ofSeconds(1), Clock.systemUTC());
   hook.start();
   connection.frames.add(frame(scope, attempt, "ready", 0, null).replace("\"pid\":\"42\"", "\"pid\":42"));
   connection.frames.add(frame(scope, attempt, "acquire", 1, null));
   requireException(IOException.class, hook::pump);
   requireThat(domain.outstanding(), "noUnauthenticatedGrant").isEqualTo(0, "expected");
   hook.close(() -> true);
   domain.retire(() -> true);
  }
 }
 /**
  * Retains a bounded native diagnostic longer than the allocation protocol's scalar limit without authorizing work.
  * @throws Exception if accounting or cleanup fails
  */
 @Test public void longNativeDiagnosticRemainsObservable() throws Exception
 {
  Pool pool = new Pool();
  try (DefaultToolingScope scope = new DefaultToolingScope();
       WindowsCargoJobserver owner = WindowsCargoJobserverSecrets.getWindowsCargoJobserverAccess().create(2, pool);
       WindowsCargoJobserver.CargoLease lease = owner.retainCargo(Map.of());
       WindowsCargoTokens tokens = new WindowsCargoTokens(lease))
  {
   UUID attempt = UUID.randomUUID();
   WindowsCargoTokens.Domain domain = tokens.retain(attempt);
   Connection connection = new Connection(pool);
   String diagnostic = "native-error-" + "s".repeat(164);
   WindowsCargoTokenHook hook = new WindowsCargoTokenHook(scope, attempt, 1, 42, "Local\\" + "s".repeat(164),
    connection, domain, "program", Duration.ofSeconds(1), Clock.systemUTC());
   hook.start();
   connection.frames.add(scope.getAllocationEnvelopeMapper().createObjectNode().put("type", "error")
    .put("description", diagnostic).toString());
   IOException failure = requireException(IOException.class, hook::pump);
   StringBuilder messages = new StringBuilder();
   for (Throwable cause = failure; cause != null; cause = cause.getCause()) messages.append(cause.getMessage());
   requireThat(messages.toString().contains(diagnostic), "completeBoundedNativeDiagnosticRetained").isTrue();
   requireThat(domain.outstanding(), "diagnosticCannotAuthorizeWork").isEqualTo(0, "expected");
   hook.close(() -> true);
   domain.retire(() -> true);
  }
 }
 /**
  * Keeps long token diagnostics local while preserving duplicate and trailing-document rejection.
  * @throws Exception if valid bounded parsing fails
  */
 @Test public void tokenParserHasIndependentStrictBounds() throws Exception
 {
  try (DefaultToolingScope allocation = new DefaultToolingScope();
       WindowsCargoTokenHook.MessageScope messages = new WindowsCargoTokenHook.MessageScope())
  {
   String value = "s".repeat(164);
   String frame = "{\"semaphore\":\"" + value + "\"}";
   requireThat(messages.json().readTree(frame).path("semaphore").asString(), "completeSemaphoreValue")
    .isEqualTo(value);
   requireThat(messages.json() == messages.json(), "oneParserPerOwnedScope").isTrue();
   requireException(tools.jackson.core.JacksonException.class, () -> allocation.getAllocationMessageMapper().readTree(frame));
   requireException(tools.jackson.core.JacksonException.class, () -> messages.json().readTree("{\"a\":1,\"a\":2}"));
   requireException(tools.jackson.core.JacksonException.class, () -> messages.json().readTree("{} {}"));
   requireException(tools.jackson.core.JacksonException.class, () -> messages.json().readTree("[[[[[0]]]]]"));
   requireException(tools.jackson.core.JacksonException.class,
    () -> messages.json().readTree("{\"n\":123456789012345678901}"));
  }
 }

 /**
  * Rejects malformed envelopes, duplicate members, trailing documents and wrong identity types before authorization.
  * @throws Exception if fixture cleanup fails
  */
 @Test public void tokenFramingRemainsStrict() throws Exception
 {
  for (String form : List.of("duplicate", "trailing", "numeric", "envelope", "oversize"))
  {
   Pool pool = new Pool();
   try (DefaultToolingScope scope = new DefaultToolingScope();
        WindowsCargoJobserver owner = WindowsCargoJobserverSecrets.getWindowsCargoJobserverAccess().create(2, pool);
        WindowsCargoJobserver.CargoLease lease = owner.retainCargo(Map.of());
        WindowsCargoTokens tokens = new WindowsCargoTokens(lease))
   {
    UUID attempt = UUID.randomUUID();
    WindowsCargoTokens.Domain domain = tokens.retain(attempt);
    Connection connection = new Connection(pool);
    WindowsCargoTokenHook hook = new WindowsCargoTokenHook(scope, attempt, 1, 42, "Local\\test", connection,
     domain, "program", Duration.ofSeconds(1), Clock.systemUTC());
    hook.start();
    String valid = frame(scope, attempt, "ready", 0, null);
    String malformed = switch (form)
    {
     case "duplicate" -> valid.replace("\"version\":1", "\"version\":1,\"version\":1");
     case "trailing" -> valid + " {}";
     case "numeric" -> valid.replace("\"pid\":\"42\"", "\"pid\":42");
     case "envelope" -> valid.replace("\"type\":\"send\"", "\"type\":1");
     case "oversize" -> " ".repeat(8193);
     default -> throw new IllegalArgumentException("Unknown test form");
    };
    connection.frames.add(malformed);
    requireException(IOException.class, hook::pump);
    requireThat(hook.ready(), "malformedFrameCannotAuthorizeImage").isFalse();
    requireThat(domain.outstanding(), "malformedFrameCannotReserveToken").isEqualTo(0, "expected");
    hook.close(() -> true);
    domain.retire(() -> true);
   }
  }
 }
 /**
  * Encodes copied Frida send frames for this exact installation.
  * @param scope parser owner
  * @param attempt workload identity
  * @param event token operation
  * @param sequence strictly ordered request, zero only for readiness
  * @param grant optional grant being returned
  * @return encoded native envelope
  */
 private static String frame(DefaultToolingScope scope, UUID attempt, String event, long sequence, String grant)
 {
  tools.jackson.databind.node.ObjectNode payload = scope.getAllocationMessageMapper().createObjectNode()
   .put("protocol", "codex-windows-cargo-tokens").put("version", 1).put("attempt", attempt.toString())
   .put("installation", "1").put("pid", "42").put("event", event);
  if (sequence > 0) payload.put("sequence", Long.toString(sequence));
  if (grant != null) payload.put("grant", grant);
  return scope.getAllocationMessageMapper().createObjectNode().put("type", "send").set("payload", payload).toString();
 }
 /** Models copied delivery and uncertain post, with no native process claims. */
 private static final class Connection implements AllocationHook.Connection
 {
  /** Copied native frames awaiting controller service. */
  private final ArrayDeque<String> frames = new ArrayDeque<>();
  /** Generated controls accepted by the modeled transport. */
  private final List<JsonNode> controls = new ArrayList<>();
  /** Modeled actual semaphore state observed at each publication boundary. */
  private final List<Integer> capacityAtPost = new ArrayList<>();
  /** Borrowed test-local semaphore model. */
  private final Pool pool;
  /** Number of native destruction attempts. */
  private int closes;
  /** Number of publication attempts. */
  private int posts;
  /** Requests an uncertain publication failure. */
  private boolean failPost;
  /**
   * Creates an isolated publication observer.
   * @param pool modeled semaphore state, inspected only at actual production publication calls
   */
  private Connection(Pool pool) { this.pool = pool; }
  @Override public void start(String source) { }
  @Override public Optional<String> pollMessage() { return Optional.ofNullable(frames.pollFirst()); }
  @Override public void post(JsonNode control) throws IOException
  {
   ++posts;
   capacityAtPost.add(pool.available);
   if (failPost) throw new IOException("Controlled uncertain post");
   controls.add(control);
  }
  @Override public void close() { ++closes; }
 }
 /** Models documented atomic zero-wait semaphore effects. */
 private static final class Pool implements WindowsCargoJobserver.Calls
 {
  /** Available explicit tokens. */
  private int available;
  /** Creates an isolated pool. */
  private Pool() { }
  @Override public boolean create(String name, int initial, int maximum, Consumer<MemorySegment> acquired)
  { available = initial; acquired.accept(MemorySegment.ofAddress(42)); return false; }
  @Override public boolean tryAcquire(MemorySegment handle)
  { if (available == 0) return false; --available; return true; }
  @Override public void release(MemorySegment handle, int count) { available += count; }
  @Override public void closeHandle(MemorySegment handle) { }
  @Override public void close() { }
 }
}
