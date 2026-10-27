#include "ssc_driver.h"

#include <errno.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "ssc_core.h"
#include "ssc_frames.h"

/* Reserved entry point the host uses besides the bootstrap's own (ADR-0002 §17.3). */
static const char DRIVER_PRELUDE[] =
    "\n;globalThis.__shinsouHasFunction=function(n){"
    "return typeof source==='object'&&source!==null&&typeof source[String(n)]==='function';};\n";

/* ssc_status and the host's error codes as the names scripts see (SandboxErrorCode). */
static const char *code_name(uint32_t code) {
    switch (code) {
    case 1: return "SCRIPT_ERROR";
    case 2: return "LIMIT_MEMORY";
    case 3: return "LIMIT_STACK";
    case 4: return "LIMIT_TIME";
    case 5: return "LIMIT_BRIDGE";
    case 6: return "LIMIT_RESULT";
    case 7: return "CANCELLED";
    case 8: return "NO_SUCH_HOOK";
    case 9: return "PROMISE_STALLED";
    case 10: return "INTERNAL";
    case 11: return "BAD_ARGUMENT";
    case 32: return "UNSUPPORTED_PROTOCOL";
    case 33: return "PERMISSION_DENIED";
    case 34: return "UNKNOWN_OPERATION";
    case 35: return "HOST_FAILURE";
    case 36: return "NETWORK_FAILED";
    case 37: return "REVOKED";
    default: return "HOST_FAILURE";
    }
}

static bool is_limit_code(uint32_t code) { return code >= 2 && code <= 6; }

/* ---- frame queue (reader thread -> driver thread) --------------------------------------- */

typedef struct node {
    ssc_frame frame;
    struct node *next;
} node;

typedef struct {
    /* Copied: the reader may outlive ssc_driver_run (it can be stuck in read() after a
     * violation), so it must not point into the caller's stack. Freed by the last of the two
     * threads to finish (refs). */
    ssc_io io_copy;
    const ssc_io *io;
    _Atomic int refs;
    pthread_mutex_t lock;
    pthread_cond_t cond;
    node *head, *tail;
    bool eof;
    int reader_status;
    /* Guarded by lock: the reader applies Cancel directly, and teardown clears it under the same
     * lock before freeing the runtime, so a Cancel can never reach a freed runtime. */
    ssc_runtime *rt;
    /* Frames queued by the reader (guarded by lock): a host flooding frames while a hook runs
     * must not grow sandbox memory past these budgets. */
    size_t queued_count;
    size_t queued_bytes;
    _Atomic uint64_t current_call;
    uint64_t deadline_ms; /* of the running init/invoke: bounds synchronous bridge waits */
    /* driver-thread state */
    uint64_t seq; /* monotonic for the whole session: a stale BridgeResult can never match a new call */
    node *stash_head, *stash_tail; /* async results that arrived while waiting for something else */
    size_t stash_count;
    size_t stash_bytes; /* charged while stashed; released when delivered or dropped */
    bool violated;
    bool write_failed;
} driver;

#define SSC_MAX_QUEUED_FRAMES 1024
#define SSC_MAX_QUEUED_BYTES (32u << 20)
#define SSC_MAX_STASHED_FRAMES 256
#define SSC_MAX_STASHED_BYTES (16u << 20)

static size_t frame_bytes(const ssc_frame *f) { return f->text.len + f->text2.len + sizeof(ssc_frame); }

static void on_frame(void *opaque, ssc_frame *f) {
    driver *d = opaque;
    node *n = malloc(sizeof *n);
    if (!n) {
        ssc_frame_free(f);
        pthread_mutex_lock(&d->lock);
        d->reader_status = SSC_DRIVER_NOMEM;
        pthread_mutex_unlock(&d->lock);
        return;
    }
    n->frame = *f;
    n->next = NULL;
    pthread_mutex_lock(&d->lock);
    if (f->type == SSC_F_CANCEL && d->rt) ssc_cancel(d->rt, f->call_id);
    if (d->queued_count >= SSC_MAX_QUEUED_FRAMES || d->queued_bytes + frame_bytes(f) > SSC_MAX_QUEUED_BYTES) {
        d->reader_status = SSC_DRIVER_VIOLATION; /* the host floods frames: stop reading */
        pthread_mutex_unlock(&d->lock);
        ssc_frame_free(&n->frame);
        free(n);
        return;
    }
    d->queued_count++;
    d->queued_bytes += frame_bytes(f);
    if (d->tail) d->tail->next = n; else d->head = n;
    d->tail = n;
    pthread_cond_broadcast(&d->cond);
    pthread_mutex_unlock(&d->lock);
}

static void release(driver *d) {
    if (atomic_fetch_sub(&d->refs, 1) != 1) return;
    for (node *n = d->head; n;) { node *next = n->next; ssc_frame_free(&n->frame); free(n); n = next; }
    pthread_mutex_destroy(&d->lock);
    pthread_cond_destroy(&d->cond);
    free(d);
}

static void *reader_main(void *opaque) {
    driver *d = opaque;
    ssc_field_limits limits;
    ssc_field_limits_default(&limits);
    ssc_reader *r = ssc_reader_new(SSC_PEER_SANDBOX, 16u << 20, &limits);
    uint8_t *buf = malloc(64 * 1024);
    int status = (!r || !buf) ? SSC_DRIVER_NOMEM : SSC_DRIVER_EOF;
    while (status == SSC_DRIVER_EOF) {
        long n = d->io->read(d->io->opaque, buf, 64 * 1024);
        if (n == 0) break;
        if (n < 0) { status = SSC_DRIVER_IO_ERROR; break; }
        if (ssc_reader_feed(r, buf, (size_t)n, on_frame, d) != SSC_FR_OK) status = SSC_DRIVER_VIOLATION;
        /* reader_status is shared with the driver thread: only ever touched under the lock. */
        pthread_mutex_lock(&d->lock);
        if (d->reader_status) status = d->reader_status;
        pthread_mutex_unlock(&d->lock);
    }
    if (status == SSC_DRIVER_EOF && r && !ssc_reader_idle(r)) status = SSC_DRIVER_VIOLATION; /* truncated */
    free(buf);
    ssc_reader_free(r);
    pthread_mutex_lock(&d->lock);
    d->eof = true;
    d->reader_status = status;
    pthread_cond_broadcast(&d->cond);
    pthread_mutex_unlock(&d->lock);
    release(d);
    return NULL;
}

/* Next frame, or NULL at end of stream / after `deadline_ms` (0 = no deadline). */
static node *next_frame(driver *d, uint64_t deadline_ms) {
    pthread_mutex_lock(&d->lock);
    while (!d->head && !d->eof) {
        if (deadline_ms == 0) {
            pthread_cond_wait(&d->cond, &d->lock);
            continue;
        }
        uint64_t now = d->io->now_ms(d->io->opaque);
        if (now >= deadline_ms) break;
        uint64_t wait = deadline_ms - now;
        struct timespec ts;
        clock_gettime(CLOCK_REALTIME, &ts);
        ts.tv_sec += (time_t)(wait / 1000);
        ts.tv_nsec += (long)(wait % 1000) * 1000000L;
        if (ts.tv_nsec >= 1000000000L) { ts.tv_sec++; ts.tv_nsec -= 1000000000L; }
        pthread_cond_timedwait(&d->cond, &d->lock, &ts);
    }
    node *n = d->head;
    if (n) {
        d->head = n->next;
        if (!d->head) d->tail = NULL;
        d->queued_count--;
        d->queued_bytes -= frame_bytes(&n->frame);
    }
    pthread_mutex_unlock(&d->lock);
    return n;
}

static void drop(node *n) {
    ssc_frame_free(&n->frame);
    free(n);
}

static bool stash(driver *d, node *n) {
    size_t bytes = frame_bytes(&n->frame);
    if (d->stash_count >= SSC_MAX_STASHED_FRAMES || d->stash_bytes + bytes > SSC_MAX_STASHED_BYTES) {
        d->violated = true; /* the host streams replies faster than the sandbox can use them */
        drop(n);
        return false;
    }
    d->stash_count++;
    d->stash_bytes += bytes;
    n->next = NULL;
    if (d->stash_tail) d->stash_tail->next = n; else d->stash_head = n;
    d->stash_tail = n;
    return true;
}

static void emit(driver *d, const ssc_frame *f) {
    if (d->write_failed) return;
    ssc_buf out = {0};
    if (ssc_encode_envelopes(f, &out) != SSC_FR_OK || d->io->write(d->io->opaque, out.buf, out.len) != 0) d->write_failed = true;
    ssc_buf_free(&out);
}

static ssc_slice slice_of(const char *p, size_t n) { return (ssc_slice){ (const uint8_t *)p, n }; }

/* ---- core host callbacks (driver thread) ------------------------------------------------ */

static uint64_t cb_now(void *o) {
    driver *d = o;
    return d->io->now_ms(d->io->opaque);
}

/* The host answers with a value (null allowed) or a stable code; scripts see the same JSON as on
 * the reviewed engines: the value, or {"error": CODE} (+ __shinsouResourceLimit for limits). */
static int cb_bridge(void *o, const char *method, const char *args, size_t args_len, char **out, size_t *out_len) {
    driver *d = o;
    ssc_frame call = { .type = SSC_F_BRIDGE_CALL, .call_id = atomic_load(&d->current_call), .id = ++d->seq };
    call.text = slice_of(method, strlen(method));
    call.text2 = slice_of(args, args_len);
    emit(d, &call);
    if (d->write_failed) return -1;
    for (;;) {
        /* Bounded by the invocation's own deadline; when it passes (or a Cancel arrives) the call
         * yields null and the core's interrupt handler ends the invocation (LIMIT_TIME/CANCELLED). */
        node *n = next_frame(d, d->deadline_ms ? d->deadline_ms : 1);
        if (!n) {
            pthread_mutex_lock(&d->lock);
            bool eof = d->eof && !d->head;
            pthread_mutex_unlock(&d->lock);
            return eof ? -1 : SSC_BRIDGE_ABORT; /* end of stream yields null; the deadline aborts */
        }
        ssc_frame *f = &n->frame;
        if (f->type == SSC_F_BRIDGE_RESULT) {
            if (f->id < call.id) { drop(n); continue; } /* answer to a call of a cancelled invocation */
            if (f->id != call.id) { d->violated = true; drop(n); return -1; }
            char *json;
            size_t len;
            if (f->ok && f->has_value) {
                len = f->text.len;
                json = malloc(len + 1);
                if (json) { memcpy(json, f->text.ptr, len); json[len] = 0; }
            } else if (f->ok) {
                json = strdup("null");
                len = 4;
            } else {
                char tmp[96];
                int k = snprintf(tmp, sizeof tmp, is_limit_code(f->code) ? "{\"error\":\"%s\",\"__shinsouResourceLimit\":true}" : "{\"error\":\"%s\"}", code_name(f->code));
                len = (size_t)k;
                json = strdup(tmp);
            }
            drop(n);
            if (!json) return -1;
            *out = json;
            *out_len = len;
            return 0;
        }
        if (f->type == SSC_F_ASYNC_BRIDGE_RESULT) { if (!stash(d, n)) return -1; continue; }
        if (f->type == SSC_F_CANCEL) {
            bool mine = f->call_id == call.call_id;
            drop(n);
            if (mine) return SSC_BRIDGE_ABORT; /* already applied by the reader: stop waiting */
            continue;
        }
        if (f->type == SSC_F_ASYNC_CANCEL) { drop(n); continue; }
        d->violated = true; /* Init/Invoke while a bridge call is pending */
        drop(n);
        return -1;
    }
}

static int cb_async_start(void *o, uint32_t op_id, const char *request, size_t len) {
    driver *d = o;
    static const char op[] = "net.fetch";
    ssc_frame call = { .type = SSC_F_ASYNC_BRIDGE_CALL, .call_id = atomic_load(&d->current_call), .id = op_id };
    call.text = slice_of(op, sizeof op - 1);
    call.text2 = slice_of(request, len);
    emit(d, &call);
    return d->write_failed ? -1 : 0;
}

/* The host cancels pending ops itself when the invocation ends (AsyncCancel); nothing to send. */
static void cb_async_cancel(void *o, uint32_t op_id) { (void)o; (void)op_id; }

static int deliver(ssc_runtime *rt, node *n) {
    ssc_frame *f = &n->frame;
    int rc;
    if (f->ok) {
        const char *json = f->has_value ? (const char *)f->text.ptr : "null";
        size_t len = f->has_value ? f->text.len : 4;
        rc = ssc_deliver_async(rt, (uint32_t)f->id, 1, json, len);
    } else {
        const char *name = code_name(f->code);
        rc = ssc_deliver_async(rt, (uint32_t)f->id, 0, name, strlen(name));
    }
    drop(n);
    return rc == 0 ? 1 : 0;
}

static int cb_async_wait(void *o, ssc_runtime *rt, uint64_t deadline_ms) {
    driver *d = o;
    int delivered = 0;
    while (d->stash_head) {
        node *n = d->stash_head;
        d->stash_head = n->next;
        if (!d->stash_head) d->stash_tail = NULL;
        d->stash_count--;
        d->stash_bytes -= frame_bytes(&n->frame);
        delivered += deliver(rt, n);
    }
    if (delivered) return delivered;
    node *n = next_frame(d, deadline_ms ? deadline_ms : 1);
    if (!n) return 0;
    switch (n->frame.type) {
    case SSC_F_ASYNC_BRIDGE_RESULT: return deliver(rt, n);
    case SSC_F_CANCEL: /* already applied by the reader; wake up so the core sees it */
    case SSC_F_ASYNC_CANCEL:
    case SSC_F_BRIDGE_RESULT: /* stale */
        drop(n);
        return 0;
    default:
        d->violated = true;
        drop(n);
        return 0;
    }
}

static void cb_log(void *o, const char *message, size_t len) {
    driver *d = o;
    if (len > 16 * 1024) len = 16 * 1024;
    ssc_frame f = { .type = SSC_F_LOG, .text = slice_of(message, len) };
    emit(d, &f);
}

/* ---- main loop -------------------------------------------------------------------------- */

static void reply(driver *d, uint64_t call_id, ssc_status st, ssc_source_failure marker, const char *json, size_t len) {
    if (d->violated) return; /* the host broke the protocol: it gets no further answers */
    ssc_frame f = { .call_id = call_id };
    if (st == SSC_OK) {
        f.type = SSC_F_RESULT;
        f.text = slice_of(json ? json : "null", json ? len : 4);
    } else {
        f.type = SSC_F_FAILURE;
        f.code = (uint32_t)st;
        f.marker = st == SSC_ERR_SCRIPT ? (uint32_t)marker : 0;
    }
    emit(d, &f);
}

int ssc_driver_run(const ssc_io *io, const char *bootstrap, size_t bootstrap_len) {
    driver *dp = calloc(1, sizeof *dp);
    if (!dp) return SSC_DRIVER_NOMEM;
    dp->io_copy = *io;
    dp->io = &dp->io_copy;
    atomic_init(&dp->refs, 2);
    pthread_mutex_init(&dp->lock, NULL);
    pthread_cond_init(&dp->cond, NULL);
    atomic_init(&dp->current_call, 0);
    pthread_t reader;
    if (pthread_create(&reader, NULL, reader_main, dp) != 0) {
        pthread_mutex_destroy(&dp->lock);
        pthread_cond_destroy(&dp->cond);
        free(dp);
        return SSC_DRIVER_NOMEM;
    }
#define d (*dp)

    ssc_runtime *rt = NULL;
    uint64_t invoke_timeout_ms = 0;
    int status = SSC_DRIVER_EOF;
    ssc_host host = { &d, cb_now, cb_bridge, cb_async_start, cb_async_cancel, cb_async_wait, cb_log };
    for (;;) {
        if (d.violated) { status = SSC_DRIVER_VIOLATION; break; }
        if (d.write_failed) { status = SSC_DRIVER_IO_ERROR; break; }
        node *n = next_frame(&d, 0);
        if (!n) break;
        ssc_frame *f = &n->frame;
        if (f->type == SSC_F_INIT) {
            if (rt) { d.violated = true; drop(n); continue; }
            ssc_frame ready = { .type = SSC_F_READY, .call_id = f->call_id, .version = SSC_PROTOCOL_VERSION };
            emit(&d, &ready);
            if (f->version != SSC_PROTOCOL_VERSION) { drop(n); continue; } /* the host fails closed on Ready */
            if (f->limits[1] > SSC_DRIVER_MAX_STACK_LIMIT) { reply(&d, f->call_id, SSC_ERR_BAD_ARGUMENT, SSC_SOURCE_FAILURE_NONE, NULL, 0); drop(n); continue; }
            ssc_limits lim = {
                .heap_limit_bytes = (size_t)f->limits[0],
                .stack_limit_bytes = (size_t)f->limits[1],
                .init_timeout_ms = f->limits[2],
                .invoke_timeout_ms = f->limits[3],
                .max_bridge_calls = (uint32_t)f->limits[4],
                .max_async_inflight = (uint32_t)f->limits[5],
                .max_async_queue = (uint32_t)f->limits[6],
                .max_result_bytes = (size_t)f->limits[7],
                .dom_max_nodes = (uint32_t)f->limits[8],
                .dom_max_depth = (uint32_t)f->limits[9],
            };
            invoke_timeout_ms = lim.invoke_timeout_ms;
            rt = ssc_new(&lim, &host);
            if (!rt) { reply(&d, f->call_id, SSC_ERR_INTERNAL, SSC_SOURCE_FAILURE_NONE, NULL, 0); drop(n); status = SSC_DRIVER_NOMEM; break; }
            pthread_mutex_lock(&d.lock);
            d.rt = rt;
            pthread_mutex_unlock(&d.lock);
            /* bootstrap + reserved entry points; the script itself is evaluated separately. */
            size_t blen = bootstrap_len + sizeof DRIVER_PRELUDE - 1;
            char *boot = malloc(blen);
            if (!boot) { reply(&d, f->call_id, SSC_ERR_INTERNAL, SSC_SOURCE_FAILURE_NONE, NULL, 0); drop(n); status = SSC_DRIVER_NOMEM; break; }
            memcpy(boot, bootstrap, bootstrap_len);
            memcpy(boot + bootstrap_len, DRIVER_PRELUDE, sizeof DRIVER_PRELUDE - 1);
            atomic_store(&d.current_call, f->call_id);
            d.deadline_ms = io->now_ms(io->opaque) + lim.init_timeout_ms;
            ssc_status st = ssc_init(rt, f->call_id, boot, blen, (const char *)f->text.ptr, f->text.len);
            free(boot);
            reply(&d, f->call_id, st, ssc_last_source_failure(rt), NULL, 0);
        } else if (f->type == SSC_F_INVOKE) {
            if (!rt) { d.violated = true; drop(n); continue; }
            atomic_store(&d.current_call, f->call_id);
            d.deadline_ms = io->now_ms(io->opaque) + invoke_timeout_ms;
            char *json = NULL;
            size_t len = 0;
            ssc_status st = ssc_invoke(rt, f->call_id, (const char *)f->text.ptr, f->text.len,
                                       (const char *)f->text2.ptr, f->text2.len, &json, &len);
            reply(&d, f->call_id, st, ssc_last_source_failure(rt), json, len);
            free(json);
            /* Results for this invocation's ops that arrive later are stale. */
            while (d.stash_head) { node *s = d.stash_head; d.stash_head = s->next; drop(s); }
            d.stash_tail = NULL;
            d.stash_count = 0;
            d.stash_bytes = 0;
            d.deadline_ms = 0;
        }
        /* Cancel, stale bridge/async results and AsyncCancel outside an invocation: nothing to do. */
        drop(n);
    }
    if (d.violated) status = SSC_DRIVER_VIOLATION;
    /* Unpublish under the reader's lock before freeing: no Cancel can reach a freed runtime. */
    pthread_mutex_lock(&d.lock);
    d.rt = NULL;
    pthread_mutex_unlock(&d.lock);
    ssc_free(rt);
    pthread_mutex_lock(&d.lock);
    bool reader_done = d.eof;
    int reader_status = d.reader_status;
    pthread_mutex_unlock(&d.lock);
    if (reader_done) {
        pthread_join(reader, NULL);
        if (status == SSC_DRIVER_EOF) status = reader_status;
    } else {
        /* Still blocked in read(): it frees the shared state when it wakes (the caller closes the
         * stream, or the process exits). */
        pthread_detach(reader);
    }
    while (d.stash_head) { node *n = d.stash_head; d.stash_head = n->next; drop(n); }
#undef d
    release(dp);
    return status;
}
