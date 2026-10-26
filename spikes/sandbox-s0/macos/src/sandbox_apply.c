#include "sandbox_apply.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* sandbox_init() is deprecated in the SDK headers but still the only way for an unsigned/non-App-Sandbox helper
 * to confine itself. Declared here to avoid the deprecation warning noise. */
extern int sandbox_init(const char *profile, uint64_t flags, char **errorbuf);
extern void sandbox_free_error(char *errorbuf);

/*
 * deny default: no network, no file open (existing stdin/stdout/stderr fds keep working because Seatbelt checks at
 * open time), no process-exec/fork, no mach lookups, no sysctl writes, no signals to other processes.
 */
static const char PROFILE[] =
    "(version 1)\n"
    "(deny default)\n"
    "(deny network*)\n"
    "(deny system-socket)\n"
    "(deny file*)\n"
    "(deny process*)\n"
    "(deny mach*)\n"
    "(deny ipc*)\n"
    "(deny signal)\n"
    "(allow sysctl-read)\n";   /* hw.pagesize etc. used by libc/malloc */

#include <stdint.h>
int apply_seatbelt_sandbox(char *errbuf, unsigned errlen) {
    char *err = NULL;
    if (sandbox_init(PROFILE, 0, &err) != 0) {
        snprintf(errbuf, errlen, "%s", err ? err : "sandbox_init failed");
        if (err) sandbox_free_error(err);
        return -1;
    }
    return 0;
}
