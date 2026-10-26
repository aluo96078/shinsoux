/* Backend: shinsou-sandbox-core linked natively (the "原生 QuickJS" baseline). */
#include <stdlib.h>
#include <string.h>

#include "backend.h"

struct backend {
    ssc_runtime *rt;
    mock_net *net;
};

const char *be_name(void) { return "native"; }

static uint64_t cb_now(void *o) { (void)o; return mock_now_ms(); }

static int cb_bridge(void *o, const char *m, const char *a, size_t al, char **out, size_t *ol) {
    backend *b = o;
    return mock_bridge(b->net, m, strlen(m), a, al, out, ol);
}
static int cb_start(void *o, uint32_t op, const char *r, size_t l) { return mock_async_start(((backend *)o)->net, op, r, l); }
static void cb_cancel(void *o, uint32_t op) { mock_async_cancel(((backend *)o)->net, op); }

static int cb_wait(void *o, ssc_runtime *rt, uint64_t deadline) {
    backend *b = o;
    mock_result res[64];
    int n = mock_async_wait(b->net, deadline, res, 64), delivered = 0;
    for (int i = 0; i < n; i++) {
        if (ssc_deliver_async(rt, res[i].op, res[i].ok, res[i].payload, res[i].len) == 0) delivered++;
        free(res[i].payload);
    }
    return delivered;
}
static void cb_log(void *o, const char *m, size_t l) { (void)o; (void)m; (void)l; }

int be_create(backend **out, const be_limits *l, mock_net *net) {
    backend *b = calloc(1, sizeof *b);
    b->net = net;
    ssc_limits lim = {
        .heap_limit_bytes = l->heap, .stack_limit_bytes = l->stack, .init_timeout_ms = l->init_ms,
        .invoke_timeout_ms = l->invoke_ms, .max_bridge_calls = l->max_bridge, .max_async_inflight = l->inflight,
        .max_async_queue = l->queue, .max_result_bytes = l->max_result, .dom_max_nodes = l->dom_nodes,
        .dom_max_depth = l->dom_depth,
    };
    ssc_host host = { b, cb_now, cb_bridge, cb_start, cb_cancel, cb_wait, cb_log };
    b->rt = ssc_new(&lim, &host);
    if (!b->rt) { free(b); return -1; }
    *out = b;
    return 0;
}

const char *be_status_name(int s) { return ssc_status_name((ssc_status)s); }
int be_init(backend *b, const char *bo, size_t bl, const char *s, size_t sl) { return ssc_init(b->rt, bo, bl, s, sl); }
int be_invoke(backend *b, const char *m, const char *a, char **o, size_t *ol) { return ssc_invoke(b->rt, m, a, o, ol); }
void be_cancel(backend *b) { ssc_cancel(b->rt); }
int be_alive(backend *b) { (void)b; return 1; }
size_t be_js_heap(backend *b) { return ssc_memory_used(b->rt); }
size_t be_footprint(backend *b) { (void)b; return 0; }
uint32_t be_bridge_calls(backend *b) { return ssc_bridge_calls(b->rt); }
void be_destroy(backend *b) { ssc_free(b->rt); free(b); }
void be_thread_attach(void) {}
void be_thread_detach(void) {}
