/*
 * Sandbox-side SandboxProtocol v1 driver (ADR-0002 §4.2, §17.3): turns the frame stream from the
 * host into ssc_core calls and the core's host callbacks into frames. Used by the desktop helper
 * (stdin/stdout) and by tests (socket pairs); the WASM build drives the core through host imports.
 *
 * Threads: a reader thread parses frames and applies Cancel immediately (ssc_cancel is
 * thread-safe); everything else, including every write, happens on the calling thread. Built with
 * SSC_DRIVER_SINGLE_THREAD (WASM has no threads) the driver pulls input itself (ssc_io.read_wait).
 */
#ifndef SSC_DRIVER_H
#define SSC_DRIVER_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    void *opaque;
    /* Blocking read: bytes read, 0 at end of stream, < 0 on error. Called on the reader thread. */
    long (*read)(void *opaque, uint8_t *buf, size_t len);
    /* Writes all bytes; 0 on success. Called on the driver thread only. */
    int (*write)(void *opaque, const uint8_t *buf, size_t len);
    /* Monotonic milliseconds. */
    uint64_t (*now_ms)(void *opaque);
    /*
     * Single-threaded builds only (SSC_DRIVER_SINGLE_THREAD, the iOS WASM guest): like read, but
     * returns SSC_IO_TIMEOUT when nothing arrives within timeout_ms (0 = just poll). There is no
     * reader thread there: the driver pulls input while it waits, and polls it from the interrupt
     * handler while a hook runs, so a Cancel still stops it at once.
     */
    long (*read_wait)(void *opaque, uint8_t *buf, size_t len, uint64_t timeout_ms);
} ssc_io;

#define SSC_IO_TIMEOUT (-2L)

enum {
    SSC_DRIVER_EOF = 0,        /* the host closed the stream: normal exit */
    SSC_DRIVER_VIOLATION = 2,  /* the host broke the protocol */
    SSC_DRIVER_IO_ERROR = 3,
    SSC_DRIVER_NOMEM = 4,
    SSC_DRIVER_LOCKDOWN = 5,   /* the helper could not confine itself and ran nothing */
};

/*
 * QuickJS trusts the stack limit it is given, so the thread that calls ssc_driver_run must have a
 * real stack of at least SSC_DRIVER_THREAD_STACK; Init frames asking for a stack limit above
 * SSC_DRIVER_MAX_STACK_LIMIT are refused (BAD_ARGUMENT).
 */
#define SSC_DRIVER_THREAD_STACK (16u << 20)
#define SSC_DRIVER_MAX_STACK_LIMIT (4u << 20)

/*
 * Runs until the host closes the stream. `bootstrap` is the compatibility bootstrap evaluated
 * before every script (the driver appends its own reserved entry points).
 */
int ssc_driver_run(const ssc_io *io, const char *bootstrap, size_t bootstrap_len);

#ifdef __cplusplus
}
#endif

#endif
