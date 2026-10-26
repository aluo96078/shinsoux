/*
 * shinsou-sandbox (ADR-0002 S0, macOS helper): drives one ssc_runtime over stdin/stdout frames.
 * Frame = u32 big-endian length + UTF-8 JSON object, <= 1 MiB. See macos/README.md for the schema.
 *
 * Threads: the main thread owns the runtime and is the only stdout writer. A reader thread is the only stdin
 * reader; it handles Cancel (ssc_cancel) and EOF (_exit) immediately and queues every other frame.
 */
#define _DARWIN_C_SOURCE
#include <errno.h>
#include <pthread.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <time.h>
#include <unistd.h>

#include "mini_json.h"
#include "sandbox_apply.h"
#include "shinsou_sandbox_core.h"

#define MAX_FRAME (1u << 20)
#define MAX_QUEUE_BYTES (8u << 20)
#define LOG_MAX_ENTRIES 256
#define LOG_MAX_BYTES (256u << 10)

enum { T_OTHER, T_INIT, T_INVOKE, T_BRIDGE_RESULT, T_ASYNC_RESULT, T_ASYNC_CANCEL };

typedef struct qframe {
    struct qframe *next;
    char *buf;
    size_t len;
    int type;
} qframe;

static pthread_mutex_t mu = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t cv = PTHREAD_COND_INITIALIZER;
static qframe *q_head, *q_tail;
static size_t q_bytes;
static int eof_seen;

static ssc_runtime *g_rt;
static int running;              /* an invocation is active */
static uint64_t cur_call;
static int cancel_flag;          /* Cancel received for the active invocation */
static uint64_t pre_cancel[16];  /* Cancels that arrived while idle (late or early) */
static unsigned pre_cancel_n;
static uint64_t cur_deadline_ms; /* wall deadline of the active invocation incl. grace */
static uint64_t bridge_seq;
static uint64_t g_invoke_timeout_ms = 30000;
static unsigned log_entries;
static size_t log_bytes;
static int diag_budget = 32;

static void diag(const char *fmt, ...) {
    if (diag_budget-- <= 0) return;
    char b[256];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(b, sizeof b - 1, fmt, ap);
    va_end(ap);
    if (n < 0) return;
    if (n > (int)sizeof b - 2) n = sizeof b - 2;
    b[n++] = '\n';
    (void)!write(2, b, (size_t)n);
}

static uint64_t now_ms(void *o) {
    (void)o;
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000 + (uint64_t)ts.tv_nsec / 1000000;
}

/* ---- output ---- */

typedef struct { char *p; size_t n, cap; int oom; } sb;
static void sb_put(sb *b, const char *s, size_t n) {
    if (b->oom) return;
    if (b->n + n + 1 > b->cap) {
        size_t c = b->cap ? b->cap * 2 : 256;
        while (c < b->n + n + 1) c *= 2;
        char *np = realloc(b->p, c);
        if (!np) { b->oom = 1; return; }
        b->p = np; b->cap = c;
    }
    memcpy(b->p + b->n, s, n);
    b->n += n;
    b->p[b->n] = 0;
}
static void sb_str(sb *b, const char *s) { sb_put(b, s, strlen(s)); }
static void sb_u64(sb *b, uint64_t v) { char t[24]; int n = snprintf(t, sizeof t, "%llu", (unsigned long long)v); sb_put(b, t, (size_t)n); }
static void sb_jstr(sb *b, const char *s, size_t n) {
    sb_put(b, "\"", 1);
    for (size_t i = 0; i < n; i++) {
        unsigned char c = (unsigned char)s[i];
        if (c == '"' || c == '\\') { char t[2] = {'\\', (char)c}; sb_put(b, t, 2); }
        else if (c < 0x20) { char t[8]; int k = snprintf(t, sizeof t, "\\u%04x", c); sb_put(b, t, (size_t)k); }
        else sb_put(b, (char *)&c, 1);
    }
    sb_put(b, "\"", 1);
}

static void write_all(const char *p, size_t n) {
    while (n) {
        ssize_t w = write(1, p, n);
        if (w < 0) { if (errno == EINTR) continue; _exit(1); }
        p += w; n -= (size_t)w;
    }
}

/* Sends one frame (body is a complete JSON object). Returns 0, or -1 if it exceeds the frame limit. */
static int send_frame(const sb *b) {
    if (b->oom || b->n > MAX_FRAME) return -1;
    unsigned char hdr[4] = {(unsigned char)(b->n >> 24), (unsigned char)(b->n >> 16), (unsigned char)(b->n >> 8), (unsigned char)b->n};
    char *msg = malloc(b->n + 4);
    if (!msg) return -1;
    memcpy(msg, hdr, 4);
    memcpy(msg + 4, b->p, b->n);
    write_all(msg, b->n + 4);
    free(msg);
    return 0;
}

static void send_failure(uint64_t call_id, const char *code) {
    sb b = {0};
    sb_str(&b, "{\"t\":\"Failure\",\"callId\":"); sb_u64(&b, call_id);
    sb_str(&b, ",\"code\":"); sb_jstr(&b, code, strlen(code)); sb_str(&b, "}");
    send_frame(&b);
    free(b.p);
}

/* ---- queue / reader thread ---- */

static int classify(span obj) {
    span t;
    if (!mj_get(obj, "t", &t) || t.n < 3) return T_OTHER;
    char *s = mj_string(t, NULL);
    int r = T_OTHER;
    if (s) {
        if (!strcmp(s, "Init")) r = T_INIT;
        else if (!strcmp(s, "Invoke")) r = T_INVOKE;
        else if (!strcmp(s, "BridgeResult")) r = T_BRIDGE_RESULT;
        else if (!strcmp(s, "AsyncBridgeResult")) r = T_ASYNC_RESULT;
        else if (!strcmp(s, "AsyncCancel")) r = T_ASYNC_CANCEL;
        else if (!strcmp(s, "Cancel")) r = -1;
    }
    free(s);
    return r;
}

static int read_full(unsigned char *p, size_t n) {
    while (n) {
        ssize_t r = read(0, p, n);
        if (r == 0) return -1;
        if (r < 0) { if (errno == EINTR) continue; return -1; }
        p += r; n -= (size_t)r;
    }
    return 0;
}

static void *reader_main(void *arg) {
    (void)arg;
    int exit_code = 0;
    for (;;) {
        unsigned char hdr[4];
        if (read_full(hdr, 4)) break; /* EOF: host is gone */
        size_t len = ((size_t)hdr[0] << 24) | ((size_t)hdr[1] << 16) | ((size_t)hdr[2] << 8) | hdr[3];
        if (len == 0 || len > MAX_FRAME) { diag("frame length %zu out of range", len); exit_code = 1; break; }
        char *buf = malloc(len);
        if (!buf || read_full((unsigned char *)buf, len)) { free(buf); break; }
        if (!mj_validate(buf, len)) { diag("malformed json frame"); free(buf); exit_code = 1; break; }
        span obj = {buf, len};
        int type = classify(obj);
        if (type == -1) { /* Cancel */
            span id;
            uint64_t call = 0;
            if (mj_get(obj, "callId", &id) && mj_u64(id, &call)) {
                pthread_mutex_lock(&mu);
                if (running && call == cur_call) { cancel_flag = 1; ssc_cancel(g_rt); }
                else pre_cancel[pre_cancel_n++ % 16] = call;
                pthread_cond_broadcast(&cv);
                pthread_mutex_unlock(&mu);
            }
            free(buf);
            continue;
        }
        qframe *f = calloc(1, sizeof *f);
        if (!f) { free(buf); break; }
        f->buf = buf; f->len = len; f->type = type;
        pthread_mutex_lock(&mu);
        if (q_bytes + len > MAX_QUEUE_BYTES) { pthread_mutex_unlock(&mu); diag("queue overflow"); free(buf); free(f); exit_code = 1; break; }
        q_bytes += len;
        if (q_tail) q_tail->next = f; else q_head = f;
        q_tail = f;
        pthread_cond_broadcast(&cv);
        pthread_mutex_unlock(&mu);
    }
    /* Host closed stdin or violated the protocol: stop whatever is running and leave without cleanup. */
    _exit(exit_code);
}

static void qf_free(qframe *f) { free(f->buf); free(f); }

/* mu must be held. match(f, ctx) != 0 selects the frame. */
static qframe *q_take(int (*match)(qframe *, void *), void *ctx) {
    qframe *prev = NULL;
    for (qframe *f = q_head; f; prev = f, f = f->next) {
        if (!match(f, ctx)) continue;
        if (prev) prev->next = f->next; else q_head = f->next;
        if (q_tail == f) q_tail = prev;
        f->next = NULL;
        q_bytes -= f->len;
        return f;
    }
    return NULL;
}

/* Waits on cv until abs deadline_ms (monotonic). Returns 0 normally, 1 when the deadline already passed. */
static int wait_until(uint64_t deadline_ms) {
    uint64_t now = now_ms(NULL);
    if (now >= deadline_ms) return 1;
    uint64_t rem = deadline_ms - now;
    struct timespec ts = {(time_t)(rem / 1000), (long)(rem % 1000) * 1000000L};
    pthread_cond_timedwait_relative_np(&cv, &mu, &ts);
    return 0;
}

/* ---- host callbacks (main thread) ---- */

static int stop_requested(void) { return cancel_flag || eof_seen; }

/* Re-asserts a Cancel that raced with the flag reset at the start of ssc_invoke. */
static void recheck_cancel(void) {
    pthread_mutex_lock(&mu);
    int c = cancel_flag;
    pthread_mutex_unlock(&mu);
    if (c) ssc_cancel(g_rt);
}

static int match_bridge_result(qframe *f, void *ctx) {
    if (f->type != T_BRIDGE_RESULT) return 0;
    span o = {f->buf, f->len}, s;
    uint64_t seq;
    if (!mj_get(o, "seq", &s) || !mj_u64(s, &seq)) return 1; /* malformed: consume and ignore */
    return !ctx || seq == *(uint64_t *)ctx; /* ctx == NULL: purge every result */
}

static void log_cb(void *o, const char *m, size_t len);

static int bridge_call(void *o, const char *method, const char *args, size_t args_len, char **out, size_t *out_len) {
    (void)o;
    recheck_cancel();
    if (!mj_validate(args, args_len)) return -1;
    if (!strcmp(method, "log")) { /* bridge.log / console.*: bounded Log frame, no host round trip */
        log_cb(NULL, args, args_len);
        *out = strdup("null");
        *out_len = 4;
        return *out ? 0 : -1;
    }
    uint64_t seq = ++bridge_seq;
    sb b = {0};
    sb_str(&b, "{\"t\":\"BridgeCall\",\"callId\":"); sb_u64(&b, cur_call);
    sb_str(&b, ",\"seq\":"); sb_u64(&b, seq);
    sb_str(&b, ",\"op\":"); sb_jstr(&b, method, strlen(method));
    sb_str(&b, ",\"args\":"); sb_put(&b, args, args_len); sb_str(&b, "}");
    int rc = send_frame(&b);
    free(b.p);
    if (rc) return -1;
    qframe *f = NULL;
    pthread_mutex_lock(&mu);
    while (!(f = q_take(match_bridge_result, &seq)) && !stop_requested() && now_ms(NULL) < cur_deadline_ms) wait_until(cur_deadline_ms);
    pthread_mutex_unlock(&mu);
    if (!f) return -1;
    int ret = -1;
    span obj = {f->buf, f->len}, okv, val, sq;
    uint64_t got;
    if (mj_get(obj, "seq", &sq) && mj_u64(sq, &got) && got == seq && mj_get(obj, "ok", &okv) && okv.n == 4 && !memcmp(okv.p, "true", 4) && mj_get(obj, "value", &val)) {
        *out = malloc(val.n + 1);
        if (*out) { memcpy(*out, val.p, val.n); (*out)[val.n] = 0; *out_len = val.n; ret = 0; }
    }
    qf_free(f);
    return ret;
}

static int async_start(void *o, uint32_t op_id, const char *req, size_t len) {
    (void)o;
    recheck_cancel();
    if (!mj_validate(req, len)) return -1;
    sb b = {0};
    sb_str(&b, "{\"t\":\"AsyncBridgeCall\",\"callId\":"); sb_u64(&b, cur_call);
    sb_str(&b, ",\"opId\":"); sb_u64(&b, op_id);
    sb_str(&b, ",\"op\":\"net.fetch\",\"args\":"); sb_put(&b, req, len); sb_str(&b, "}");
    int rc = send_frame(&b);
    free(b.p);
    return rc;
}

/* Sandbox -> host: the op is no longer wanted (invocation ended / cancelled). */
static void async_cancel(void *o, uint32_t op_id) {
    (void)o;
    sb b = {0};
    sb_str(&b, "{\"t\":\"AsyncCancel\",\"callId\":"); sb_u64(&b, cur_call);
    sb_str(&b, ",\"opId\":"); sb_u64(&b, op_id); sb_str(&b, "}");
    send_frame(&b);
    free(b.p);
}

static int match_async(qframe *f, void *ctx) { (void)ctx; return f->type == T_ASYNC_RESULT || f->type == T_ASYNC_CANCEL; }

static int async_wait(void *o, ssc_runtime *rt, uint64_t deadline_ms) {
    (void)o;
    recheck_cancel();
    if (cur_deadline_ms && deadline_ms > cur_deadline_ms) deadline_ms = cur_deadline_ms;
    qframe *list = NULL, **tail = &list;
    pthread_mutex_lock(&mu);
    for (;;) {
        qframe *f;
        while ((f = q_take(match_async, NULL))) { *tail = f; tail = &f->next; }
        if (list || stop_requested() || now_ms(NULL) >= deadline_ms) break;
        wait_until(deadline_ms);
    }
    pthread_mutex_unlock(&mu);
    int delivered = 0;
    while (list) {
        qframe *f = list;
        list = f->next;
        span obj = {f->buf, f->len}, id, okv, val;
        uint64_t op;
        if (mj_get(obj, "opId", &id) && mj_u64(id, &op)) {
            int ok = f->type == T_ASYNC_RESULT && mj_get(obj, "ok", &okv) && okv.n == 4 && !memcmp(okv.p, "true", 4) && mj_get(obj, "value", &val);
            /* JS_ParseJSON needs a NUL after the buffer, which the in-frame span does not have. */
            char *payload = ok ? malloc(val.n + 1) : NULL;
            size_t plen = 4;
            if (ok && payload) { memcpy(payload, val.p, val.n); payload[val.n] = 0; plen = val.n; }
            else ok = 0;
            if (ssc_deliver_async(rt, (uint32_t)op, ok, payload ? payload : "null", plen) == 0) delivered++;
            free(payload);
        }
        qf_free(f);
    }
    return delivered;
}

static void log_cb(void *o, const char *m, size_t len) {
    (void)o;
    if (log_entries >= LOG_MAX_ENTRIES || log_bytes >= LOG_MAX_BYTES) return;
    if (len > 1024) len = 1024;
    log_entries++; log_bytes += len;
    sb b = {0};
    sb_str(&b, "{\"t\":\"Log\",\"callId\":"); sb_u64(&b, cur_call);
    sb_str(&b, ",\"message\":"); sb_jstr(&b, m, len); sb_str(&b, "}");
    send_frame(&b);
    free(b.p);
}

/* ---- frame handlers ---- */

static int match_cmd(qframe *f, void *ctx) { (void)f; (void)ctx; return 1; }

static uint64_t limit_u64(span lim, const char *key, uint64_t def) {
    span v; uint64_t r;
    return mj_get(lim, key, &v) && mj_u64(v, &r) ? r : def;
}

static void handle_init(qframe *f) {
    span obj = {f->buf, f->len}, bs, sc, ver;
    uint64_t v = 0;
    if (g_rt) { send_failure(0, "BAD_ARGUMENT"); return; }
    if (!mj_get(obj, "v", &ver) || !mj_u64(ver, &v) || v != 1) { send_failure(0, "BAD_PROTOCOL_VERSION"); return; } /* fail closed */
    size_t bl = 0, sl = 0;
    char *bootstrap = mj_get(obj, "bootstrap", &bs) ? mj_string(bs, &bl) : NULL;
    char *script = mj_get(obj, "script", &sc) ? mj_string(sc, &sl) : NULL;
    if (!bootstrap || !script) { free(bootstrap); free(script); send_failure(0, "BAD_ARGUMENT"); return; }
    span l = {"{}", 2};
    mj_get(obj, "limits", &l);
    ssc_limits lm = {
        .heap_limit_bytes = (size_t)limit_u64(l, "heapLimitBytes", 64u << 20),
        .stack_limit_bytes = (size_t)limit_u64(l, "stackLimitBytes", 1u << 20),
        .init_timeout_ms = limit_u64(l, "initTimeoutMs", 10000),
        .invoke_timeout_ms = limit_u64(l, "invokeTimeoutMs", 30000),
        .max_bridge_calls = (uint32_t)limit_u64(l, "maxBridgeCalls", 4096),
        .max_async_inflight = (uint32_t)limit_u64(l, "maxAsyncInflight", 6),
        .max_async_queue = (uint32_t)limit_u64(l, "maxAsyncQueue", 64),
        .max_result_bytes = (size_t)limit_u64(l, "maxResultBytes", 1u << 20),
        .dom_max_nodes = (uint32_t)limit_u64(l, "domMaxNodes", 100000),
        .dom_max_depth = (uint32_t)limit_u64(l, "domMaxDepth", 512),
    };
    g_invoke_timeout_ms = lm.invoke_timeout_ms;
    if (lm.max_result_bytes > MAX_FRAME - 4096) lm.max_result_bytes = MAX_FRAME - 4096; /* S0 has no chunk frames */
    ssc_host host = {NULL, now_ms, bridge_call, async_start, async_cancel, async_wait, log_cb};
    ssc_runtime *rt = ssc_new(&lm, &host);
    if (!rt) { free(bootstrap); free(script); send_failure(0, "INTERNAL"); return; }
    g_rt = rt;
    ssc_status st = ssc_init(rt, bootstrap, bl, script, sl);
    free(bootstrap); free(script);
    if (st != SSC_OK) { send_failure(0, ssc_status_name(st)); return; }
    sb b = {0};
    sb_str(&b, "{\"t\":\"InitOk\",\"v\":1}");
    send_frame(&b);
    free(b.p);
}

static void handle_invoke(qframe *f) {
    span obj = {f->buf, f->len}, idv, mv, av;
    uint64_t call = 0;
    if (!mj_get(obj, "callId", &idv) || !mj_u64(idv, &call)) return;
    char *method = mj_get(obj, "method", &mv) ? mj_string(mv, NULL) : NULL;
    if (!g_rt || !method) { free(method); send_failure(call, "BAD_ARGUMENT"); return; }
    span args = {"[]", 2};
    if (mj_get(obj, "args", &av)) args = av;
    char *args_z = malloc(args.n + 1);
    memcpy(args_z, args.p, args.n); args_z[args.n] = 0;

    pthread_mutex_lock(&mu);
    int pre = 0;
    for (unsigned i = 0; i < 16; i++) if (pre_cancel[i] == call) { pre = 1; pre_cancel[i] = 0; }
    if (!pre) {
        running = 1; cur_call = call; cancel_flag = 0; bridge_seq = 0; log_entries = 0; log_bytes = 0;
        cur_deadline_ms = now_ms(NULL) + g_invoke_timeout_ms; /* the host watchdog adds the grace period */
    }
    pthread_mutex_unlock(&mu);
    if (pre) { free(method); free(args_z); send_failure(call, ssc_status_name(SSC_ERR_CANCELLED)); return; }

    char *result = NULL;
    size_t rlen = 0;
    ssc_status st = ssc_invoke(g_rt, method, args_z, &result, &rlen);

    pthread_mutex_lock(&mu);
    running = 0; cur_call = 0; cur_deadline_ms = 0;
    /* Results for ops/calls of the finished invocation must not leak into the next one. */
    qframe *stale;
    while ((stale = q_take(match_async, NULL))) qf_free(stale);
    while ((stale = q_take(match_bridge_result, NULL))) qf_free(stale);
    pthread_mutex_unlock(&mu);

    pthread_mutex_lock(&mu);
    int was_cancelled = cancel_flag;
    cancel_flag = 0;
    pthread_mutex_unlock(&mu);
    if (st == SSC_OK && was_cancelled) st = SSC_ERR_CANCELLED; /* a Cancel that lost the race with completion still reports CANCELLED */
    if (st == SSC_OK && result && mj_validate(result, rlen)) {
        sb b = {0};
        sb_str(&b, "{\"t\":\"Result\",\"callId\":"); sb_u64(&b, call);
        sb_str(&b, ",\"value\":"); sb_put(&b, result, rlen); sb_str(&b, "}");
        if (send_frame(&b)) send_failure(call, ssc_status_name(SSC_ERR_LIMIT_RESULT));
        free(b.p);
    } else {
        send_failure(call, st == SSC_OK ? "INTERNAL" : ssc_status_name(st));
    }
    free(result); free(method); free(args_z);
}

extern char **environ;
static char *empty_env[] = {NULL};

int main(int argc, char **argv) {
    int use_seatbelt = 1;
    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--sandbox=none")) use_seatbelt = 0;       /* App Sandbox entitlement or tests */
        else if (!strcmp(argv[i], "--sandbox=seatbelt")) use_seatbelt = 1;
        else { diag("unknown argument"); return 2; }
    }
    signal(SIGPIPE, SIG_IGN);
    environ = empty_env; /* drop the host's environment; nothing in the sandbox needs it */
    { struct rlimit rl = {0, 0}; setrlimit(RLIMIT_CORE, &rl); }
    for (int fd = 3; fd < 256; fd++) close(fd);
    if (use_seatbelt) {
        char err[256];
        if (apply_seatbelt_sandbox(err, sizeof err)) { diag("sandbox_init failed: %s", err); return 3; }
    }
    pthread_t th;
    if (pthread_create(&th, NULL, reader_main, NULL)) return 4;
    pthread_detach(th);

    for (;;) {
        qframe *f;
        pthread_mutex_lock(&mu);
        while (!(f = q_take(match_cmd, NULL))) pthread_cond_wait(&cv, &mu);
        pthread_mutex_unlock(&mu);
        if (f->type == T_INIT) handle_init(f);
        else if (f->type == T_INVOKE) handle_invoke(f);
        qf_free(f);
    }
}
