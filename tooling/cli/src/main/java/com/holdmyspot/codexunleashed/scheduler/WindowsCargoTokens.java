package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Accounts explicit semaphore reservations for independently quiescent Windows process Jobs.
 * The controller records ownership before native acquisition and before publishing a grant. An image's exit does not
 * return its grants: descendants can still execute. Retire each domain only after the complete owning Job cannot run.
 * All operations belong to the constructing thread. A failed count-changing effect poisons the entire invocation;
 * outstanding state remains retained and no uncertain effect is repeated.
 */
final class WindowsCargoTokens implements AutoCloseable
{
 /** Exclusive owner of every count transition. */
 private final Thread controller = Thread.currentThread();
 /** Invocation borrow retained until every domain retires. */
 private final WindowsCargoJobserver.CargoLease lease;
 /** Append-only domain identities prevent a retired workload from acquiring fresh authority. */
 private final Map<UUID, Domain> domains = new HashMap<>();
 /** First uncertain count-changing operation, never cleared by cleanup. */
 private IOException failure = null;
 /** Indicates whether the retained lease borrow has ended. */
 private boolean closed = false;

 /**
  * Borrows the invocation lease through ledger retirement without changing the semaphore count.
  * @param lease retained invocation
  * @throws NullPointerException if lease is null
  * @throws IllegalStateException if the lease is released or used off-thread
  */
 WindowsCargoTokens(WindowsCargoJobserver.CargoLease lease)
 {
  this.lease = Objects.requireNonNull(lease, "lease may not be null");
  lease.retainTokenLedger();
 }

 /**
  * Retains an independently quiescent Job domain before its first image executes.
  * @param identity unique workload identity, never reused by this invocation
  * @return empty domain borrowing this ledger
  * @throws NullPointerException if identity is null
  * @throws IllegalArgumentException if the identity was already retained
  * @throws IllegalStateException if closed or used off-thread
  * @throws IOException if a previous token effect is uncertain
  */
 Domain retain(UUID identity) throws IOException
 {
  Objects.requireNonNull(identity, "identity may not be null");
  requireAuthority();
  if (domains.containsKey(identity))
   throw new IllegalArgumentException("Token domain identity was already retained: " + identity);
  Domain domain = new Domain();
  domains.put(identity, domain);
  return domain;
 }

 /**
  * Counts the grants of every live domain without touching the semaphore.
  * @return tokens definitely held and tokens outstanding, including uncertain acquisitions
  * @throws IllegalStateException if called off-thread
  */
 Accounting accounting()
 {
  requireController();
  int held = 0;
  int outstanding = 0;
  for (Domain domain : domains.values())
  {
   if (domain.retired) continue;
   outstanding += domain.grants.size();
   for (Boolean certain : domain.grants.values())
    if (certain) ++held;
  }
  return new Accounting(held, outstanding);
 }

 /**
  * Reports the explicit semaphore capacity this ledger draws from.
  * @return explicit tokens in a quiescent pool, excluding Cargo's implicit slot
  */
 int capacity() { return lease.capacity(); }

 /**
  * Token counts of the live domains.
  * @param held tokens definitely held
  * @param outstanding held tokens plus acquisitions whose result is uncertain
  */
 record Accounting(int held, int outstanding) { }

 /**
  * Releases the invocation borrow only when every domain has reached whole-Job quiescence.
  * A poisoned ledger releases its borrow for pool destruction and still reports its original failure. It never
  * invents a return for an uncertain acquisition or repeats an uncertain return.
  * @throws IllegalStateException if a domain remains live or called off-thread
  * @throws IOException if a native effect remains uncertain
  */
 @Override public void close() throws IOException
 {
  requireController();
  if (closed)
  {
   if (failure != null) throw failure;
   return;
  }
  for (Domain domain : domains.values())
   if (!domain.retired)
    throw new IllegalStateException("A whole-Job token domain still retains the invocation ledger");
  lease.releaseTokenLedger();
  closed = true;
  if (failure != null) throw failure;
 }

 /**
  * Checks ownership before touching the invocation's count.
  * @throws IllegalStateException if closed or used off-thread
  * @throws IOException if a count-changing operation failed
  */
 private void requireAuthority() throws IOException
 {
  requireController();
  if (failure != null) throw failure;
  if (closed) throw new IllegalStateException("Windows Cargo token ledger is closed");
 }

 /**
  * Rejects use from another controller before external effects.
  * @throws IllegalStateException if called off-thread
  */
 private void requireController()
 {
  if (Thread.currentThread() != controller)
   throw new IllegalStateException("Windows Cargo tokens require the constructing controller thread");
 }

 /** Retains grants through image exit until its complete Job cannot execute. */
 final class Domain
 {
  /** Grants retained before native acquisition; uncertain entries cannot be inferred away. */
  private final Map<UUID, Boolean> grants = new HashMap<>();
  /** Indicates whether successful whole-Job retirement returned every remaining grant. */
  private boolean retired = false;

  /** Creates an empty Job domain without native effects. */
  private Domain() { }

  /**
   * Reserves actual explicit capacity before publishing one unique grant.
   * @param grant unique controller-generated grant identity, never a peer-supplied numeric handle
   * @return true if one token is held, or false when no token was available and no grant is retained
   * @throws NullPointerException if grant is null
   * @throws IllegalArgumentException if the grant is already held
   * @throws IllegalStateException if retired, closed, or called off-thread
   * @throws IOException if a token operation fails, retaining uncertain acquisition ownership
   */
  boolean tryReserve(UUID grant) throws IOException
  {
   Objects.requireNonNull(grant, "grant may not be null");
   requireLive();
   if (grants.putIfAbsent(grant, Boolean.FALSE) != null)
    throw new IllegalArgumentException("Token grant is already outstanding: " + grant);
   try
   {
    if (!lease.tryAcquireToken())
    {
     grants.remove(grant);
     return false;
    }
    grants.put(grant, Boolean.TRUE);
    return true;
   }
   catch (IOException cause)
   {
    failure = cause;
    throw cause;
   }
  }

  /**
   * Returns a definitely held token once, before acknowledging its native return or cancellation.
   * @param grant outstanding grant identity
   * @throws NullPointerException if grant is null
   * @throws IllegalArgumentException if the grant is absent
   * @throws IllegalStateException if retired, closed, or called off-thread
   * @throws IOException if a count-changing effect is uncertain; its grant remains retained
   */
  void returnToken(UUID grant) throws IOException
  {
   Objects.requireNonNull(grant, "grant may not be null");
   requireLive();
   if (!Boolean.TRUE.equals(grants.get(grant)))
    throw new IllegalArgumentException("Token grant is not definitely held: " + grant);
   try { lease.returnToken(); }
   catch (IOException cause)
   {
    failure = cause;
    throw cause;
   }
   grants.remove(grant);
  }

  /**
   * Returns all remaining definite grants after the complete Job cannot execute.
   * Once quiescence is known, execution authority retires even on failure; retained uncertainty requires destruction
   * of the invocation pool rather than reuse or count reseeding.
   * An unsuccessful barrier leaves this domain live and makes no semaphore change. Repeated successful retirement
   * has no effect. Uncertain acquisitions or returns remain fatal and are never replayed.
   * @param barrier trusted kernel-backed observation covering the complete Job, including descendants
   * @throws NullPointerException if barrier is null
   * @throws IllegalStateException if closed or called off-thread
   * @throws IOException if quiescence is false or a count-changing effect fails
   */
  void retire(AllocationHook.ExecutionBarrier barrier) throws IOException
  {
   Objects.requireNonNull(barrier, "barrier may not be null");
   requireController();
   if (retired)
   {
    if (failure != null) throw failure;
    return;
   }
   if (!barrier.reached())
    throw new IOException("Token reclamation requires whole-Job execution to be impossible");
   try
   {
    requireAuthority();
    for (UUID grant : java.util.List.copyOf(grants.keySet())) returnToken(grant);
   }
   finally
   {
    // Whole-Job zero retires execution authority even when an uncertain count must be abandoned with the pool.
    // The retained failure prevents success; the ledger borrow can still end so the pool handle can be destroyed.
    retired = true;
   }
  }

  /**
   * Observes currently available pool capacity without creating a synthetic semaphore token.
   * The matching native available-probe decrement consumes only a script-local observation credit.
   * @return actual observed and restored explicit capacity, excluding outstanding grants and the implicit slot
   * @throws IllegalStateException if retired, closed, or called off-thread
   * @throws IOException if observation or restoration is uncertain
   */
  int available() throws IOException
  {
   requireLive();
   try { return lease.availableTokens(); }
   catch (IOException cause) { failure = cause; throw cause; }
  }

  /**
   * Returns unreturned grants, including retained uncertain acquisitions.
   * @return count of this domain's retained grants
   * @throws IllegalStateException if called off-thread
   */
  int outstanding() { requireController(); return grants.size(); }

  /**
   * Checks this domain's authority before a count-changing operation.
   * @throws IllegalStateException if retired, closed, or called off-thread
   * @throws IOException if any invocation count-changing effect is uncertain
   */
  private void requireLive() throws IOException
  {
   requireAuthority();
   if (retired) throw new IllegalStateException("Windows Cargo token domain is retired");
  }
 }
}
