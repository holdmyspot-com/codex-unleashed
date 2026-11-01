package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Verifies controller token accounting with documented zero-wait semaphore outcomes. */
public final class WindowsCargoTokensTest
{
 /**
  * Reserves actual capacity before grant and refuses reclamation before whole-Job quiescence.
  * @throws Exception if accounting fails
  */
 @Test public void reclaimsOnlyAfterWholeJobCannotExecute() throws Exception
 {
  Pool pool = new Pool();
  try (WindowsCargoJobserver owner = WindowsCargoJobserver.SharedSecrets.create(3, pool);
       WindowsCargoJobserver.CargoLease lease = owner.retainCargo(Map.of());
       WindowsCargoTokens tokens = new WindowsCargoTokens(lease))
  {
   WindowsCargoTokens.Domain domain = tokens.retain(UUID.randomUUID());
   UUID first = UUID.randomUUID();
   UUID second = UUID.randomUUID();
   requireThat(domain.tryReserve(first), "firstGrantReserved").isTrue();
   requireThat(domain.tryReserve(second), "secondGrantReserved").isTrue();
   requireThat(domain.tryReserve(UUID.randomUUID()), "emptyPoolGrant").isFalse();
   requireThat(domain.outstanding(), "heldTokens").isEqualTo(2, "expected");
   requireException(IOException.class, () -> domain.retire(() -> false));
   requireThat(pool.available, "tokensBeforeJobZero").isEqualTo(0, "expected");
   requireException(IllegalStateException.class, lease::close);
   domain.returnToken(first);
   requireException(IllegalArgumentException.class, () -> domain.returnToken(first));
   domain.retire(() -> true);
   domain.retire(() -> true);
   requireThat(pool.available, "tokensAfterJobZero").isEqualTo(2, "expected");
  }
 }
 /**
  * Retains ambiguous native returns without issuing the same return twice.
  * @throws Exception if setup fails
  */
 @Test public void failedReturnIsNeverReplayed() throws Exception
 {
  Pool pool = new Pool();
  WindowsCargoJobserver owner = WindowsCargoJobserver.SharedSecrets.create(2, pool);
  WindowsCargoJobserver.CargoLease lease = owner.retainCargo(Map.of());
  WindowsCargoTokens tokens = new WindowsCargoTokens(lease);
  WindowsCargoTokens.Domain domain = tokens.retain(UUID.randomUUID());
  UUID grant = UUID.randomUUID();
  requireThat(domain.tryReserve(grant), "reserved").isTrue();
  pool.failReturn = true;
  requireException(IOException.class, () -> domain.returnToken(grant));
  requireException(IOException.class, () -> domain.returnToken(grant));
  requireException(IOException.class, () -> domain.retire(() -> true));
  requireThat(pool.returns, "singleNativeReturnAttempt").isEqualTo(1, "expected");
  requireException(IOException.class, tokens::close);
  // Whole-Job zero permits abandoning the poisoned count and destroying the invocation pool, never reseeding it.
  lease.close();
  requireException(WindowsCargoJobserver.CapacityMismatch.class, owner::close);
  requireThat(pool.returns, "uncertainReturnWasNeverRepeatedDuringDestruction").isEqualTo(1, "expected");
 }
 /** Models the documented atomic semaphore decrement/timeout and increment outcomes. */
 private static final class Pool implements WindowsCargoJobserver.Calls
 {
  /** Explicit tokens currently present in the modeled semaphore. */
  private int available;
  /** Number of native token return attempts. */
  private int returns;
  /** Requests an ambiguous token return failure. */
  private boolean failReturn;
  /** Creates a test-local pool. */
  private Pool() { }
  @Override public boolean create(String name, int initial, int maximum, Consumer<MemorySegment> acquired)
  { available = initial; acquired.accept(MemorySegment.ofAddress(42)); return false; }
  @Override public boolean tryAcquire(MemorySegment handle)
  { if (available == 0) return false; --available; return true; }
  @Override public void release(MemorySegment handle, int count) throws IOException
  { ++returns; if (failReturn) throw new IOException("Ambiguous controlled return"); available += count; }
  @Override public void closeHandle(MemorySegment handle) { }
  @Override public void close() { }
 }
}
