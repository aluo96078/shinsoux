/*
 * Backend (beyond the S0 brief, reference only): the same shinsou_sandbox.wasm translated to C
 * ahead of time with wasm2c and compiled into the app binary. It is NOT a JIT (no runtime code
 * generation), so it would be allowed on iOS. Memory isolation comes from wasm2c's bounds checks;
 * there is no way to preempt the guest, so terminate is cooperative: the next host_clock_ms call
 * (made by QuickJS's interrupt handler) traps.
 */
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "backend.h"
#include "frame_host.h"
#include "sandbox.h"
#include "wasm-rt.h"
#include "wasm-rt-exceptions.h"
#include "wasm-rt-impl.h"
#include <unistd.h>

struct w2c_env {
    sw_frame_host fh;
    w2c_sandbox sb;
    atomic_int dead;
    atomic_int terminate;
};

typedef struct { void (*fn)(void *); void *arg; int done; } job;

struct backend {
    struct w2c_env env;
    mock_net *net;
    be_limits lim;
    pthread_t worker;
    pthread_mutex_t mu;
    pthread_cond_t cv;
    job *pending;
    int quit;
    int trapped;
    /* watchdog */
    pthread_t wd;
    uint64_t wd_deadline;
    int wd_quit;
};

const char *be_name(void) { return "wasm2c"; }

static uint64_t now_ms(void) { return mock_now_ms(); }

/* ---- imports ------------------------------------------------------------------------------ */

u64 w2c_env_host_clock_ms(struct w2c_env *e, u32 clock) {
    if (atomic_load(&e->terminate)) wasm_rt_trap(WASM_RT_TRAP_UNREACHABLE);
    struct timespec ts;
    clock_gettime(clock == 0 ? CLOCK_REALTIME : CLOCK_MONOTONIC, &ts);
    return (u64)ts.tv_sec * 1000 + (u64)ts.tv_nsec / 1000000;
}

u32 w2c_env_host_bridge(struct w2c_env *e, u32 frame, u32 len, u32 out, u32 cap) {
    wasm_rt_memory_t *m = w2c_sandbox_memory(&e->sb);
    if ((uint64_t)frame + len > m->size || (uint64_t)out + cap > m->size) wasm_rt_trap(WASM_RT_TRAP_OOB);
    return (u32)sw_frame_dispatch(&e->fh, m->data + frame, len, m->data + out, cap);
}

/* ---- worker thread with a big native stack (generated C recurses on the host stack) ---------- */

static void *worker_main(void *p) {
    backend *b = p;
    wasm_rt_init_thread();
    pthread_mutex_lock(&b->mu);
    for (;;) {
        while (!b->pending && !b->quit) pthread_cond_wait(&b->cv, &b->mu);
        if (b->quit) break;
        job *j = b->pending;
        pthread_mutex_unlock(&b->mu);
        if (wasm_rt_impl_try() == 0) j->fn(j->arg); else b->trapped = 1;
        pthread_mutex_lock(&b->mu);
        j->done = 1;
        b->pending = NULL;
        pthread_cond_broadcast(&b->cv);
    }
    pthread_mutex_unlock(&b->mu);
    wasm_rt_free_thread();
    return NULL;
}

static int run_job(backend *b, void (*fn)(void *), void *arg) {
    job j = { fn, arg, 0 };
    b->trapped = 0;
    pthread_mutex_lock(&b->mu);
    b->pending = &j;
    pthread_cond_broadcast(&b->cv);
    while (!j.done) pthread_cond_wait(&b->cv, &b->mu);
    pthread_mutex_unlock(&b->mu);
    if (b->trapped) { atomic_store(&b->env.dead, 1); return -1; }
    return 0;
}

static void *wd_main(void *p) {
    backend *b = p;
    while (!b->wd_quit) {
        uint64_t d = b->wd_deadline;
        if (d && now_ms() >= d) {
            b->wd_deadline = 0;
            atomic_store(&b->env.terminate, 1);
            atomic_store(&b->env.dead, 1);
        }
        usleep(2000);
    }
    return NULL;
}

/* ---- backend API -------------------------------------------------------------------------- */

typedef struct { backend *b; u32 argv[10]; u32 ret; const char *boot; size_t bl; const char *sc; size_t sl; const char *method; const char *args; char *out; size_t out_len; } call_ctx;

static uint32_t put(backend *b, const void *d, size_t n) {
    uint32_t off = w2c_sandbox_sw_alloc(&b->env.sb, (u32)n + 1);
    memcpy(w2c_sandbox_memory(&b->env.sb)->data + off, d, n);
    w2c_sandbox_memory(&b->env.sb)->data[off + n] = 0;
    return off;
}

static void do_instantiate(void *p) {
    call_ctx *c = p;
    wasm2c_sandbox_instantiate(&c->b->env.sb, &c->b->env); /* runs _initialize on the worker */
}
static void do_new(void *p) {
    call_ctx *c = p;
    u32 *a = c->argv;
    c->ret = w2c_sandbox_sw_new(&c->b->env.sb, a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7], a[8], a[9]);
}
static void do_init(void *p) {
    call_ctx *c = p;
    uint32_t bo = put(c->b, c->boot, c->bl), sc = put(c->b, c->sc, c->sl);
    c->ret = w2c_sandbox_sw_init(&c->b->env.sb, bo, (u32)c->bl, sc, (u32)c->sl);
    w2c_sandbox_sw_free(&c->b->env.sb, bo);
    w2c_sandbox_sw_free(&c->b->env.sb, sc);
}
static void do_invoke(void *p) {
    call_ctx *c = p;
    backend *b = c->b;
    uint32_t m = put(b, c->method, strlen(c->method)), a = put(b, c->args, strlen(c->args));
    c->ret = w2c_sandbox_sw_invoke(&b->env.sb, m, a);
    if (c->ret == 0) {
        u32 rp = w2c_sandbox_sw_result_ptr(&b->env.sb), rl = w2c_sandbox_sw_result_len(&b->env.sb);
        if (rp) {
            c->out = malloc(rl + 1);
            memcpy(c->out, w2c_sandbox_memory(&b->env.sb)->data + rp, rl);
            c->out[rl] = 0;
            c->out_len = rl;
        }
    }
    w2c_sandbox_sw_free(&b->env.sb, m);
    w2c_sandbox_sw_free(&b->env.sb, a);
}

static int cb_bridge(void *u, const char *m, size_t ml, const char *a, size_t al, char **out, size_t *ol) {
    return mock_bridge(((backend *)u)->net, m, ml, a, al, out, ol);
}
static int cb_start(void *u, uint32_t op, const char *r, size_t l) { return mock_async_start(((backend *)u)->net, op, r, l); }
static void cb_cancel(void *u, uint32_t op) { mock_async_cancel(((backend *)u)->net, op); }
static int cb_wait(void *u, uint64_t deadline, sw_async_result *res, int max) {
    mock_result tmp[32];
    int n = mock_async_wait(((backend *)u)->net, deadline, tmp, max > 32 ? 32 : max);
    for (int i = 0; i < n; i++) { res[i].op = tmp[i].op; res[i].ok = tmp[i].ok; res[i].payload = tmp[i].payload; res[i].len = tmp[i].len; }
    return n;
}

int be_create(backend **out, const be_limits *l, mock_net *net) {
    static int rt_ready;
    if (!rt_ready) { wasm_rt_init(); rt_ready = 1; }
    backend *b = calloc(1, sizeof *b);
    b->net = net;
    b->lim = *l;
    pthread_mutex_init(&b->mu, NULL);
    pthread_cond_init(&b->cv, NULL);
    b->env.fh.cb = (sw_callbacks){ b, cb_bridge, cb_start, cb_cancel, cb_wait, NULL };
    b->env.fh.dead = &b->env.dead;
    pthread_attr_t at;
    pthread_attr_init(&at);
    pthread_attr_setstacksize(&at, 64u << 20);
    pthread_create(&b->worker, &at, worker_main, b);
    pthread_create(&b->wd, NULL, wd_main, b);
    call_ctx c = { .b = b };
    if (run_job(b, do_instantiate, &c) != 0) return -1;
    u32 v[10] = { (u32)l->heap, (u32)l->stack, l->init_ms, l->invoke_ms, l->max_bridge, l->inflight, l->queue, (u32)l->max_result, l->dom_nodes, l->dom_depth };
    memcpy(c.argv, v, sizeof v);
    if (run_job(b, do_new, &c) != 0 || c.ret != 0) return -1;
    *out = b;
    return 0;
}

const char *be_status_name(int s) {
    static const char *const names[] = { "OK", "SCRIPT_ERROR", "LIMIT_MEMORY", "LIMIT_STACK", "LIMIT_TIME", "LIMIT_BRIDGE",
        "LIMIT_RESULT", "CANCELLED", "NO_SUCH_HOOK", "PROMISE_STALLED", "INTERNAL", "BAD_ARGUMENT" };
    if (s == SW_ERR_TRAPPED) return "TRAPPED";
    return (unsigned)s < sizeof names / sizeof names[0] ? names[s] : "UNKNOWN";
}

int be_init(backend *b, const char *bo, size_t bl, const char *s, size_t sl) {
    call_ctx c = { .b = b, .boot = bo, .bl = bl, .sc = s, .sl = sl };
    b->wd_deadline = now_ms() + b->lim.init_ms + 2000;
    int rc = run_job(b, do_init, &c);
    b->wd_deadline = 0;
    return rc != 0 ? SW_ERR_TRAPPED : (int)c.ret;
}

int be_invoke(backend *b, const char *m, const char *a, char **o, size_t *ol) {
    *o = NULL; *ol = 0;
    if (atomic_load(&b->env.dead)) return SW_ERR_TRAPPED;
    call_ctx c = { .b = b, .method = m, .args = a };
    b->wd_deadline = now_ms() + (b->lim.watchdog_ms ? b->lim.watchdog_ms : b->lim.invoke_ms + 2000);
    int rc = run_job(b, do_invoke, &c);
    b->wd_deadline = 0;
    if (rc != 0 || atomic_load(&b->env.dead)) { atomic_store(&b->env.dead, 1); return SW_ERR_TRAPPED; }
    *o = c.out; *ol = c.out_len;
    return (int)c.ret;
}

void be_cancel(backend *b) { atomic_store(&b->env.terminate, 1); atomic_store(&b->env.dead, 1); }
int be_alive(backend *b) { return !atomic_load(&b->env.dead); }

static void do_mem(void *p) { call_ctx *c = p; c->ret = w2c_sandbox_sw_memory_used(&c->b->env.sb); }
static void do_calls(void *p) { call_ctx *c = p; c->ret = w2c_sandbox_sw_bridge_calls(&c->b->env.sb); }
size_t be_js_heap(backend *b) { call_ctx c = { .b = b }; if (atomic_load(&b->env.dead) || run_job(b, do_mem, &c)) return 0; return c.ret; }
uint32_t be_bridge_calls(backend *b) { call_ctx c = { .b = b }; if (atomic_load(&b->env.dead) || run_job(b, do_calls, &c)) return 0; return c.ret; }
size_t be_footprint(backend *b) { return w2c_sandbox_memory(&b->env.sb)->size; }

void be_destroy(backend *b) {
    pthread_mutex_lock(&b->mu);
    b->quit = 1;
    pthread_cond_broadcast(&b->cv);
    pthread_mutex_unlock(&b->mu);
    pthread_join(b->worker, NULL);
    b->wd_quit = 1;
    pthread_join(b->wd, NULL);
    wasm2c_sandbox_free(&b->env.sb);
    free(b->env.fh.pending);
    free(b);
}
void be_thread_attach(void) {}
void be_thread_detach(void) {}
