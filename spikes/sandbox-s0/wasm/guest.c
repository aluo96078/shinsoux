/*
 * WASM guest wrapper for shinsou-sandbox-core (ADR-0002 §4.5).
 *
 * One module instance == one ssc_runtime. The only imports are:
 *   env.host_bridge(frame_ptr, frame_len, out_ptr, out_cap) -> i32   (frames, see wasm_frames.h)
 *   env.host_clock_ms(clock) -> i64                                   (time source: 0 wall, 1 monotonic)
 * The WASI imports wasi-libc would pull in are replaced by the stubs below, so the final module
 * has no wasi_snapshot_preview1 import at all (checked by wasm/check-imports.sh).
 */
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "shinsou_sandbox_core.h"
#include "wasm_frames.h"

#define IMPORT(name) __attribute__((import_module("env"), import_name(name)))
#define EXPORT(name) __attribute__((export_name(name), used))

IMPORT("host_bridge") int32_t host_bridge(const void *frame, uint32_t frame_len, void *out, uint32_t out_cap);
IMPORT("host_clock_ms") int64_t host_clock_ms(int32_t clock); /* 0 = wall clock, 1 = monotonic */

/*
 * wasi-libc reaches the host only through these __imported_* symbols (time via clock_time_get,
 * stdio via fd_*). Defining them here resolves the imports inside the module: no WASI file or
 * socket API is linked, and the clock comes from host_clock_ms.
 */
#define WASI_ERRNO_BADF 8
#define WASI_ERRNO_NOSYS 52
int32_t __imported_wasi_snapshot_preview1_clock_time_get(int32_t id, int64_t precision, int64_t *out) {
    (void)precision;
    *out = host_clock_ms(id == 0 ? 0 : 1) * 1000000;
    return 0;
}
int32_t __imported_wasi_snapshot_preview1_fd_close(int32_t fd) { (void)fd; return WASI_ERRNO_BADF; }
int32_t __imported_wasi_snapshot_preview1_fd_fdstat_get(int32_t fd, void *st) { (void)fd; (void)st; return WASI_ERRNO_BADF; }
int32_t __imported_wasi_snapshot_preview1_fd_seek(int32_t fd, int64_t off, int32_t whence, void *out) { (void)fd; (void)off; (void)whence; (void)out; return WASI_ERRNO_BADF; }
int32_t __imported_wasi_snapshot_preview1_fd_write(int32_t fd, const void *iov, int32_t n, void *out) { (void)fd; (void)iov; (void)n; (void)out; return WASI_ERRNO_BADF; }

static ssc_runtime *g_rt;
static char *g_result;
static size_t g_result_len;

/* Reply buffer, grown on demand. The host keeps an oversized reply until FETCH_REPLY. */
static uint8_t *g_reply;
static uint32_t g_reply_cap;

static uint8_t *g_frame;
static uint32_t g_frame_cap;

static uint8_t *frame_buf(uint32_t need) {
    if (need > g_frame_cap) {
        uint32_t cap = need < 4096 ? 4096 : need;
        uint8_t *p = realloc(g_frame, cap);
        if (!p) return NULL;
        g_frame = p;
        g_frame_cap = cap;
    }
    return g_frame;
}

static void put32(uint8_t *p, uint32_t v) { memcpy(p, &v, 4); }
static void put64(uint8_t *p, uint64_t v) { memcpy(p, &v, 8); }
static uint32_t get32(const uint8_t *p) { uint32_t v; memcpy(&v, p, 4); return v; }

/* Sends a frame; on success *reply points into g_reply (valid until the next call). */
static int32_t round_trip(const uint8_t *frame, uint32_t len, const uint8_t **reply) {
    if (!g_reply) {
        g_reply = malloc(4096);
        if (!g_reply) return -1;
        g_reply_cap = 4096;
    }
    int32_t n = host_bridge(frame, len, g_reply, g_reply_cap);
    if (n < 0) return -1;
    if ((uint32_t)n > g_reply_cap) {
        uint8_t *p = realloc(g_reply, (uint32_t)n);
        if (!p) return -1;
        g_reply = p;
        g_reply_cap = (uint32_t)n;
        uint8_t fetch = SW_FRAME_FETCH_REPLY;
        n = host_bridge(&fetch, 1, g_reply, g_reply_cap);
        if (n < 0 || (uint32_t)n > g_reply_cap) return -1;
    }
    *reply = g_reply;
    return n;
}

static uint64_t cb_now_ms(void *o) { (void)o; return (uint64_t)host_clock_ms(1); }

static int cb_bridge_call(void *o, const char *method, const char *args, size_t args_len, char **out, size_t *out_len) {
    (void)o;
    size_t mlen = strlen(method);
    uint8_t *f = frame_buf((uint32_t)(1 + 4 + mlen + args_len));
    if (!f) return 1;
    f[0] = SW_FRAME_BRIDGE_CALL;
    put32(f + 1, (uint32_t)mlen);
    memcpy(f + 5, method, mlen);
    memcpy(f + 5 + mlen, args, args_len);
    const uint8_t *reply;
    int32_t n = round_trip(f, (uint32_t)(5 + mlen + args_len), &reply);
    if (n < 1 || reply[0] != 0) return 1;      /* reply[0] = ok flag */
    char *copy = malloc((size_t)n);
    if (!copy) return 1;
    memcpy(copy, reply + 1, (size_t)n - 1);
    *out = copy;
    *out_len = (size_t)n - 1;
    return 0;
}

static int cb_async_start(void *o, uint32_t op_id, const char *req, size_t len) {
    (void)o;
    uint8_t *f = frame_buf((uint32_t)(5 + len));
    if (!f) return 1;
    f[0] = SW_FRAME_ASYNC_START;
    put32(f + 1, op_id);
    memcpy(f + 5, req, len);
    const uint8_t *reply;
    int32_t n = round_trip(f, (uint32_t)(5 + len), &reply);
    return (n >= 1 && reply[0] == 0) ? 0 : 1; /* returns immediately; result comes via WAIT */
}

static void cb_async_cancel(void *o, uint32_t op_id) {
    (void)o;
    uint8_t f[5];
    f[0] = SW_FRAME_ASYNC_CANCEL;
    put32(f + 1, op_id);
    const uint8_t *reply;
    round_trip(f, sizeof f, &reply);
}

/* Reply: repeated [u32 op][u8 ok][u32 len][payload]. Delivery stays on this (the runtime) thread. */
static int cb_async_wait(void *o, ssc_runtime *rt, uint64_t deadline_ms) {
    (void)o;
    uint8_t f[9];
    f[0] = SW_FRAME_ASYNC_WAIT;
    put64(f + 1, deadline_ms);
    const uint8_t *reply;
    int32_t n = round_trip(f, sizeof f, &reply);
    if (n < 0) return 0;
    int delivered = 0;
    uint32_t off = 0;
    while (off + 9 <= (uint32_t)n) {
        uint32_t op = get32(reply + off);
        int ok = reply[off + 4];
        uint32_t plen = get32(reply + off + 5);
        off += 9;
        if (plen > (uint32_t)n - off) break;
        /* JS_ParseJSON needs a NUL after the buffer; the frame has the next record there. */
        char *payload = malloc((size_t)plen + 1);
        if (!payload) break;
        memcpy(payload, reply + off, plen);
        payload[plen] = 0;
        if (ssc_deliver_async(rt, op, ok, payload, plen) == 0) delivered++;
        free(payload);
        off += plen;
    }
    return delivered;
}

static void cb_log(void *o, const char *m, size_t len) {
    (void)o;
    uint8_t *f = frame_buf((uint32_t)(1 + len));
    if (!f) return;
    f[0] = SW_FRAME_LOG;
    memcpy(f + 1, m, len);
    const uint8_t *reply;
    round_trip(f, (uint32_t)(1 + len), &reply);
}

EXPORT("sw_alloc") void *sw_alloc(uint32_t n) { return malloc(n ? n : 1); }
EXPORT("sw_free") void sw_free(void *p) { free(p); }

EXPORT("sw_new") int32_t sw_new(uint32_t heap, uint32_t stack, uint32_t init_ms, uint32_t invoke_ms,
                                uint32_t max_bridge, uint32_t inflight, uint32_t queue,
                                uint32_t max_result, uint32_t dom_nodes, uint32_t dom_depth) {
    ssc_limits l = {
        .heap_limit_bytes = heap, .stack_limit_bytes = stack, .init_timeout_ms = init_ms,
        .invoke_timeout_ms = invoke_ms, .max_bridge_calls = max_bridge,
        .max_async_inflight = inflight, .max_async_queue = queue, .max_result_bytes = max_result,
        .dom_max_nodes = dom_nodes, .dom_max_depth = dom_depth,
    };
    ssc_host h = { NULL, cb_now_ms, cb_bridge_call, cb_async_start, cb_async_cancel, cb_async_wait, cb_log };
    g_rt = ssc_new(&l, &h);
    return g_rt ? 0 : -1;
}

EXPORT("sw_init") int32_t sw_init(const char *boot, uint32_t boot_len, const char *script, uint32_t script_len) {
    return (int32_t)ssc_init(g_rt, boot, boot_len, script, script_len);
}

EXPORT("sw_invoke") int32_t sw_invoke(const char *method, const char *args) {
    free(g_result);
    g_result = NULL;
    g_result_len = 0;
    return (int32_t)ssc_invoke(g_rt, method, args, &g_result, &g_result_len);
}

EXPORT("sw_result_ptr") const char *sw_result_ptr(void) { return g_result; }
EXPORT("sw_result_len") uint32_t sw_result_len(void) { return (uint32_t)g_result_len; }
EXPORT("sw_memory_used") uint32_t sw_memory_used(void) { return (uint32_t)ssc_memory_used(g_rt); }
EXPORT("sw_bridge_calls") uint32_t sw_bridge_calls(void) { return ssc_bridge_calls(g_rt); }
EXPORT("sw_free_runtime") void sw_free_runtime(void) { ssc_free(g_rt); g_rt = NULL; }
