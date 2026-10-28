/*
 * The startup sequence of ADR-0002 §4.4 for the desktop helper. macOS: Seatbelt via sandbox_init,
 * which S0 found to be the only mechanism that also forbids fork/exec and reading system paths for
 * a helper (an App Sandbox entitlement alone allows both). Windows (LPAC + Job object):
 * ssc_os_windows.c.
 */
#include "ssc_os_sandbox.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifndef _WIN32
#include <sys/resource.h>
#include <time.h>
#include <unistd.h>

extern char **environ;
static char *empty_environment[] = { NULL };

static void harden(void) {
    environ = empty_environment; /* nothing in the sandbox needs the host's environment */
    /*
     * Load the system time zone now: once confined, libc can no longer read it and scripts' local
     * dates (new Date(y, m, d), getHours) would silently become UTC, unlike the host's engine.
     */
    tzset();
    time_t now = time(NULL);
    struct tm ignored;
    localtime_r(&now, &ignored);
    struct rlimit none = { 0, 0 };
    setrlimit(RLIMIT_CORE, &none); /* a crash must not write the script's memory to disk */
    long max = sysconf(_SC_OPEN_MAX);
    if (max < 0 || max > 65536) max = 65536;
    for (int fd = 3; fd < max; fd++) close(fd); /* only stdin, stdout and stderr stay */
}
#endif

#ifdef __APPLE__
/* Deprecated in the SDK headers, still the only way for a helper to confine itself. */
extern int sandbox_init(const char *profile, unsigned long long flags, char **errorbuf);
extern void sandbox_free_error(char *errorbuf);

/*
 * deny default: no network, no file opens (stdin/stdout/stderr were opened before and keep
 * working: Seatbelt checks at open time), no fork/exec, no Mach lookups, no IPC, no signals to
 * other processes. libc and malloc only need sysctl reads.
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
    "(allow sysctl-read)\n";

int ssc_os_lockdown(int *argc, char ***argv, int *exit_code, char *err, size_t err_len) {
    (void)argc; (void)argv; (void)exit_code;
    harden();
    char *e = NULL;
    if (sandbox_init(PROFILE, 0, &e) != 0) {
        snprintf(err, err_len, "sandbox_init failed");
        if (e) sandbox_free_error(e); /* its text is not ours to print */
        return -1;
    }
    return SSC_OS_CONFINED;
}
#elif !defined(_WIN32)
/* Linux builds are for tests and CI; no desktop distribution ships them. */
int ssc_os_lockdown(int *argc, char ***argv, int *exit_code, char *err, size_t err_len) {
    (void)argc; (void)argv; (void)exit_code; (void)err; (void)err_len;
    harden();
    return SSC_OS_CONFINED;
}
#endif
