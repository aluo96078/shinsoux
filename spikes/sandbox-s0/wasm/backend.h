/* Backend abstraction so one scenario/bench source runs against native QuickJS and WASM. */
#ifndef SW_BACKEND_H
#define SW_BACKEND_H

#include <stddef.h>
#include <stdint.h>

#include "mock.h"
#include "shinsou_sandbox_core.h"

#define SW_ERR_TRAPPED 100 /* WASM trap or terminate: instance is dead and must be rebuilt */

typedef struct {
    size_t heap, stack;
    uint32_t init_ms, invoke_ms, max_bridge, inflight, queue;
    size_t max_result;
    uint32_t dom_nodes, dom_depth;
    /* WASM only */
    size_t linear_max;       /* linear memory ceiling in bytes (0 = module default) */
    size_t wasm_stack;       /* interpreter stack bytes */
    uint32_t watchdog_ms;    /* hard-terminate deadline per call (0 = invoke_ms + grace) */
} be_limits;

typedef struct backend backend;

const char *be_name(void);
int be_create(backend **out, const be_limits *l, mock_net *net);
const char *be_status_name(int status);
int be_init(backend *b, const char *boot, size_t boot_len, const char *script, size_t script_len);
int be_invoke(backend *b, const char *method, const char *args, char **out, size_t *out_len);
void be_cancel(backend *b);      /* any thread: native = ssc_cancel, WASM = wasm_runtime_terminate */
int be_alive(backend *b);
size_t be_js_heap(backend *b);   /* bytes of JS heap in use */
size_t be_footprint(backend *b); /* WASM linear memory bytes (native: 0) */
uint32_t be_bridge_calls(backend *b);
void be_destroy(backend *b);
void be_thread_attach(void);
void be_thread_detach(void);

#endif
