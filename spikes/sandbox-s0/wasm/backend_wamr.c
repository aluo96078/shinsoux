/* Backend: shinsou_sandbox.wasm inside WAMR (interpreter flavour fixed at WAMR build time). */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "backend.h"
#include "wamr_host.h"

#ifndef SW_WAMR_FLAVOUR
#define SW_WAMR_FLAVOUR "wamr"
#endif

struct backend {
    sw_instance *inst;
    mock_net *net;
};

static sw_module *g_module;
static int g_engine_ready;

const char *be_name(void) { return SW_WAMR_FLAVOUR; }

static int cb_bridge(void *u, const char *m, size_t ml, const char *a, size_t al, char **out, size_t *ol) {
    return mock_bridge(((backend *)u)->net, m, ml, a, al, out, ol);
}
static int cb_start(void *u, uint32_t op, const char *r, size_t l) { return mock_async_start(((backend *)u)->net, op, r, l); }
static void cb_cancel(void *u, uint32_t op) { mock_async_cancel(((backend *)u)->net, op); }
static int cb_wait(void *u, uint64_t deadline, sw_async_result *res, int max) {
    mock_result tmp[32];
    int n = mock_async_wait(((backend *)u)->net, deadline, tmp, max > 32 ? 32 : max);
    for (int i = 0; i < n; i++) {
        res[i].op = tmp[i].op; res[i].ok = tmp[i].ok; res[i].payload = tmp[i].payload; res[i].len = tmp[i].len;
    }
    return n;
}

static int ensure_module(void) {
    if (g_module) return 0;
    if (!g_engine_ready) {
        if (sw_engine_init() != 0) return -1;
        g_engine_ready = 1;
    }
    const char *path = getenv("SW_WASM");
    if (!path) path = "shinsou_sandbox.wasm";
    FILE *f = fopen(path, "rb");
    if (!f) { perror(path); return -1; }
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    uint8_t *buf = malloc((size_t)n);
    if (fread(buf, 1, (size_t)n, f) != (size_t)n) return -1;
    fclose(f);
    char err[256];
    g_module = sw_module_load(buf, (size_t)n, err, sizeof err);
    free(buf);
    if (!g_module) { fprintf(stderr, "load: %s\n", err); return -1; }
    return 0;
}

int be_create(backend **out, const be_limits *l, mock_net *net) {
    if (ensure_module() != 0) return -1;
    backend *b = calloc(1, sizeof *b);
    b->net = net;
    sw_limits sl = {
        .heap = (uint32_t)l->heap, .stack = (uint32_t)l->stack, .init_ms = l->init_ms, .invoke_ms = l->invoke_ms,
        .max_bridge = l->max_bridge, .inflight = l->inflight, .queue = l->queue,
        .max_result = (uint32_t)l->max_result, .dom_nodes = l->dom_nodes, .dom_depth = l->dom_depth,
        .linear_max_bytes = (uint32_t)l->linear_max, .wasm_stack_bytes = (uint32_t)l->wasm_stack,
        .watchdog_ms = l->watchdog_ms,
    };
    if (getenv("SW_WASM_STACK")) sl.wasm_stack_bytes = (uint32_t)atoi(getenv("SW_WASM_STACK"));
    sw_callbacks cb = { b, cb_bridge, cb_start, cb_cancel, cb_wait, NULL };
    char err[256] = "";
    b->inst = sw_instance_create(g_module, &sl, &cb, err, sizeof err);
    if (!b->inst) { fprintf(stderr, "instance: %s\n", err); free(b); return -1; }
    *out = b;
    return 0;
}

/* The core is not linked on the host side; names mirror ssc_status_name(). */
const char *be_status_name(int s) {
    static const char *const names[] = { "OK", "SCRIPT_ERROR", "LIMIT_MEMORY", "LIMIT_STACK", "LIMIT_TIME", "LIMIT_BRIDGE",
        "LIMIT_RESULT", "CANCELLED", "NO_SUCH_HOOK", "PROMISE_STALLED", "INTERNAL", "BAD_ARGUMENT" };
    if (s == SW_TRAPPED) return "TRAPPED";
    return (unsigned)s < sizeof names / sizeof names[0] ? names[s] : "UNKNOWN";
}
int be_init(backend *b, const char *bo, size_t bl, const char *s, size_t sl) { return sw_instance_init(b->inst, bo, bl, s, sl); }
int be_invoke(backend *b, const char *m, const char *a, char **o, size_t *ol) { return sw_instance_invoke(b->inst, m, a, o, ol); }
void be_cancel(backend *b) { sw_instance_terminate(b->inst); }
int be_alive(backend *b) { return sw_instance_alive(b->inst); }
size_t be_js_heap(backend *b) { return sw_instance_js_heap(b->inst); }
size_t be_footprint(backend *b) { return sw_instance_linear_bytes(b->inst); }
uint32_t be_bridge_calls(backend *b) { return sw_instance_bridge_calls(b->inst); }
void be_destroy(backend *b) { sw_instance_destroy(b->inst); free(b); }
void be_thread_attach(void) { if (ensure_module() == 0) sw_thread_attach(); }
void be_thread_detach(void) { sw_thread_detach(); }
