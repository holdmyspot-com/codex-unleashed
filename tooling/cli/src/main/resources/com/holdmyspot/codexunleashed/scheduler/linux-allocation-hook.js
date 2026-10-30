'use strict';

/*
 * One verified Linux/glibc process-image installation, initialized by AllocationHook's serialized allocationIdentity.
 * Only positive-size malloc NULL/ENOMEM waits. This resource does not cover every allocator, fork inheritance or signal
 * handler allocation. Native callbacks use the pinned Gum listener guard; JS control calls explicitly use traps:none.
 * Eight slots bound simultaneous denied invocations, not Java transport or lifetime identity collections.
 * Keep this listener, CModule and backing alive until the attempt establishes whole-workload execution is impossible.
 */
if (Process.platform !== 'linux' || Process.pointerSize !== 8)
  throw new Error('Allocation hook requires 64-bit Linux/glibc');

const maximumWait = uint64('9223372036854775807');
const zero = uint64('0');

/** Read a canonical positive decimal native identity without losing unsigned magnitudes. */
function positiveInteger(raw, maximum) {
  if (typeof raw !== 'string')
    throw new Error('Allocation identity must be a decimal string');
  const value = uint64(raw);
  if (value.compare(zero) <= 0 || value.compare(maximum) > 0 || value.toString() !== raw)
    throw new Error('Allocation identity is outside its canonical positive range');
  return value;
}

const identityFields = ['attempt', 'installation', 'pid'];
if (Object.keys(allocationIdentity).length !== identityFields.length ||
    identityFields.some(field => !Object.prototype.hasOwnProperty.call(allocationIdentity, field)))
  throw new Error('Allocation initialization has unexpected fields');
if (typeof allocationIdentity.attempt !== 'string' ||
    !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(allocationIdentity.attempt))
  throw new Error('Allocation initialization requires a canonical attempt UUID');
positiveInteger(allocationIdentity.installation, maximumWait);
positiveInteger(allocationIdentity.pid, uint64('4294967295'));
if (allocationIdentity.pid !== Process.id.toString())
  throw new Error('Allocation initialization identifies a different process');

const libc = Process.findModuleByName('libc.so.6');
if (libc === null)
  throw new Error('Allocation hook requires loaded glibc libc.so.6');
const mallocAddress = libc.getExportByName('malloc');
/* Preallocated bound validated against actual native sizeof before initialization or listener attachment. */
const registryBytes = 16384;
const backing = Memory.alloc(registryBytes);
backing.writeByteArray(new Uint8Array(registryBytes));
const nativeModule = new CModule(`
#include <glib.h>
#include <gum/guminterceptor.h>
#include <stddef.h>

#define SLOT_COUNT 8
#define MAX_WAIT 9223372036854775807ULL
#define ENOMEM_VALUE 12

enum Phase { FREE, WAITING_UNPUBLISHED, WAITING_PUBLISHED, PERMITTED, RETRYING };
enum Fault { NO_FAULT, SLOT_EXHAUSTED, WAIT_EXHAUSTED, INVALID_PHASE, NO_INVOCATION_DATA };
typedef struct { gsize bytes; } Call;
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
extern void *original_malloc(size_t bytes);
extern int *errno_location(void);
extern int native_thread_id(void);

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

/* Capture original arguments before malloc. Gum suppresses nested listener entry throughout callback extent. */
void enter(GumInvocationContext *context) {
  Call *call = GUM_IC_GET_INVOCATION_DATA(context, Call);
  if (call == NULL) {
    g_mutex_lock(&registry.mutex);
    fatal_locked(NO_INVOCATION_DATA);
  }
  call->bytes = (gsize)gum_invocation_context_get_nth_argument(context, 0);
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
void leave(GumInvocationContext *context) {
  Call *call = GUM_IC_GET_INVOCATION_DATA(context, Call);
  gpointer result = gum_invocation_context_get_return_value(context);
  gint error = context->system_error;
  Slot *slot = NULL;
  guint i;
  if (call == NULL) {
    g_mutex_lock(&registry.mutex);
    fatal_locked(NO_INVOCATION_DATA);
  }
  if (result != NULL || call->bytes == 0 || error != ENOMEM_VALUE) {
    context->system_error = error;
    return;
  }
  g_mutex_lock(&registry.mutex);
  if (registry.fault != NO_FAULT) fatal_locked(registry.fault);
  for (i = 0; i < SLOT_COUNT; i++) {
    if (registry.slots[i].phase == FREE) { slot = &registry.slots[i]; break; }
  }
  if (slot == NULL) fatal_locked(SLOT_EXHAUSTED);
  slot->thread = (guint64)native_thread_id();
  slot->bytes = call->bytes;
  denied_locked(slot, error);
  for (;;) {
    while (slot->phase == WAITING_UNPUBLISHED || slot->phase == WAITING_PUBLISHED)
      g_cond_wait(&slot->condition, &registry.mutex);
    if (registry.fault != NO_FAULT) fatal_locked(registry.fault);
    if (slot->phase != PERMITTED) fatal_locked(INVALID_PHASE);
    slot->phase = RETRYING;
    g_mutex_unlock(&registry.mutex);

    *errno_location() = error;
    result = original_malloc(call->bytes);
    error = *errno_location();

    g_mutex_lock(&registry.mutex);
    if (registry.fault != NO_FAULT) fatal_locked(registry.fault);
    if (result != NULL || error != ENOMEM_VALUE) {
      slot->phase = FREE;
      slot->id = 0;
      g_mutex_unlock(&registry.mutex);
      gum_invocation_context_replace_return_value(context, result);
      context->system_error = error;
      return;
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
`, {
  registry: backing,
  original_malloc: mallocAddress,
  errno_location: libc.getExportByName('__errno_location'),
  native_thread_id: libc.getExportByName('gettid')
});

/** Bind only control/query operations, excluding their runtime allocations from the application listener. */
function control(name, result, arguments_) {
  return new NativeFunction(nativeModule[name], result, arguments_, { traps: 'none' });
}

const nativeSize = control('registry_size', 'uint', [])();
if (nativeSize > registryBytes)
  throw new Error('Native allocation registry exceeds preallocated backing');
const publicationSize = control('publication_size', 'uint', [])();
if (publicationSize !== 32)
  throw new Error('Native allocation publication layout differs from 64-bit Linux contract');
const publication = Memory.alloc(publicationSize);
control('initialize', 'void', [])();
const faultCode = control('fault_code', 'int', []);
const nextPublication = control('next_publication', 'int', ['pointer']);
const nativeGrant = control('grant', 'int', ['uint64']);

/** Send only strict version-1 allocation payloads accepted by the existing Java codec. */
function emit(event, details = {}) {
  send({ protocol: 'codex-build-allocation', version: 1, attempt: allocationIdentity.attempt,
    installation: allocationIdentity.installation, pid: allocationIdentity.pid, event, ...details });
}

/** Validate one identity-exact permission before any native effect; malformed controls become terminal script errors. */
function receivePermission() {
  recv('allocation-control', message => {
    const fields = ['type', 'version', 'attempt', 'installation', 'pid', 'operation', 'wait'];
    if (Object.keys(message).length !== fields.length ||
        fields.some(field => !Object.prototype.hasOwnProperty.call(message, field)))
      throw new Error('Allocation control has unexpected fields');
    if (message.type !== 'allocation-control' || message.version !== 1 || message.attempt !== allocationIdentity.attempt ||
        message.installation !== allocationIdentity.installation || message.pid !== allocationIdentity.pid ||
        message.operation !== 'grant')
      throw new Error('Allocation control has wrong identity, version or operation');
    const id = positiveInteger(message.wait, maximumWait);
    const result = nativeGrant(id);
    if (result === -1) {
      clearInterval(publicationTimer);
      throw new Error('Allocation registry fault ' + faultCode());
    }
    const statuses = ['unknown', 'accepted', 'duplicate'];
    if (result < 0 || result >= statuses.length)
      throw new Error('Native allocation permission result is outside its range');
    emit('granted', { wait: message.wait, status: statuses[result] });
    receivePermission();
  });
}

/* Reachability retains all executable state. There is intentionally no dispose/unload while target tasks can execute. */
const listener = Interceptor.attach(mallocAddress, { onEnter: nativeModule.enter, onLeave: nativeModule.leave });
Interceptor.flush();
receivePermission();
const publicationTimer = setInterval(() => {
  if (faultCode() !== 0) {
    clearInterval(publicationTimer);
    throw new Error('Allocation registry fault ' + faultCode());
  }
  for (let count = 0; count < 8; count++) {
    const result = nextPublication(publication);
    if (result === 0)
      return;
    if (result !== 1) {
      clearInterval(publicationTimer);
      throw new Error('Allocation registry fault ' + faultCode());
    }
    emit('blocked', { wait: publication.readU64().toString(), thread: publication.add(8).readU64().toString(),
      bytes: publication.add(16).readU64().toString(), error: publication.add(24).readU32().toString() });
  }
}, 10);
emit('ready');
