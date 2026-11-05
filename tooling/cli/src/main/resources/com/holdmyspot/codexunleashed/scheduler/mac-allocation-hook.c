/* Native malloc replacement and allocation registry, loaded by mac-allocation-hook.js. */
#include <glib.h>
#include <stddef.h>

#define SLOT_COUNT 8
#define MAX_WAIT 9223372036854775807ULL
#define ENOMEM_VALUE 12

enum Phase { FREE, WAITING_UNPUBLISHED, WAITING_PUBLISHED, PERMITTED, RETRYING };
enum Fault { NO_FAULT, SLOT_EXHAUSTED, WAIT_EXHAUSTED, INVALID_PHASE };
typedef void *(*Malloc)(size_t bytes);
typedef struct {
  GCond condition;
  guint64 id, thread, bytes;
  gint error, phase;
} Slot;
typedef struct {
  GMutex mutex;
  GCond fatal_condition;
  guint64 issued;
  gint fault;
  Slot slots[SLOT_COUNT];
} Registry;
typedef struct { guint64 id, thread, bytes; guint32 error; } Publication;
extern Registry registry;
extern Malloc original_malloc;
extern int *errno_location(void);
extern void *native_thread_id(void);

/* Size checks run before initialize touches the supplied external backing. */
guint registry_size(void) { return sizeof(Registry); }
guint publication_size(void) { return sizeof(Publication); }
void initialize(void) {
  guint i;
  g_mutex_init(&registry.mutex);
  g_cond_init(&registry.fatal_condition);
  for (i = 0; i < SLOT_COUNT; i++) g_cond_init(&registry.slots[i].condition);
}

/* Called with mutex held. Fatal callback waits release it, preserving controller fault/kill responsiveness. */
void fatal_locked(gint fault) {
  if (registry.fault == NO_FAULT) registry.fault = fault;
  for (;;) g_cond_wait(&registry.fatal_condition, &registry.mutex);
}

/* Sticky fault is observed by JS; no permission or cleanup-time wake can make a fatal callback continue. */
gint fault_code(void) {
  gint value;
  g_mutex_lock(&registry.mutex);
  value = registry.fault;
  g_mutex_unlock(&registry.mutex);
  return value;
}

/* Reserve one fresh denial identity while preserving its invocation's slot and native mutex ownership. */
void denied_locked(Slot *slot, gint error) {
  if (registry.fault != NO_FAULT) fatal_locked(registry.fault);
  if (registry.issued == MAX_WAIT) fatal_locked(WAIT_EXHAUSTED);
  slot->id = ++registry.issued;
  slot->error = error;
  slot->phase = WAITING_UNPUBLISHED;
}

/* Only actual positive-size NULL/ENOMEM enters the registry. Successful/ineligible calls preserve result and errno. */
void *replacement_malloc(size_t bytes) {
  void *result = original_malloc(bytes);
  gint error;
  Slot *slot = NULL;
  guint i;
  if (result != NULL || bytes == 0) return result;
  error = *errno_location();
  if (error != ENOMEM_VALUE) return result;
  g_mutex_lock(&registry.mutex);
  if (registry.fault != NO_FAULT) fatal_locked(registry.fault);
  for (i = 0; i < SLOT_COUNT; i++) {
    if (registry.slots[i].phase == FREE) { slot = &registry.slots[i]; break; }
  }
  if (slot == NULL) fatal_locked(SLOT_EXHAUSTED);
  slot->thread = (guint64)(gsize)native_thread_id();
  slot->bytes = bytes;
  denied_locked(slot, error);
  for (;;) {
    while (slot->phase == WAITING_UNPUBLISHED || slot->phase == WAITING_PUBLISHED)
      g_cond_wait(&slot->condition, &registry.mutex);
    if (registry.fault != NO_FAULT) fatal_locked(registry.fault);
    if (slot->phase != PERMITTED) fatal_locked(INVALID_PHASE);
    slot->phase = RETRYING;
    g_mutex_unlock(&registry.mutex);

    *errno_location() = error;
    result = original_malloc(bytes);
    error = *errno_location();

    g_mutex_lock(&registry.mutex);
    if (registry.fault != NO_FAULT) fatal_locked(registry.fault);
    if (result != NULL || error != ENOMEM_VALUE) {
      slot->phase = FREE;
      slot->id = 0;
      g_mutex_unlock(&registry.mutex);
      *errno_location() = error;
      return result;
    }
    denied_locked(slot, error);
  }
}

/* Atomically copy the oldest unpublished denial. Slot contents stay retained until accepted permission. */
gint next_publication(Publication *output) {
  Slot *oldest = NULL;
  guint i;
  g_mutex_lock(&registry.mutex);
  if (registry.fault != NO_FAULT) { g_mutex_unlock(&registry.mutex); return -1; }
  for (i = 0; i < SLOT_COUNT; i++) {
    Slot *slot = &registry.slots[i];
    if (slot->phase == WAITING_UNPUBLISHED && (oldest == NULL || slot->id < oldest->id)) oldest = slot;
  }
  if (oldest == NULL) { g_mutex_unlock(&registry.mutex); return 0; }
  output->id = oldest->id;
  output->thread = oldest->thread;
  output->bytes = oldest->bytes;
  output->error = (guint32)oldest->error;
  oldest->phase = WAITING_PUBLISHED;
  g_mutex_unlock(&registry.mutex);
  return 1;
}

/* 0 unknown, 1 accepted, 2 duplicate, -1 fatal. Every absent issued ID retired only after accepted grant. */
gint grant(guint64 id) {
  guint i;
  gint result;
  g_mutex_lock(&registry.mutex);
  if (registry.fault != NO_FAULT) { g_mutex_unlock(&registry.mutex); return -1; }
  for (i = 0; i < SLOT_COUNT; i++) {
    Slot *slot = &registry.slots[i];
    if (slot->id != id || id == 0) continue;
    switch (slot->phase) {
      case WAITING_PUBLISHED:
        slot->phase = PERMITTED;
        g_cond_signal(&slot->condition);
        g_mutex_unlock(&registry.mutex);
        return 1;
      case PERMITTED:
      case RETRYING:
        g_mutex_unlock(&registry.mutex);
        return 2;
      case WAITING_UNPUBLISHED:
        g_mutex_unlock(&registry.mutex);
        return 0;
      default:
        if (registry.fault == NO_FAULT) registry.fault = INVALID_PHASE;
        g_mutex_unlock(&registry.mutex);
        return -1;
    }
  }
  result = id > 0 && id <= registry.issued ? 2 : 0;
  g_mutex_unlock(&registry.mutex);
  return result;
}
