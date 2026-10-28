/*
 * The iOS sandbox (ADR-0002 §4.5): sandbox-core compiled to wasm32 and run by the WAMR interpreter
 * inside the app, one instance per package. It speaks SandboxProtocol v1 like every other platform,
 * through the single-threaded driver (SSC_DRIVER_SINGLE_THREAD): WASM has no threads.
 *
 * The module's only imports are these four (checked by check-imports.sh); the WASI functions
 * wasi-libc would reach are defined below, so no file, socket or environment API is linked:
 *   env.host_read(buf, len, timeout_ms) -> i32   bytes, 0 at end of stream, -1 error, -2 timeout
 *   env.host_write(buf, len) -> i32              0, or -1 when the host is gone
 *   env.host_now_ms(clock) -> i64                0 wall clock, 1 monotonic
 *   env.host_timezone_offset(time_ms) -> i32     minutes, as Date.prototype.getTimezoneOffset
 */
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "ssc_driver.h"

#define IMPORT(name) __attribute__((import_module("env"), import_name(name)))
#define EXPORT(name) __attribute__((export_name(name), used))

IMPORT("host_read") int32_t host_read(void *buf, uint32_t len, int64_t timeout_ms);
IMPORT("host_write") int32_t host_write(const void *buf, uint32_t len);
IMPORT("host_now_ms") int64_t host_now_ms(int32_t clock);
IMPORT("host_timezone_offset") int32_t host_timezone_offset(int64_t time_ms);

extern const char ssc_bootstrap_js[];
extern const size_t ssc_bootstrap_js_len;

/* wasi-libc reaches the host only through these; time comes from host_now_ms, stdio goes nowhere. */
#define WASI_ERRNO_BADF 8
int32_t __imported_wasi_snapshot_preview1_clock_time_get(int32_t id, int64_t precision, int64_t *out) {
    (void)precision;
    *out = host_now_ms(id == 0 ? 0 : 1) * 1000000;
    return 0;
}
int32_t __imported_wasi_snapshot_preview1_fd_close(int32_t fd) { (void)fd; return WASI_ERRNO_BADF; }
int32_t __imported_wasi_snapshot_preview1_fd_fdstat_get(int32_t fd, void *st) { (void)fd; (void)st; return WASI_ERRNO_BADF; }
int32_t __imported_wasi_snapshot_preview1_fd_seek(int32_t fd, int64_t off, int32_t whence, void *out) {
    (void)fd; (void)off; (void)whence; (void)out;
    return WASI_ERRNO_BADF;
}
int32_t __imported_wasi_snapshot_preview1_fd_write(int32_t fd, const void *iov, int32_t n, void *out) {
    (void)fd; (void)iov; (void)n; (void)out;
    return WASI_ERRNO_BADF;
}

/* quickjs-ng (patches/quickjs-wasi-timezone.patch): local dates follow the device's time zone. */
int ssc_wasm_timezone_offset(int64_t time_ms) { return host_timezone_offset(time_ms); }

static long read_wait(void *o, uint8_t *buf, size_t len, uint64_t timeout_ms) {
    (void)o;
    if (len > 0x7fffffff) len = 0x7fffffff;
    int32_t n = host_read(buf, (uint32_t)len, (int64_t)timeout_ms);
    return n == -2 ? SSC_IO_TIMEOUT : (long)n;
}

static long read_blocking(void *o, uint8_t *buf, size_t len) {
    for (;;) {
        long n = read_wait(o, buf, len, 1000);
        if (n != SSC_IO_TIMEOUT) return n;
    }
}

static int write_all(void *o, const uint8_t *buf, size_t len) {
    (void)o;
    while (len) {
        uint32_t chunk = len > (1u << 20) ? (1u << 20) : (uint32_t)len;
        if (host_write(buf, chunk) != 0) return -1;
        buf += chunk;
        len -= chunk;
    }
    return 0;
}

static uint64_t now_ms(void *o) { (void)o; return (uint64_t)host_now_ms(1); }

/* Runs until the host closes the stream; returns the driver's exit status (SSC_DRIVER_*). */
EXPORT("ssc_wasm_run") int32_t ssc_wasm_run(void) {
    ssc_io io = { NULL, read_blocking, write_all, now_ms, read_wait };
    return ssc_driver_run(&io, ssc_bootstrap_js, ssc_bootstrap_js_len);
}
