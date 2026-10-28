/*
 * OS confinement of the desktop sandbox helper (ADR-0002 §4.4, S3). Called once, first thing in
 * main, before a single byte of input is read.
 */
#ifndef SSC_OS_SANDBOX_H
#define SSC_OS_SANDBOX_H

#include <stddef.h>

#define SSC_OS_CONFINED 0 /* this process is confined: go on */
#define SSC_OS_LAUNCHED 1 /* this process only launched the confined copy, which has ended */

/*
 * macOS: drops what the process inherited (environment, descriptors above stderr, core dumps) and
 * applies a deny-all Seatbelt profile with sandbox_init (irreversible).
 *
 * Windows: a process cannot confine itself, so the first call is a launcher. It starts this same
 * executable again inside a Low Privilege AppContainer (no capabilities) and a Job object, hands
 * it its stdin/stdout, waits, and returns SSC_OS_LAUNCHED with the copy's exit code in
 * *exit_code. `--package-key <[0-9a-z]{1,32}>` as the first argument picks the AppContainer, so
 * helpers of different packages cannot open each other. The copy verifies its own token and Job
 * before returning SSC_OS_CONFINED; the launcher's internal argument is removed from argc and argv.
 *
 * Returns SSC_OS_CONFINED, SSC_OS_LAUNCHED, or -1 with a fixed message in `err`: the caller must
 * then exit without running anything.
 */
int ssc_os_lockdown(int *argc, char ***argv, int *exit_code, char *err, size_t err_len);

#endif
