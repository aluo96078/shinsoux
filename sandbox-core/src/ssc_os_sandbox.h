/*
 * OS confinement of the desktop sandbox helper (ADR-0002 §4.4, S3). Called once, first thing in
 * main, before a single byte of input is read.
 */
#ifndef SSC_OS_SANDBOX_H
#define SSC_OS_SANDBOX_H

#include <stddef.h>

/*
 * Drops what the process inherited (environment, descriptors above stderr, core dumps) and, on
 * macOS, applies a deny-all Seatbelt profile with sandbox_init (irreversible). Returns 0, or -1 with
 * a fixed message in `err`: the caller must then exit without running anything.
 */
int ssc_os_lockdown(char *err, size_t err_len);

/* 1 when ssc_os_lockdown confines the process on this platform (macOS); 0 where it only hardens. */
int ssc_os_lockdown_confines(void);

#endif
