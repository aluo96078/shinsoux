/*
 * shinsou-sandbox-core (S0 spike): one QuickJS runtime per extension package.
 *
 * The core never touches files, sockets, processes or the environment; QuickJS is built
 * without quickjs-libc. Every capability reaches the host through the callbacks below, which a
 * transport (helper process pipe, Android binder, WASM host import) implements.
 *
 * Threading: one runtime is driven by one thread. Only ssc_cancel() may be called from another
 * thread.
 */
#ifndef SSC_CORE_H
#define SSC_CORE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct ssc_runtime ssc_runtime;

/* bridge_call result: the host cut the wait short (cancel or deadline), see ssc_host. */
#define SSC_BRIDGE_ABORT 2

/* Host policy (ADR-0002 §7). The sandboxed script cannot change any of these. */
typedef struct {
    size_t heap_limit_bytes;      /* JS_SetMemoryLimit */
    size_t stack_limit_bytes;     /* JS_SetMaxStackSize */
    uint64_t init_timeout_ms;     /* bootstrap + script evaluation */
    uint64_t invoke_timeout_ms;   /* wall time per hook invocation, including async waits */
    uint32_t max_bridge_calls;    /* sync + async bridge calls per invocation */
    uint32_t max_async_inflight;  /* bridge.net.fetch requests started at once */
    uint32_t max_async_queue;     /* requests waiting inside the sandbox */
    size_t max_result_bytes;      /* JSON result of one invocation */
    uint32_t dom_max_nodes;       /* per parsed document, enforced inside the sandbox */
    uint32_t dom_max_depth;
} ssc_limits;

/* Stable failure codes; the host never receives engine messages or stack traces. */
typedef enum {
    SSC_OK = 0,
    SSC_ERR_SCRIPT = 1,          /* script threw */
    SSC_ERR_LIMIT_MEMORY = 2,
    SSC_ERR_LIMIT_STACK = 3,
    SSC_ERR_LIMIT_TIME = 4,
    SSC_ERR_LIMIT_BRIDGE = 5,
    SSC_ERR_LIMIT_RESULT = 6,
    SSC_ERR_CANCELLED = 7,
    SSC_ERR_NO_SUCH_HOOK = 8,
    SSC_ERR_PROMISE_STALLED = 9, /* hook promise pending with no async work left */
    SSC_ERR_INTERNAL = 10,
    SSC_ERR_BAD_ARGUMENT = 11,
} ssc_status;

const char *ssc_status_name(ssc_status status);

/*
 * Host-owned source-failure markers (SourceFailureMarker.kt): a script that throws (or rejects
 * with) an Error whose own `message` is exactly one of these names fails with SSC_ERR_SCRIPT and
 * this marker (a thrown string that is exactly the name counts too), so the host can show fixed
 * guidance. No other script text ever leaves the sandbox.
 */
typedef enum {
    SSC_SOURCE_FAILURE_NONE = 0,
    SSC_SOURCE_FAILURE_HTTP_CHALLENGE = 1,
    SSC_SOURCE_FAILURE_HTTP_BLOCKED = 2,
    SSC_SOURCE_FAILURE_HTTP_FORBIDDEN = 3,
    SSC_SOURCE_FAILURE_HTTP_UNAVAILABLE = 4,
    SSC_SOURCE_FAILURE_QUOTA_EXCEEDED = 5,
} ssc_source_failure;

typedef struct {
    void *opaque;
    /* Monotonic milliseconds. The sandbox has no other clock source of its own. */
    uint64_t (*now_ms)(void *opaque);
    /*
     * Synchronous bridge call. args_json is a JSON array. On success the host stores a
     * malloc()ed JSON value in *out (owned by the core afterwards) and returns 0. A non-zero
     * return makes the JS call yield null, matching the legacy bridge contract.
     */
    int (*bridge_call)(void *opaque, const char *method, const char *args_json, size_t args_len,
                       char **out, size_t *out_len);
    /* bridge_call returns SSC_BRIDGE_ABORT when the wait was cut short by a cancel or the
     * deadline: the invocation then ends at once (CANCELLED or LIMIT_TIME), uncatchably. */
    /*
     * Starts async op `op_id`; the result must later arrive through ssc_deliver_async().
     * request_json is valid only for the duration of the call. The host may deliver synchronously
     * from inside this callback.
     */
    int (*async_start)(void *opaque, uint32_t op_id, const char *request_json, size_t len);
    /* Tells the host to drop an op (invocation ended, cancelled or timed out). */
    void (*async_cancel)(void *opaque, uint32_t op_id);
    /*
     * Blocks until at least one async result was delivered with ssc_deliver_async(), or the
     * deadline passes. Returns the number of results delivered (0 on timeout).
     */
    int (*async_wait)(void *opaque, ssc_runtime *rt, uint64_t deadline_ms);
    /* bridge.log/console.*: the JSON array of arguments, handled locally (never a bridge call). */
    void (*log)(void *opaque, const char *message, size_t len);
} ssc_host;

ssc_runtime *ssc_new(const ssc_limits *limits, const ssc_host *host);
void ssc_free(ssc_runtime *rt);

/*
 * Evaluates the compatibility bootstrap and the extension script as invocation `gen` (non-zero,
 * unique per invocation; the frame driver uses the protocol call id).
 */
ssc_status ssc_init(ssc_runtime *rt, uint64_t gen, const char *bootstrap, size_t bootstrap_len,
                    const char *script, size_t script_len);

/*
 * Calls `source[method](...args)` as invocation `gen` (non-zero, unique per invocation); methods
 * starting with `__shinsou` are the bootstrap's global entry points instead (ADR-0002 §17.3).
 * method and args_json are (pointer, length) pairs and need no NUL terminator; args_json must be
 * a JSON array. A returned promise is driven
 * to settlement. On SSC_OK, *result_json is a malloc()ed JSON string owned by the caller.
 * The result is serialized with every host port closed, and any job still queued afterwards is
 * discarded, so nothing from one invocation can run in the next. SSC_ERR_INTERNAL means the
 * runtime is unusable (for example a script kept re-queueing jobs) and must be recreated.
 */
ssc_status ssc_invoke(ssc_runtime *rt, uint64_t gen, const char *method, size_t method_len,
                      const char *args_json, size_t args_len, char **result_json, size_t *result_len);

/*
 * Resolves (ok != 0, payload = JSON) or rejects (ok == 0, payload = the host's stable error code
 * name, e.g. "NETWORK_FAILED") an async op. Must run on the runtime's thread. The payload does not
 * need to be NUL-terminated; the core copies it.
 */
int ssc_deliver_async(ssc_runtime *rt, uint32_t op_id, int ok, const char *payload_json,
                      size_t len);

/*
 * Thread-safe: makes invocation `gen` fail with SSC_ERR_CANCELLED, whether it is running or has
 * not started yet. A cancel for any other generation has no effect.
 */
void ssc_cancel(ssc_runtime *rt, uint64_t gen);

/* The marker of the last init/invoke when it ended with SSC_ERR_SCRIPT, else NONE. */
ssc_source_failure ssc_last_source_failure(const ssc_runtime *rt);

/* Tests only: raises the DOM handle/input/memory budgets for parity runs over large pages. */
void ssc_set_dom_test_limits(ssc_runtime *rt, uint32_t max_handles, uint32_t max_input_bytes, size_t max_memory_bytes);

/* Diagnostics for the S0 measurements. */
size_t ssc_memory_used(ssc_runtime *rt);
uint32_t ssc_bridge_calls(ssc_runtime *rt);

#ifdef __cplusplus
}
#endif

#endif
