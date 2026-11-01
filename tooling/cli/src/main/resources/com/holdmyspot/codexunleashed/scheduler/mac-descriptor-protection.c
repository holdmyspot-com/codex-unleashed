/* Native callbacks for held-image descriptor admission. The creation path must
 * remain callable before Gum/GumJS fork recovery. No JavaScript or Gum function
 * is called from create_queue(). The mapping is owned by the target process,
 * independently of the Script and CModule that currently operate on it. */
#include <glib.h>
#include <gum/guminterceptor.h>

#define MD_MAGIC 0x4d445031
#define MD_CAPACITY 65536
#define MD_PATHS 64

typedef struct {
  gint owned;
  gint generation;
} Descriptor;

typedef struct {
  gint magic;
  gint pid;
  volatile gint next_ticket;
  volatile gint serving_ticket;
  gint sealed;
  gint fault;
  gint mutations;
  gint creators_count;
  gint closers_count;
  gpointer creators[MD_PATHS];
  gpointer closers[MD_PATHS];
  Descriptor descriptors[MD_CAPACITY];
} Registry;

extern Registry registry;
extern gint process_id(void);
extern gint kernel_call(gint number, ...);
extern gint kqueue_number;
extern void abandon_process(void);
extern gint original_close(gint descriptor);
extern gint *error_location(void);
extern gint kernel_events(gint descriptor, gconstpointer changes, gint change_count,
    gpointer events, gint event_count, gconstpointer timeout);

/* This lock is never inherited in a usable state: the first native
 * creation on the single surviving fork thread clears the entire child registry
 * before acquiring it. Creation never enters Gum or JavaScript. A legitimate
 * close retains the lock through the existing native close guard and syscall,
 * so another thread cannot consume or authorize a reused number in that gap. */
static void acquire(void) {
  gint ticket = g_atomic_int_add(&registry.next_ticket, 1);
  while (g_atomic_int_add(&registry.serving_ticket, 0) != ticket) {}
}

static void release(void) {
  g_atomic_int_add(&registry.serving_ticket, 1);
}

static gint contains(gpointer *paths, gint count, gpointer caller) {
  for (gint i = 0; i < count; i++)
    if (paths[i] == caller) return 1;
  return 0;
}

static void remember(gpointer *paths, gint *count, gpointer caller) {
  if (contains(paths, *count, caller)) return;
  if (*count == MD_PATHS) { registry.fault = 1; return; }
  paths[(*count)++] = caller;
}

/* Invoked only before the first Script acknowledgement in a new image. */
gint initialize(gint pid) {
  if (registry.magic != 0 || pid != process_id()) return 0;
  registry.pid = pid;
  registry.magic = MD_MAGIC;
  return 1;
}

gint mapping_size(void) {
  return sizeof(Registry);
}

/* The supported producer holds the fork child before application return. Its
 * first GLib recovery creation occurs on the sole surviving thread, before
 * runtime worker threads are recreated. Parent descriptor identities and any
 * partially inherited lock state therefore cannot authorize a child close. */
static void enter_process(gint pid) {
  if (registry.pid == pid) return;
  registry.next_ticket = 0;
  registry.serving_ticket = 0;
  registry.sealed = 0;
  registry.fault = 0;
  registry.mutations = 0;
  registry.creators_count = 0;
  registry.closers_count = 0;
  for (gint i = 0; i < MD_CAPACITY; i++) {
    registry.descriptors[i].owned = 0;
    registry.descriptors[i].generation = 0;
  }
  registry.pid = pid;
}

/* Forward the authenticated SDK syscall directly. The returned Gum trampoline
 * is deliberately unused: early child recovery must not enter Gum machinery.
 * Caller addresses are admitted exclusively while application code is held. */
gint create_queue(void) {
  gpointer caller = __builtin_return_address(0);
  gint pid = process_id();
  enter_process(pid);
  acquire();
  gint fd = kernel_call(kqueue_number);
  if (fd < 0) { release(); return fd; }
  if (registry.mutations == 2147483647) registry.fault = 1;
  else registry.mutations++;
  if (fd >= MD_CAPACITY) registry.fault = 1;
  else {
    Descriptor *entry = &registry.descriptors[fd];
    if (entry->generation == 2147483647) registry.fault = 1;
    else entry->generation++;
    if (!registry.sealed)
      remember(registry.creators, &registry.creators_count, caller);
    entry->owned = contains(registry.creators, registry.creators_count, caller);
  }
  gint failed = registry.fault;
  release();
  if (failed) abandon_process();
  return fd;
}

/* An ordinary native replacement composes with Frida's existing close
 * listener. Unlike a listener, it also executes on runtime threads whose Gum
 * listeners are temporarily ignored. Calling the imported original entry from
 * this replacement uses Gum's supported same-replacement bypass; it does not
 * replace the guarded entry blindly or enter JavaScript. */
gint close_descriptor(gint fd) {
  if (registry.pid != process_id() || fd < 0 || fd >= MD_CAPACITY)
    return original_close(fd);
  GumInvocationContext *context = gum_interceptor_get_current_invocation();
  gpointer caller = gum_invocation_context_get_return_address(context);
  acquire();
  Descriptor *entry = &registry.descriptors[fd];
  gint owned = entry->owned;
  gint generation = entry->generation;
  /* Some native runtime teardown paths bypass the ordinary close entry. A
   * surviving numeric ownership entry therefore cannot authorize protection
   * after the kernel has reused that number for an application pipe or file.
   * The SDK bridge asserts both the zero timeout layout and Darwin EBADF.
   * Only that definitive non-kqueue result discards ownership; other errors
   * retain protection. No event is registered, removed, or consumed here. */
  if (owned) {
    gint saved_error = *error_location();
    gint64 zero_timeout[2] = { 0, 0 };
    gint present = kernel_events(fd, NULL, 0, NULL, 0, zero_timeout);
    gint probe_error = *error_location();
    *error_location() = saved_error;
    if (present < 0 && probe_error == 9) {
      entry->owned = 0;
      owned = 0;
      if (registry.mutations == 2147483647) {
        registry.fault = 1;
        release();
        abandon_process();
        return -1;
      }
      registry.mutations++;
    }
  }
  if (owned) {
    if (!registry.sealed)
      remember(registry.closers, &registry.closers_count, caller);
    if (!contains(registry.closers, registry.closers_count, caller)) {
      release();
      *error_location() = 0;
      return 0;
    }
  }
  if (!owned) {
    if (registry.sealed) {
      release();
      return original_close(fd);
    }
    /* Hold the ticket through an unseeded close without versioning unrelated
     * transport file/socket churn. seal() rechecks every proposed queue with
     * a zero-event kernel query under this same ticket before publishing it. */
    gint result = original_close(fd);
    release();
    return result;
  }
  /* Keep ownership and the ticket through the actual original kernel effect. */
  gint result = original_close(fd);
  if (registry.mutations == 2147483647 ||
      (owned && (result != 0 || entry->generation != generation)))
    registry.fault = 1;
  else {
    if (owned) entry->owned = 0;
    registry.mutations++;
  }
  gint failed = registry.fault;
  release();
  if (failed) abandon_process();
  return result;
}

/* Adoption occurs only after the supported GLib, GIO, Gum and GumJS child
 * recovery sequence. The native creation path itself never enters Gum. */
gint adopt(void) {
  if (registry.magic != MD_MAGIC || registry.pid != process_id() ||
      registry.sealed || registry.fault) return 0;
  return 1;
}

/* Copies the exact early native ownership set before a fresh Script bootstrap
 * can create another runtime context. Numeric identities alone never seed it. */
gint inventory(gint *output, gint capacity) {
  if (registry.magic != MD_MAGIC || registry.pid != process_id() || capacity < 0) return -1;
  acquire();
  gint count = 0;
  for (gint fd = 0; fd < MD_CAPACITY; fd++) {
    if (!registry.descriptors[fd].owned) continue;
    if (count == capacity) { release(); return -1; }
    output[count++] = fd;
  }
  release();
  return count;
}

/* Copies a consistent header for the authenticated host's admission protocol. */
gint snapshot(gint *output) {
  if (registry.magic != MD_MAGIC || registry.pid != process_id()) return 0;
  acquire();
  output[0] = registry.pid;
  output[1] = registry.mutations;
  output[2] = registry.fault;
  output[3] = registry.creators_count;
  output[4] = registry.closers_count;
  output[5] = registry.sealed;
  release();
  return 1;
}

/* The held producer admits every future native creation. Seed only still-live
 * independently authenticated queues and preserve queues created in transit.
 * A pre-hook queue cannot be recreated without passing the native hook. */
gint seal(gint expected_mutations, const gint *descriptors, gint count) {
  if (count < 0 || count > MD_CAPACITY) return 0;
  acquire();
  gint valid = registry.pid == process_id() && !registry.fault &&
      !registry.sealed && expected_mutations >= 0 &&
      registry.creators_count > 0 && registry.closers_count > 0;
  /* The SDK-compiled native bridge asserts this opaque zero timeout's size.
   * No kevent records are supplied or consumed, so this is a bounded native
   * type/liveness check, not an event registration or event dequeue. Creation
   * is versioned, and close holds this ticket through its kernel effect. Thus
   * a pre-hook queue cannot be seeded after closing and reusing its number. */
  gint64 zero_timeout[2] = { 0, 0 };
  for (gint i = 0; valid && i < count; i++) {
    gint fd = descriptors[i];
    if (fd < 0 || fd >= MD_CAPACITY ||
        kernel_events(fd, NULL, 0, NULL, 0, zero_timeout) != 0) valid = 0;
  }
  if (valid) {
    for (gint i = 0; i < count; i++) registry.descriptors[descriptors[i]].owned = 1;
    registry.sealed = 1;
  }
  release();
  return valid;
}

/* While the application remains held, later creations can only come from the
 * authenticated runtime producer. Retain these native ownership entries across
 * transport activity; require every independently observed queue to remain a
 * live owned queue under the same close/creation ticket. */
gint validate(gint expected_mutations, const gint *descriptors, gint count) {
  if (count < 0 || count > MD_CAPACITY) return 0;
  acquire();
  gint valid = registry.pid == process_id() && !registry.fault &&
      registry.sealed && expected_mutations >= 0;
  gint64 zero_timeout[2] = { 0, 0 };
  for (gint i = 0; valid && i < count; i++) {
    gint fd = descriptors[i];
    if (fd < 0 || fd >= MD_CAPACITY || !registry.descriptors[fd].owned ||
        kernel_events(fd, NULL, 0, NULL, 0, zero_timeout) != 0) valid = 0;
  }
  release();
  return valid;
}
