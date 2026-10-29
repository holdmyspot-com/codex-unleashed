#include <errno.h>
#include <mach/mach.h>
#include <stdio.h>
#include <string.h>
#include <sys/sysctl.h>

extern kern_return_t macx_triggers(int hi_water, int low_water, int flags, mach_port_t alert_port);

/*
 * Checks whether macOS can disable swap and restore its initially enabled state.
 * Requires vm.swap_enabled to be 1 before the probe; otherwise exits with 2.
 * After requesting disable, attempts to enable swap again before checking the
 * request results. Restoration can fail and leave swap changed; inspect the
 * restored_swap_enabled output before relying on the initial state.
 *
 * Writes initial/observed swap states and kernel request statuses as key=value
 * lines to stdout, and failure diagnostics to stderr. On complete success, also
 * writes verified_swap_disabled=true and verified_original_state_restored=true.
 * Returns 0 when both transitions are verified. Returns 2 when the initial state
 * cannot be read or is not enabled. After the initial check, failures take this
 * priority: 3 for the disable request, 4 for the post-disable state read, 5 for
 * the restore request or verification, then 1 when the post-disable state is
 * nonzero despite a successful disable request.
 */
int main(void)
{
    enum { SWAP_COMPACT_DISABLE = 0x10, SWAP_COMPACT_ENABLE = 0x20 };
    int original;
    size_t state_size = sizeof original;
    if (sysctlbyname("vm.swap_enabled", &original, &state_size, NULL, 0) != 0)
    {
        perror("Cannot read initial vm.swap_enabled");
        return 2;
    }
    printf("initial_swap_enabled=%d\n", original);
    if (original != 1)
    {
        fprintf(stderr, "This probe requires initially enabled swap so it can restore that state.\n");
        return 2;
    }

    kern_return_t disable_status = macx_triggers(0, 0, SWAP_COMPACT_DISABLE, MACH_PORT_NULL);
    int after = -1;
    state_size = sizeof after;
    int read_status = sysctlbyname("vm.swap_enabled", &after, &state_size, NULL, 0);
    int read_error = errno;
    kern_return_t restore_status = macx_triggers(0, 0, SWAP_COMPACT_ENABLE, MACH_PORT_NULL);
    int restored = -1;
    state_size = sizeof restored;
    int restore_read_status = sysctlbyname("vm.swap_enabled", &restored, &state_size, NULL, 0);
    int restore_read_error = errno;
    printf("disable_status=%d\nafter_disable_swap_enabled=%d\nrestore_status=%d\nrestored_swap_enabled=%d\n",
        disable_status, after, restore_status, restored);

    if (disable_status != KERN_SUCCESS)
    {
        fprintf(stderr, "Kernel swap-disable request returned %d.\n", disable_status);
        return 3;
    }
    if (read_status != 0)
    {
        fprintf(stderr, "Cannot read swap state after control: %s\n", strerror(read_error));
        return 4;
    }
    if (restore_status != KERN_SUCCESS)
    {
        fprintf(stderr, "Kernel swap-restore request returned %d.\n", restore_status);
        return 5;
    }
    if (restore_read_status != 0)
    {
        fprintf(stderr, "Cannot verify restored swap state: %s\n", strerror(restore_read_error));
        return 5;
    }
    if (restored != original)
    {
        fprintf(stderr, "Restored swap state differs from the initial state.\n");
        return 5;
    }
    if (after != 0)
    {
        fprintf(stderr, "Kernel request returned success but swap remains enabled.\n");
        return 1;
    }
    printf("verified_swap_disabled=true\nverified_original_state_restored=true\n");
    return 0;
}
