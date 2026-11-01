package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.LockSupport;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Owns one authenticated Windows image's controller token mediation on its constructing thread.
 * The caller proves image birth, Job membership and the selected pool before installing this owner and holds the
 * image until readiness. Each acquire receives an immediate grant or empty reply: capacity waits stay native and
 * cannot prevent another image's return from being serviced. Definite reservations precede publication; cancellation
 * returns the identified reservation before acknowledgment. A lost grant remains owned until whole-Job retirement.
 * Closing this connection never retires its borrowed Job domain. This class does not authenticate a raw PID itself.
 */
final class WindowsCargoTokenHook implements WindowsAllocationHooks.Hook
{
 /** Namespace used by both the packaged native producer and controller controls. */
 private static final String PROTOCOL = "codex-windows-cargo-tokens";
 /** Packaged source path relative to this class. */
 private static final String RESOURCE = "windows-cargo-token-hook.js";
 /** Bounded copied-envelope size, in Java characters. */
 private static final int MAX_FRAME = 8192;
 /** Fair maximum requests serviced per owner-thread pump. */
 private static final int PUMP_BUDGET = 64;
 /** Required identity fields in every native event. */
 private static final Set<String> IDENTITY_FIELDS = Set.of("protocol", "version", "attempt", "installation", "pid", "event");
 /** Thread owning all connection and ledger transitions. */
 private final Thread controller = Thread.currentThread();
 /** Owned token-local Pouch values, retained through native connection destruction. */
 private final MessageScope messages = new MessageScope();
 /** Borrowed strict token-local scope-owned protocol mapper. */
 private final JsonMapper json;
 /** Authenticated whole-Job attempt identity. */
 private final UUID attempt;
 /** Never-reused positive installation generation. */
 private final long installation;
 /** Authenticated native process ID. */
 private final long pid;
 /** Owned resource-free connection, retained before start. */
 private final AllocationHook.Connection connection;
 /** Borrowed whole-Job ledger, retained after image destruction. */
 private final WindowsCargoTokens.Domain tokens;
 /** Grants issued by this exact image, excluding returned/cancelled grants. */
 private final Set<UUID> grants = new HashSet<>();
 /** Positive readiness deadline budget supplied by invocation composition. */
 private final Duration timeout;
 /** Readiness clock supplied at the composition boundary. */
 private final Clock clock;
 /** Indicates whether control authority was withdrawn permanently. */
 private boolean stopped = false;
 /** Identity-initialized complete native program. */
 private final String source;
 /** Indicates whether native start has been attempted once. */
 private boolean startAttempted = false;
 /** Indicates whether native start returned successfully. */
 private boolean started = false;
 /** Indicates whether this exact installation reported readiness. */
 private boolean ready = false;
 /** Last accepted request sequence; zero precedes the first request. */
 private long sequence = 0;
 /** Indicates whether native cleanup succeeded. */
 private boolean closed = false;
 /** First operational failure, retained without replaying controls. */
 private IOException failure = null;
 /** First native destruction failure, retained without replaying close. */
 private IOException closeFailure = null;

 /**
  * Creates a resource-free installation borrowing whole-Job accounting.
  * @param scope invocation scope, required open at construction
  * @param attempt authenticated Job identity
  * @param installation never-reused positive image generation
  * @param pid authenticated live unsigned-DWORD image PID
  * @param semaphore invocation's nonempty named semaphore, without NUL
  * @param connection owned resource-free connection, transferred only on successful construction
  * @param tokens borrowed whole-Job token domain
  * @param program packaged native source without NUL
  * @param timeout positive readiness deadline budget
  * @param clock readiness clock
  * @throws NullPointerException if a reference input is null
  * @throws IllegalArgumentException if identity, semaphore name or program is invalid, or the positive readiness
  * duration cannot fit signed nanoseconds
  */
 WindowsCargoTokenHook(ToolingScope scope, UUID attempt, long installation, long pid, String semaphore,
  AllocationHook.Connection connection, WindowsCargoTokens.Domain tokens, String program, Duration timeout, Clock clock)
 {
  Objects.requireNonNull(scope, "scope may not be null");
  this.attempt = Objects.requireNonNull(attempt, "attempt may not be null");
  this.connection = Objects.requireNonNull(connection, "connection may not be null");
  this.tokens = Objects.requireNonNull(tokens, "tokens may not be null");
  Objects.requireNonNull(semaphore, "semaphore may not be null");
  Objects.requireNonNull(program, "program may not be null");
  if (installation <= 0 || pid <= 0 || pid > 4294967295L)
   throw new IllegalArgumentException("Installation must be positive and PID must be from 1 through 4294967295");
  if (semaphore.isEmpty() || semaphore.indexOf('\0') >= 0 || program.indexOf('\0') >= 0)
   throw new IllegalArgumentException("Semaphore must be nonempty and semaphore/program must contain no NUL");
  this.installation = installation;
  this.pid = pid;
  this.timeout = Objects.requireNonNull(timeout, "timeout may not be null");
  this.clock = Objects.requireNonNull(clock, "clock may not be null");
  if (timeout.isNegative() || timeout.isZero())
   throw new IllegalArgumentException("Windows Cargo token readiness duration must be positive");
  try { timeout.toNanos(); }
  catch (ArithmeticException cause)
  {
   throw new IllegalArgumentException("Windows Cargo token readiness duration must fit signed nanoseconds", cause);
  }
  if (scope.isClosed()) throw new IllegalStateException("Invocation scope is closed");
  this.json = messages.json();
  this.source = "const windowsCargoTokenIdentity = " + json.createObjectNode().put("attempt", attempt.toString())
   .put("installation", Long.toString(installation)).put("pid", Long.toString(pid)).put("semaphore", semaphore) +
   ";\n" + program;
 }

 /**
  * Creates an invocation-bound resource-free image factory using each image's original manager.
  * Every returned owner must become ready before that manager resumes its image.
  * @param scope open invocation scope
  * @param attempt whole-Job identity
  * @param semaphore invocation pool name
  * @param tokens whole-Job domain retained across image replacement and exit
  * @param timeout positive readiness budget
  * @return factory for controller-owned script installations
  * @throws NullPointerException if any input is null
  * @throws IllegalArgumentException if the name is empty/contains NUL or the duration is nonpositive or cannot fit signed nanoseconds
  * @throws IOException if the packaged program cannot be read
  */
 static WindowsAllocationHooks.ImageHooks factory(ToolingScope scope, UUID attempt, String semaphore,
  WindowsCargoTokens.Domain tokens, Duration timeout) throws IOException
 {
  Objects.requireNonNull(scope, "scope may not be null");
  Objects.requireNonNull(attempt, "attempt may not be null");
  Objects.requireNonNull(semaphore, "semaphore may not be null");
  Objects.requireNonNull(tokens, "tokens may not be null");
  Objects.requireNonNull(timeout, "timeout may not be null");
  if (semaphore.isEmpty() || semaphore.indexOf('\0') >= 0 || timeout.isNegative() || timeout.isZero())
   throw new IllegalArgumentException("Semaphore must be nonempty without NUL and readiness duration must be positive");
  try { timeout.toNanos(); }
  catch (ArithmeticException cause)
  {
   throw new IllegalArgumentException("Windows Cargo token readiness duration must fit signed nanoseconds", cause);
  }
  String program = program();
  return (installation, pid, manager) -> new WindowsCargoTokenHook(scope, attempt, installation, pid, semaphore,
   AllocationHook.newNativeConnection(manager, pid), tokens, program, timeout, Clock.systemUTC());
 }

 /**
  * Awaits exact native readiness while servicing copied frames and peer images.
  * @param peers owner-thread peer pump, including held descendants
  * @throws NullPointerException if peers is null
  * @throws IllegalStateException if not started or accessed off-thread
  * @throws IOException if peer service, framing, interruption or the deadline fails
  */
 @Override public void awaitReady(AllocationHook.Pump peers) throws IOException
 {
  Objects.requireNonNull(peers, "peers may not be null");
  requireAuthority();
  Instant deadline = clock.instant().plus(timeout);
  while (!ready)
  {
   pump();
   peers.pump();
   requireAuthority();
   if (ready) return;
   if (Thread.currentThread().isInterrupted())
    throw fail("Windows Cargo token readiness interrupted", new IOException("Controller thread interrupted"));
   if (!clock.instant().isBefore(deadline))
    throw fail("Windows Cargo token readiness deadline exceeded", new IOException("Native ready frame not observed"));
   LockSupport.parkNanos(1_000_000);
  }
 }

 /**
  * Rejects allocation grants because only the composed allocation protocol owns that operation.
  * @param wait observed allocation wait
  * @param peers peer pump
  * @throws UnsupportedOperationException always; use the composed allocation owner
  */
 @Override public void grant(AllocationWaits.Wait wait, AllocationHook.Pump peers)
 {
  throw new UnsupportedOperationException("Windows token hooks do not grant allocation waits");
 }

 /** Permanently withdraws control authority without returning tokens before whole-Job quiescence. */
 @Override public void stop() { requireController(); stopped = true; }

 /**
  * Reads the packaged native mediation program without acquiring a process attachment.
  * @return complete native source
  * @throws IOException if the packaged program is missing or unreadable
  */
 static String program() throws IOException
 {
  try (InputStream input = WindowsCargoTokenHook.class.getResourceAsStream(RESOURCE))
  {
   if (input == null) throw new IOException("Packaged Windows Cargo token hook is missing: " + RESOURCE);
   return new String(input.readAllBytes(), StandardCharsets.UTF_8);
  }
 }

 /**
  * Starts this connection once, retaining partial acquisition after failure.
  * @throws IllegalStateException if started before or accessed off-thread
  * @throws IOException if native startup fails
  */
 @Override public void start() throws IOException
 {
  requireAuthority();
  if (startAttempted) throw new IllegalStateException("Windows Cargo token hook start may be attempted once");
  startAttempted = true;
  try { connection.start(source); started = true; }
  catch (IOException | RuntimeException | Error cause) { throw fail("Windows Cargo token start failed", cause); }
 }

 /**
  * Services copied requests with immediate grant/empty replies and never waits for capacity.
  * Identity, exact object shape and consecutive sequence checks precede every ledger effect. A malformed frame,
  * replay or uncertain post poisons this image; already reserved tokens remain in its whole-Job domain.
  * @throws IllegalStateException if not started or accessed off-thread
  * @throws IOException if protocol or native control fails
  */
 @Override public void pump() throws IOException
 {
  requireAuthority();
  if (!started) throw new IllegalStateException("Windows Cargo token hook has not started");
  try
  {
   for (int i = 0; i < PUMP_BUDGET; ++i)
   {
    String frame = connection.pollMessage().orElse(null);
    if (frame == null) return;
    accept(frame);
   }
  }
  catch (IOException | RuntimeException | Error cause) { throw fail("Windows Cargo token delivery failed", cause); }
 }

 /**
  * Validates and applies one exact copied native event.
  * @param frame complete Frida envelope
  * @throws IOException if shape, identity, ordering or a ledger operation fails
  */
 private void accept(String frame) throws IOException
 {
  if (frame.length() > MAX_FRAME) throw new IOException("Windows Cargo token frame exceeds " + MAX_FRAME);
  JsonNode envelope;
  try { envelope = json.readTree(frame); }
  catch (JacksonException cause) { throw new IOException("Invalid Windows Cargo token JSON", cause); }
  if (envelope != null && envelope.isObject() && envelope.path("type").isString() &&
   "error".equals(envelope.path("type").asString()))
   throw new IOException("Windows Cargo token native script failed: " + frame);
  exact(envelope, Set.of("type", "payload"));
  if (!envelope.path("type").isString() || !"send".equals(envelope.path("type").asString()))
   throw new IOException("Windows Cargo token envelope is not a native send event");
  JsonNode event = envelope.path("payload");
  String operation = event.path("event").asString();
  Set<String> fields = new HashSet<>(IDENTITY_FIELDS);
  if (!"ready".equals(operation)) fields.add("sequence");
  boolean returnsGrant = "return".equals(operation) || "cancel".equals(operation);
  if (returnsGrant) fields.add("grant");
  exact(event, fields);
  for (String field : fields)
   if (!"version".equals(field) && !event.path(field).isString())
    throw new IOException("Windows Cargo token member must be a string: " + field);
  boolean matching = PROTOCOL.equals(event.path("protocol").asString()) &&
   event.path("version").isIntegralNumber() && "1".equals(event.path("version").toString()) &&
   attempt.toString().equals(event.path("attempt").asString()) &&
   Long.toString(installation).equals(event.path("installation").asString()) &&
   Long.toString(pid).equals(event.path("pid").asString());
  if (!matching) throw new IOException("Windows Cargo token event identity differs from this installation");
  if ("ready".equals(operation))
  {
   if (ready) throw new IOException("Windows Cargo token readiness was repeated");
   ready = true;
   return;
  }
  if (!ready) throw new IOException("Windows Cargo token request preceded readiness");
  if (sequence == Long.MAX_VALUE) throw new IOException("Windows Cargo token request sequence exhausted");
  String next = Long.toString(sequence + 1);
  if (!next.equals(event.path("sequence").asString()))
   throw new IOException("Windows Cargo token request sequence is not consecutive");
  ++sequence;
  ObjectNode control = json.createObjectNode().put("type", "windows-cargo-token-control-" + sequence)
   .put("protocol", PROTOCOL).put("version", 1).put("attempt", attempt.toString())
   .put("installation", Long.toString(installation)).put("pid", Long.toString(pid))
   .put("sequence", Long.toString(sequence)).put("operation", operation);
  if ("acquire".equals(operation))
  {
   UUID grant = UUID.randomUUID();
   if (tokens.tryReserve(grant))
   {
    grants.add(grant);
    control.put("outcome", "grant").put("grant", grant.toString());
   }
   else control.put("outcome", "empty");
  }
  else if ("probe".equals(operation)) control.put("outcome", "available").put("available", tokens.available());
  else if (returnsGrant)
  {
   UUID grant;
   try { grant = UUID.fromString(event.path("grant").asString()); }
   catch (IllegalArgumentException cause) { throw new IOException("Invalid Windows Cargo token grant identity", cause); }
   if (!grant.toString().equals(event.path("grant").asString()) || !grants.contains(grant))
    throw new IOException("Windows Cargo token grant does not belong to this image");
   tokens.returnToken(grant);
   grants.remove(grant);
   control.put("outcome", "ack").put("grant", grant.toString());
  }
  else throw new IOException("Unsupported Windows Cargo token operation: " + operation);
  connection.post(control);
 }

 /**
  * Requires precisely the supplied JSON members.
  * @param node candidate object
  * @param fields complete allowed and required members
  * @throws IOException if shape or members differ
  */
 private static void exact(JsonNode node, Set<String> fields) throws IOException
 {
  if (node == null || !node.isObject() || !Set.copyOf(node.propertyNames()).equals(fields))
   throw new IOException("Windows Cargo token object has missing or unexpected members");
 }

 /**
  * Indicates whether authentic readiness was observed for this installation.
  * @return true after exact readiness, including when a later operation fails
  * @throws IllegalStateException if accessed off-thread
  */
 boolean ready() { requireController(); return ready; }

 /**
  * Destroys native ownership only after the complete Job cannot run; never retires the borrowed ledger.
  * A failed destruction is retained and not retried. An operational failure remains observable through pump.
  * @param barrier trusted whole-Job execution barrier
  * @throws NullPointerException if barrier is null
  * @throws IllegalStateException if accessed off-thread
  * @throws IOException if the barrier is false/unknown or native cleanup fails
  */
 @Override public void close(AllocationHook.ExecutionBarrier barrier) throws IOException
 {
  Objects.requireNonNull(barrier, "barrier may not be null");
  requireController();
  if (closed) return;
  if (closeFailure != null) throw closeFailure;
  stop();
  if (!barrier.reached()) throw new IOException("Windows Cargo token destruction requires whole-Job quiescence");
  try { connection.close(); messages.close(); closed = true; }
  catch (IOException | RuntimeException | Error cause)
  {
   closeFailure = new IOException("Windows Cargo token native destruction failed", cause);
   throw closeFailure;
  }
 }

 /**
  * Retains the first failure without permitting another native effect.
  * @param message failing operation
  * @param cause original outcome
  * @return sticky first failure
  */
 private IOException fail(String message, Throwable cause)
 {
  if (failure == null) failure = new IOException(message, cause);
  return failure;
 }

 /**
  * Checks this installation before another effect.
  * @throws IllegalStateException if closed or called off-thread
  * @throws IOException if an earlier operation failed
  */
 private void requireAuthority() throws IOException
 {
  requireController();
  if (failure != null) throw failure;
  if (closed || stopped) throw new IllegalStateException("Windows Cargo token hook is stopped or closed");
 }

 /**
  * Rejects cross-thread access before external effects.
  * @throws IllegalStateException if called off-thread
  */
 private void requireController()
 {
  if (Thread.currentThread() != controller)
   throw new IllegalStateException("Windows Cargo token hook requires the constructing controller thread");
 }

 /**
  * Owns the token parser separately from the allocation protocol's short scalar parser.
  * Complete copied frames are bounded to 8192 UTF-16 characters before parsing. A diagnostic string may use that
  * complete budget; authenticated token fields are subsequently constrained by exact schema and identity checks.
  */
 static final class MessageScope extends io.github.cowwoc.pouch10.core.AbstractScope
 {
  /** Lazily constructed immutable parser, shared only by this installation's messages and controls. */
  private final io.github.cowwoc.pouch10.core.ConcurrentLazyFactory<JsonMapper> mapper =
   io.github.cowwoc.pouch10.core.ConcurrentLazyFactory.create(() ->
    JsonMapper.builder(tools.jackson.core.json.JsonFactory.builder().
     enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).
     streamReadConstraints(tools.jackson.core.StreamReadConstraints.builder().maxDocumentLength(MAX_FRAME).
      maxStringLength(MAX_FRAME).maxNestingDepth(4).maxNumberLength(20).build()).build()).
     enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build(), value -> {});
  /** Permanently prevents acquisition after scope disposal starts. */
  private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();

  /** Creates a token-local scope without creating a parser or acquiring native resources. */
  MessageScope() {}

  /**
   * Returns the same strict immutable parser while this installation scope remains open.
   * @return token-local parser with complete-frame scalar bounds
   * @throws IllegalStateException if disposal began
   */
  JsonMapper json()
  {
   if (closed.get()) throw new IllegalStateException("Windows token message scope is closed");
   return mapper.getValue();
  }

  @Override public boolean isClosed() { return closed.get(); }

  /** Disposes token-local shared values after native delivery stops; repeated closure has no effects. */
  @Override public void close()
  {
   if (!closed.compareAndSet(false, true)) return;
   io.github.cowwoc.pouch10.core.Scopes.runAll(() -> children.shutdown(Duration.ZERO), mapper::close);
  }
 }
}
