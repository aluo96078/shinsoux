/*
 * WAMR embedder for shinsou_sandbox.wasm (ADR-0002 §4.5): one sw_instance per extension package.
 * Interpreter only (classic or fast, chosen at WAMR build time); no JIT/AOT.
 */
#ifndef SW_WAMR_HOST_H
#define SW_WAMR_HOST_H

#include <stddef.h>
#include <stdint.h>

#define SW_TRAPPED 100 /* guest trapped / was terminated: discard and rebuild the instance */

typedef struct {
    uint32_t op;
    int ok;
    char *payload; /* malloc()ed by the callback; the embedder frees it */
    size_t len;
} sw_async_result;

/* The Host side of SandboxProtocol: everything the guest asks for ends up here. */
typedef struct {
    void *user;
    int (*bridge)(void *u, const char *method, size_t mlen, const char *args, size_t alen, char **out, size_t *olen);
    int (*async_start)(void *u, uint32_t op, const char *req, size_t len); /* must return at once */
    void (*async_cancel)(void *u, uint32_t op);
    /* Blocks the sandbox thread until >= 1 result or the monotonic deadline; returns count. */
    int (*async_wait)(void *u, uint64_t deadline_ms, sw_async_result *res, int max);
    void (*log)(void *u, const char *msg, size_t len);
} sw_callbacks;

typedef struct {
    uint32_t heap, stack, init_ms, invoke_ms, max_bridge, inflight, queue, max_result, dom_nodes, dom_depth;
    uint32_t linear_max_bytes; /* WAMR-enforced linear memory ceiling (0 = module's declared max) */
    uint32_t wasm_stack_bytes; /* interpreter stack */
    uint32_t watchdog_ms;      /* hard terminate this long after a call starts (0 = invoke_ms + 2000) */
} sw_limits;

typedef struct sw_module sw_module;
typedef struct sw_instance sw_instance;

int sw_engine_init(void);           /* once per process; registers host imports */
void sw_engine_shutdown(void);
void sw_thread_attach(void);        /* every thread that runs instances */
void sw_thread_detach(void);
sw_module *sw_module_load(const uint8_t *bytes, size_t len, char *err, size_t err_len);
void sw_module_free(sw_module *m);

sw_instance *sw_instance_create(sw_module *m, const sw_limits *l, const sw_callbacks *cb, char *err, size_t err_len);
int sw_instance_init(sw_instance *i, const char *boot, size_t boot_len, const char *script, size_t script_len);
int sw_instance_invoke(sw_instance *i, const char *method, const char *args, char **out, size_t *out_len);
void sw_instance_terminate(sw_instance *i);  /* any thread */
int sw_instance_alive(sw_instance *i);
size_t sw_instance_js_heap(sw_instance *i);
size_t sw_instance_linear_bytes(sw_instance *i);
uint32_t sw_instance_bridge_calls(sw_instance *i);
const char *sw_instance_last_exception(sw_instance *i);
void sw_instance_destroy(sw_instance *i);

#endif
