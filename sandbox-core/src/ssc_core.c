#include "ssc_core.h"

#include <stdatomic.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>

#include "quickjs.h"
#include "ssc_dom_js.h"

#define SSC_MAX_ASYNC_SLOTS 1024
/* Jobs left after an invocation are discarded by running them with every host port closed. A
 * script that keeps re-queueing jobs past this bound poisons the runtime instead of hanging. */
#define SSC_MAX_DISCARDED_JOBS 100000
/* Queued net.fetch requests live outside the QuickJS heap (and its limit) until the host takes
 * them: bound each one and their total so a script cannot use the queue to grow past its budget. */
#define SSC_MAX_ASYNC_REQUEST_BYTES (4u << 20)
#define SSC_MAX_QUEUED_REQUEST_BYTES (8u << 20)

typedef enum { STOP_NONE = 0, STOP_TIME, STOP_CANCEL } stop_reason;

typedef struct {
    bool used;
    bool started;
    uint32_t op_id;
    JSValue resolve;
    JSValue reject;
    char *request;
    size_t request_len;
} async_slot;

struct ssc_runtime {
    ssc_dom_js_owner owner; /* first: the JSContext opaque is read as ssc_dom_js_owner by the DOM */
    ssc_limits limits;
    ssc_host host;
    JSRuntime *rt;
    JSContext *ctx;
    uint64_t deadline_ms;
    /* Cancellation is per invocation generation (ADR-0002 §16.2): a cancel that arrives before its
     * invocation starts still applies, and one that arrives late cannot hit the next invocation. */
    _Atomic uint64_t cancel_gen;
    uint64_t current_gen;
    bool discarding; /* close_invocation is running leftover jobs: stop them all */
    stop_reason stop;
    bool open;     /* host ports usable: only while init/invoke is running user code */
    bool pumping;  /* pump_async_queue is running; nested calls leave the work to the outer loop */
    bool bridge_limit_hit; /* set by the core itself, so a script cannot forge LIMIT_BRIDGE */
    bool dom_limit_hit;    /* likewise for the DOM's node/depth/handle/memory budgets */
    bool poisoned; /* leftover jobs could not be discarded; the host must recreate the runtime */
    uint32_t bridge_calls;
    ssc_source_failure source_failure; /* set with SSC_ERR_SCRIPT only */
    uint32_t next_op_id;
    uint32_t inflight;
    uint32_t queued;
    size_t queued_request_bytes; /* bytes held by slot->request across all slots */
    async_slot slots[SSC_MAX_ASYNC_SLOTS];
};

static const char *const status_names[] = {
    "OK", "SCRIPT_ERROR", "LIMIT_MEMORY", "LIMIT_STACK", "LIMIT_TIME", "LIMIT_BRIDGE",
    "LIMIT_RESULT", "CANCELLED", "NO_SUCH_HOOK", "PROMISE_STALLED", "INTERNAL", "BAD_ARGUMENT",
};

const char *ssc_status_name(ssc_status status) {
    if ((unsigned)status < sizeof(status_names) / sizeof(status_names[0])) return status_names[status];
    return "UNKNOWN";
}

static bool cancelled(ssc_runtime *s) {
    return s->discarding || (s->current_gen != 0 && atomic_load(&s->cancel_gen) == s->current_gen);
}

static int interrupt_handler(JSRuntime *rt, void *opaque) {
    (void)rt;
    ssc_runtime *s = opaque;
    if (cancelled(s)) {
        s->stop = STOP_CANCEL;
        return 1;
    }
    if (s->deadline_ms && s->host.now_ms(s->host.opaque) > s->deadline_ms) {
        s->stop = STOP_TIME;
        return 1;
    }
    return 0;
}

static const char *const source_failure_names[] = {
    NULL, "SHINSOU_SOURCE_HTTP_CHALLENGE", "SHINSOU_SOURCE_HTTP_BLOCKED", "SHINSOU_SOURCE_HTTP_FORBIDDEN",
    "SHINSOU_SOURCE_HTTP_UNAVAILABLE", "SHINSOU_SOURCE_QUOTA_EXCEEDED",
};

static ssc_source_failure source_failure_named(const char *text, size_t len) {
    for (int i = 1; i <= (int)SSC_SOURCE_FAILURE_QUOTA_EXCEEDED; i++) {
        if (len == strlen(source_failure_names[i]) && memcmp(text, source_failure_names[i], len) == 0) return (ssc_source_failure)i;
    }
    return SSC_SOURCE_FAILURE_NONE;
}

/*
 * The thrown value is exactly a marker name: a string, or an Error whose own data property
 * `message` is one (no getter or toString runs). Rhino reports both forms the same way.
 */
static ssc_source_failure source_failure_of(JSContext *ctx, JSValueConst value) {
    ssc_source_failure marker = SSC_SOURCE_FAILURE_NONE;
    if (JS_IsString(value)) {
        size_t len;
        const char *text = JS_ToCStringLen(ctx, &len, value);
        if (!text) { JS_FreeValue(ctx, JS_GetException(ctx)); return marker; }
        marker = source_failure_named(text, len);
        JS_FreeCString(ctx, text);
        return marker;
    }
    /* JS_IsError tests the value's own class: a Proxy (even around an Error) is never inspected. */
    if (!JS_IsError(value)) return marker;
    JSAtom atom = JS_NewAtom(ctx, "message");
    JSPropertyDescriptor desc;
    int found = JS_GetOwnProperty(ctx, &desc, value, atom);
    JS_FreeAtom(ctx, atom);
    if (found == 1) {
        if (!(desc.flags & JS_PROP_GETSET) && JS_IsString(desc.value)) {
            size_t len;
            const char *text = JS_ToCStringLen(ctx, &len, desc.value);
            if (text) {
                marker = source_failure_named(text, len);
                JS_FreeCString(ctx, text);
            } else {
                JS_FreeValue(ctx, JS_GetException(ctx));
            }
        }
        JS_FreeValue(ctx, desc.value);
        JS_FreeValue(ctx, desc.getter);
        JS_FreeValue(ctx, desc.setter);
    } else if (found < 0) {
        JS_FreeValue(ctx, JS_GetException(ctx));
    }
    return marker;
}

/* Maps the pending exception to a stable code without exposing its text to the host. */
static ssc_status take_exception(ssc_runtime *s) {
    JSValue exc = JS_GetException(s->ctx);
    ssc_status status = SSC_ERR_SCRIPT;
    if (s->stop == STOP_CANCEL) {
        status = SSC_ERR_CANCELLED;
    } else if (s->stop == STOP_TIME) {
        status = SSC_ERR_LIMIT_TIME;
    } else if (s->dom_limit_hit) {
        status = SSC_ERR_LIMIT_MEMORY; /* the DOM is part of the memory budget (§7) */
    } else if (s->bridge_limit_hit) {
        status = SSC_ERR_LIMIT_BRIDGE;
    } else if (JS_IsError(exc)) {
        /* Read only an own data property: an inherited getter would run script code here. */
        JSAtom atom = JS_NewAtom(s->ctx, "message");
        JSPropertyDescriptor desc;
        int found = JS_GetOwnProperty(s->ctx, &desc, exc, atom);
        JS_FreeAtom(s->ctx, atom);
        if (found == 1) {
            if (!(desc.flags & JS_PROP_GETSET) && JS_IsString(desc.value)) {
                const char *text = JS_ToCString(s->ctx, desc.value);
                if (text) {
                    if (strcmp(text, "out of memory") == 0) status = SSC_ERR_LIMIT_MEMORY;
                    else if (strcmp(text, "Maximum call stack size exceeded") == 0) status = SSC_ERR_LIMIT_STACK;
                    JS_FreeCString(s->ctx, text);
                }
            }
            JS_FreeValue(s->ctx, desc.value);
            JS_FreeValue(s->ctx, desc.getter);
            JS_FreeValue(s->ctx, desc.setter);
        } else if (found < 0) {
            JS_FreeValue(s->ctx, JS_GetException(s->ctx));
        }
    } else if (JS_IsNull(exc) || JS_IsUndefined(exc)) {
        /* JS_ThrowOutOfMemory may leave a null exception when allocation of the error fails. */
        status = SSC_ERR_LIMIT_MEMORY;
    }
    if (status == SSC_ERR_SCRIPT) s->source_failure = source_failure_of(s->ctx, exc);
    JS_FreeValue(s->ctx, exc);
    return status;
}

/*
 * Error objects built by the core use own data properties: JS_SetPropertyStr would run a setter a
 * script installed on Error.prototype, i.e. arbitrary script code in the middle of core bookkeeping.
 */
static JSValue make_error(JSContext *ctx, const char *code) {
    JSValue err = JS_NewError(ctx);
    if (JS_IsException(err)) return err;
    JS_DefinePropertyValueStr(ctx, err, "message", JS_NewString(ctx, code), JS_PROP_C_W_E);
    JS_DefinePropertyValueStr(ctx, err, "code", JS_NewString(ctx, code), JS_PROP_C_W_E);
    return err;
}

static JSValue throw_bridge_limit(JSContext *ctx) {
    /* Uncatchable: a script must not swallow its own quota exhaustion and keep calling. */
    ssc_runtime *s = JS_GetContextOpaque(ctx);
    s->bridge_limit_hit = true;
    JSValue err = make_error(ctx, "__ssc_bridge_limit");
    if (JS_IsException(err)) return err;
    JS_SetUncatchableError(ctx, err);
    return JS_Throw(ctx, err);
}

static JSValue throw_closed(JSContext *ctx) {
    /* Uncatchable: code running outside an invocation (leftover jobs, toJSON) has no host access. */
    JSValue err = make_error(ctx, "__ssc_closed");
    if (JS_IsException(err)) return err;
    JS_SetUncatchableError(ctx, err);
    return JS_Throw(ctx, err);
}

static bool count_bridge_call(ssc_runtime *s) {
    if (s->bridge_calls >= s->limits.max_bridge_calls) return false;
    s->bridge_calls++;
    return true;
}

/* __shinsouBridge(method, argsJson) -> JSON string or null */
static JSValue js_bridge(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    (void)this_val;
    ssc_runtime *s = JS_GetContextOpaque(ctx);
    /* Strings only: converting anything else would call script toString/valueOf in the core. */
    if (argc < 2 || !JS_IsString(argv[0]) || !JS_IsString(argv[1])) return JS_NULL;
    const char *method = JS_ToCString(ctx, argv[0]);
    if (!method) return JS_EXCEPTION;
    /* bridge.log is a local Log frame (§4.2), not a bridge round trip or a bridge-quota call. */
    if (strcmp(method, "log") == 0) {
        if (!s->open) {
            JS_FreeCString(ctx, method);
            return JS_NULL;
        }
        size_t len = 0;
        const char *args = JS_ToCStringLen(ctx, &len, argv[1]);
        if (args && s->host.log) s->host.log(s->host.opaque, args, len);
        JS_FreeCString(ctx, args);
        JS_FreeCString(ctx, method);
        return JS_NULL;
    }
    /* DOM bookkeeping never crosses the boundary: the document lives in the sandbox (§5.2). */
    if (strncmp(method, "dom", 3) == 0) {
        JSValue local = JS_NULL;
        if (strcmp(method, "domParse") == 0) {
            char quota[96];
            int n = snprintf(quota, sizeof quota, "{\"maxNodes\":%u,\"maxDepth\":%u}",
                             s->limits.dom_max_nodes, s->limits.dom_max_depth);
            local = JS_NewStringLen(ctx, quota, (size_t)n);
        }
        JS_FreeCString(ctx, method);
        return local;
    }
    if (!s->open) {
        JS_FreeCString(ctx, method);
        return throw_closed(ctx);
    }
    if (!count_bridge_call(s)) {
        JS_FreeCString(ctx, method);
        return throw_bridge_limit(ctx);
    }
    size_t args_len = 0;
    const char *args = JS_ToCStringLen(ctx, &args_len, argv[1]);
    JSValue result = JS_NULL;
    int rc = -1;
    if (method && args) {
        char *out = NULL;
        size_t out_len = 0;
        rc = s->host.bridge_call(s->host.opaque, method, args, args_len, &out, &out_len);
        if (rc == 0 && out) result = JS_NewStringLen(ctx, out, out_len);
        free(out);
    }
    JS_FreeCString(ctx, method);
    JS_FreeCString(ctx, args);
    if (rc == SSC_BRIDGE_ABORT) {
        /* The host cut the wait short: end the invocation now rather than letting the script
         * continue with a null until the next interrupt check. */
        s->stop = cancelled(s) ? STOP_CANCEL : STOP_TIME;
        JSValue err = make_error(ctx, "__ssc_aborted");
        if (JS_IsException(err)) return err;
        JS_SetUncatchableError(ctx, err);
        return JS_Throw(ctx, err);
    }
    return result;
}

static async_slot *find_slot(ssc_runtime *s, uint32_t op_id) {
    for (int i = 0; i < SSC_MAX_ASYNC_SLOTS; i++) {
        if (s->slots[i].used && s->slots[i].op_id == op_id) return &s->slots[i];
    }
    return NULL;
}

/* Frees a slot's queued request copy and returns its bytes to the queue budget. */
static void drop_request(ssc_runtime *s, async_slot *slot) {
    if (slot->request) s->queued_request_bytes -= slot->request_len;
    free(slot->request);
    slot->request = NULL;
}

static void release_slot(ssc_runtime *s, async_slot *slot) {
    JS_FreeValue(s->ctx, slot->resolve);
    JS_FreeValue(s->ctx, slot->reject);
    drop_request(s, slot);
    memset(slot, 0, sizeof(*slot));
}

/*
 * Removes a slot from the table and hands its resolvers to the caller. Settling a promise can run
 * script code (a `then` getter) that re-enters the host, and the host can re-enter
 * ssc_deliver_async; once detached, the op can neither be found nor released a second time.
 */
static void detach_slot(ssc_runtime *s, async_slot *slot, JSValue *resolve, JSValue *reject) {
    *resolve = slot->resolve;
    *reject = slot->reject;
    drop_request(s, slot);
    memset(slot, 0, sizeof(*slot));
}

static void settle_detached(ssc_runtime *s, JSValue resolve, JSValue reject, bool ok, JSValue value) {
    JSValue ret = JS_Call(s->ctx, ok ? resolve : reject, JS_UNDEFINED, 1, (JSValueConst *)&value);
    JS_FreeValue(s->ctx, ret);
    JS_FreeValue(s->ctx, value);
    JS_FreeValue(s->ctx, resolve);
    JS_FreeValue(s->ctx, reject);
}

static JSValue error_value(JSContext *ctx, const char *code) { return make_error(ctx, code); }

/*
 * Starts queued requests while the in-flight budget allows (FIFO by op id). The budget is reserved
 * before async_start, which may re-enter ssc_deliver_async for this or any other op.
 */
static void pump_async_queue(ssc_runtime *s) {
    if (!s->open || s->pumping) return; /* never start host work outside an invocation */
    s->pumping = true;
    while (s->open && s->inflight < s->limits.max_async_inflight && s->queued > 0) {
        async_slot *next = NULL;
        for (int i = 0; i < SSC_MAX_ASYNC_SLOTS; i++) {
            async_slot *c = &s->slots[i];
            if (c->used && !c->started && (!next || c->op_id < next->op_id)) next = c;
        }
        if (!next) break;
        uint32_t op_id = next->op_id;
        char *request = next->request; /* owned here: the slot may be gone when async_start returns */
        size_t request_len = next->request_len;
        next->request = NULL;
        s->queued_request_bytes -= request_len;
        next->started = true;
        s->queued--;
        s->inflight++;
        int rc = s->host.async_start(s->host.opaque, op_id, request, request_len);
        free(request);
        if (rc != 0) {
            async_slot *still = find_slot(s, op_id); /* the host may already have settled it */
            if (still && still->started) {
                JSValue resolve, reject;
                detach_slot(s, still, &resolve, &reject);
                s->inflight--;
                settle_detached(s, resolve, reject, false, error_value(s->ctx, "NETWORK_UNAVAILABLE"));
            }
        }
    }
    s->pumping = false;
}

/* __shinsouAsync(requestJson) -> Promise */
static JSValue js_async(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    (void)this_val;
    ssc_runtime *s = JS_GetContextOpaque(ctx);
    if (!s->open) return throw_closed(ctx);
    /* Strings only, converted before any slot is chosen: no script code can run in between. */
    size_t len = 0;
    const char *request = (argc >= 1 && JS_IsString(argv[0])) ? JS_ToCStringLen(ctx, &len, argv[0]) : NULL;
    if (request && !count_bridge_call(s)) {
        JS_FreeCString(ctx, request);
        return throw_bridge_limit(ctx);
    }
    JSValue funcs[2];
    JSValue promise = JS_NewPromiseCapability(ctx, funcs);
    if (JS_IsException(promise)) {
        if (request) JS_FreeCString(ctx, request);
        return promise;
    }
    const char *reject_code = NULL;
    async_slot *slot = NULL;
    if (!request) {
        reject_code = "BAD_ARGUMENT";
    } else if (len > SSC_MAX_ASYNC_REQUEST_BYTES) {
        reject_code = "LIMIT_REQUEST";
    } else if (s->queued_request_bytes + len > SSC_MAX_QUEUED_REQUEST_BYTES) {
        reject_code = "ASYNC_QUEUE_FULL";
    } else if (s->inflight >= s->limits.max_async_inflight && s->queued >= s->limits.max_async_queue) {
        reject_code = "ASYNC_QUEUE_FULL";
    } else {
        for (int i = 0; i < SSC_MAX_ASYNC_SLOTS && !slot; i++)
            if (!s->slots[i].used) slot = &s->slots[i];
        if (!slot) reject_code = "ASYNC_QUEUE_FULL";
    }
    if (!reject_code) {
        slot->request = malloc(len + 1);
        if (!slot->request) reject_code = "OUT_OF_MEMORY";
    }
    if (reject_code) {
        if (request) JS_FreeCString(ctx, request);
        JSValue err = error_value(ctx, reject_code);
        JSValue ret = JS_Call(ctx, funcs[1], JS_UNDEFINED, 1, (JSValueConst *)&err);
        JS_FreeValue(ctx, ret);
        JS_FreeValue(ctx, err);
        JS_FreeValue(ctx, funcs[0]);
        JS_FreeValue(ctx, funcs[1]);
        return promise;
    }
    memcpy(slot->request, request, len);
    slot->request[len] = 0;
    slot->request_len = len;
    s->queued_request_bytes += len;
    JS_FreeCString(ctx, request);
    slot->used = true;
    slot->op_id = ++s->next_op_id;
    slot->resolve = funcs[0];
    slot->reject = funcs[1];
    s->queued++;
    pump_async_queue(s);
    return promise;
}

int ssc_deliver_async(ssc_runtime *s, uint32_t op_id, int ok, const char *payload, size_t len) {
    if (!s->open) return -1; /* invocation over: results can no longer queue JS work */
    async_slot *slot = find_slot(s, op_id);
    if (!slot || !slot->started) return -1; /* stale or cancelled op: drop silently */
    char *copy = NULL;
    if (ok) {
        /* JS_ParseJSON reads payload[len]; frames from a transport are not NUL-terminated. */
        copy = malloc(len + 1);
        if (!copy) return -1;
        memcpy(copy, payload, len);
        copy[len] = 0;
    }
    /* Detach before creating any JS value: nothing after this point can find the op again. */
    JSValue resolve, reject;
    detach_slot(s, slot, &resolve, &reject);
    s->inflight--;
    JSValue value;
    if (ok) {
        value = JS_ParseJSON(s->ctx, copy, len, "<async>");
        free(copy);
        if (JS_IsException(value)) {
            JS_FreeValue(s->ctx, JS_GetException(s->ctx));
            value = error_value(s->ctx, "BAD_RESPONSE");
            ok = 0;
        }
    } else {
        /* A failed op carries the host's stable error code name (ASCII), never host text. */
        char code[48] = "NETWORK_FAILED";
        if (len > 0 && len < sizeof code) {
            bool ascii = true;
            for (size_t i = 0; i < len; i++) if (payload[i] < 'A' || payload[i] > 'Z') { if (payload[i] != '_') ascii = false; }
            if (ascii) { memcpy(code, payload, len); code[len] = 0; }
        }
        value = error_value(s->ctx, code);
    }
    settle_detached(s, resolve, reject, ok != 0, value);
    pump_async_queue(s);
    return 0;
}

/* Cancels every pending op; results that arrive later are dropped by ssc_deliver_async. */
static void cancel_all_async(ssc_runtime *s) {
    /* Invalidate every slot before telling the host, so a host that re-enters ssc_deliver_async
     * from async_cancel finds nothing to settle and cannot queue work for a later invocation. */
    uint32_t started[SSC_MAX_ASYNC_SLOTS];
    int count = 0;
    for (int i = 0; i < SSC_MAX_ASYNC_SLOTS; i++) {
        async_slot *slot = &s->slots[i];
        if (!slot->used) continue;
        if (slot->started) started[count++] = slot->op_id;
        release_slot(s, slot);
    }
    s->inflight = 0;
    s->queued = 0;
    if (s->host.async_cancel)
        for (int i = 0; i < count; i++) s->host.async_cancel(s->host.opaque, started[i]);
}

/* bridge.net.fetch(url, opts): the request is the same JSON argument array as every bridge op. */
static const char ASYNC_SHIM[] =
    "(function(g){var native=g.__shinsouAsync;delete g.__shinsouAsync;"
    "g.bridge.net={fetch:function(url,opts){opts=opts||{};"
    "return native(JSON.stringify([String(url),{method:String(opts.method||'GET'),"
    "headers:opts.headers||{},body:opts.body==null?null:String(opts.body)}])).then(function(r){"
    "return{status:r.status,headers:r.headers||{},text:function(){return String(r.body==null?'':r.body);},"
    "json:function(){return JSON.parse(String(r.body));}};});}};"
    "Object.freeze(g.bridge.net);})(globalThis);";

ssc_runtime *ssc_new(const ssc_limits *limits, const ssc_host *host) {
    ssc_runtime *s = calloc(1, sizeof(*s));
    if (!s) return NULL;
    s->limits = *limits;
    s->host = *host;
    atomic_init(&s->cancel_gen, 0);
    s->rt = JS_NewRuntime();
    if (!s->rt) { free(s); return NULL; }
    JS_SetMemoryLimit(s->rt, limits->heap_limit_bytes);
    JS_SetMaxStackSize(s->rt, limits->stack_limit_bytes);
    JS_SetInterruptHandler(s->rt, interrupt_handler, s);
    s->ctx = JS_NewContext(s->rt);
    if (!s->ctx) { JS_FreeRuntime(s->rt); free(s); return NULL; }
    JS_SetContextOpaque(s->ctx, s);
    JSValue global = JS_GetGlobalObject(s->ctx);
    JS_SetPropertyStr(s->ctx, global, "__shinsouBridge", JS_NewCFunction(s->ctx, js_bridge, "__shinsouBridge", 2));
    JS_SetPropertyStr(s->ctx, global, "__shinsouAsync", JS_NewCFunction(s->ctx, js_async, "__shinsouAsync", 1));
    JS_FreeValue(s->ctx, global);
    s->owner.dom_js = ssc_dom_js_new(s->ctx, limits->dom_max_nodes, limits->dom_max_depth, &s->dom_limit_hit, &s->open);
    if (!s->owner.dom_js) {
        JS_FreeContext(s->ctx);
        JS_FreeRuntime(s->rt);
        free(s);
        return NULL;
    }
    return s;
}

void ssc_free(ssc_runtime *s) {
    if (!s) return;
    cancel_all_async(s);
    ssc_dom_js_free(s->owner.dom_js);
    JS_FreeContext(s->ctx);
    JS_FreeRuntime(s->rt);
    free(s);
}

static void begin(ssc_runtime *s, uint64_t gen, uint64_t timeout_ms) {
    JS_UpdateStackTop(s->rt);
    s->current_gen = gen;
    s->stop = STOP_NONE;
    s->bridge_calls = 0;
    s->source_failure = SSC_SOURCE_FAILURE_NONE;
    s->bridge_limit_hit = false;
    s->dom_limit_hit = false;
    s->deadline_ms = s->host.now_ms(s->host.opaque) + timeout_ms;
    s->open = true;
}

/*
 * Ends init/invoke: closes the host ports, discards every queued job (they run with cancel set and
 * no host access, so they cannot start work or reach the next invocation), then drops async ops.
 */
static void close_invocation(ssc_runtime *s) {
    s->open = false;
    s->discarding = true;
    uint32_t discarded = 0;
    while (JS_IsJobPending(s->rt)) {
        if (++discarded > SSC_MAX_DISCARDED_JOBS) {
            s->poisoned = true;
            break;
        }
        JSContext *job_ctx;
        if (JS_ExecutePendingJob(s->rt, &job_ctx) < 0) JS_FreeValue(s->ctx, JS_GetException(s->ctx));
    }
    cancel_all_async(s);
    /* Documents never outlive their invocation: the DOM budget is per invocation (§7). */
    ssc_dom_js_reset(s->owner.dom_js);
    s->deadline_ms = 0;
    s->discarding = false;
    s->current_gen = 0;
}

static ssc_status eval(ssc_runtime *s, const char *code, size_t len, const char *name) {
    /* JS_Eval requires a NUL-terminated buffer. */
    char *copy = malloc(len + 1);
    if (!copy) return SSC_ERR_INTERNAL;
    memcpy(copy, code, len);
    copy[len] = 0;
    JSValue v = JS_Eval(s->ctx, copy, len, name, JS_EVAL_TYPE_GLOBAL);
    free(copy);
    if (JS_IsException(v)) return take_exception(s);
    JS_FreeValue(s->ctx, v);
    return SSC_OK;
}

ssc_status ssc_init(ssc_runtime *s, uint64_t gen, const char *bootstrap, size_t bootstrap_len,
                    const char *script, size_t script_len) {
    if (gen == 0) return SSC_ERR_BAD_ARGUMENT;
    begin(s, gen, s->limits.init_timeout_ms);
    ssc_status st = eval(s, bootstrap, bootstrap_len, "shinsou-bootstrap.js");
    if (st == SSC_OK) st = eval(s, ASYNC_SHIM, sizeof(ASYNC_SHIM) - 1, "shinsou-async.js");
    if (st == SSC_OK) st = eval(s, SSC_DOM_SHIM, SSC_DOM_SHIM_LEN, "shinsou-dom.js");
    if (st == SSC_OK) st = eval(s, script, script_len, "plugin.js");
    close_invocation(s);
    if (st == SSC_OK && s->poisoned) st = SSC_ERR_INTERNAL;
    return st;
}

/* Runs queued promise jobs; returns false if a job raised an exception. */
static bool drain_jobs(ssc_runtime *s, ssc_status *failure) {
    for (;;) {
        JSContext *job_ctx = NULL;
        int r = JS_ExecutePendingJob(s->rt, &job_ctx);
        if (r == 0) return true;
        if (r < 0) {
            *failure = take_exception(s);
            return false;
        }
    }
}

static ssc_status stringify_result(ssc_runtime *s, JSValue value, char **out, size_t *out_len) {
    JSValue json = JS_JSONStringify(s->ctx, value, JS_UNDEFINED, JS_UNDEFINED);
    if (JS_IsException(json)) return take_exception(s);
    if (JS_IsUndefined(json)) { /* e.g. a hook returning undefined or a function */
        *out = strdup("null");
        if (!*out) return SSC_ERR_INTERNAL;
        *out_len = 4;
        return SSC_OK;
    }
    size_t len = 0;
    const char *text = JS_ToCStringLen(s->ctx, &len, json);
    JS_FreeValue(s->ctx, json);
    if (!text) return take_exception(s); /* UTF-8 conversion failed: out of memory */
    ssc_status st = SSC_OK;
    if (len > s->limits.max_result_bytes) {
        st = SSC_ERR_LIMIT_RESULT;
    } else {
        *out = malloc(len + 1);
        if (*out) {
            memcpy(*out, text, len);
            (*out)[len] = 0;
            *out_len = len;
        } else {
            st = SSC_ERR_INTERNAL;
        }
    }
    JS_FreeCString(s->ctx, text);
    return st;
}

static bool is_reserved(const char *method, size_t len) {
    static const char prefix[] = "__shinsou";
    return len > sizeof prefix - 1 && memcmp(method, prefix, sizeof prefix - 1) == 0;
}

ssc_status ssc_invoke(ssc_runtime *s, uint64_t gen, const char *method, size_t method_len,
                      const char *args_json, size_t args_len, char **result_json, size_t *result_len) {
    *result_json = NULL;
    *result_len = 0;
    if (s->poisoned) return SSC_ERR_INTERNAL;
    if (gen == 0 || method_len == 0 || method_len > 256 || memchr(method, 0, method_len)) return SSC_ERR_BAD_ARGUMENT;
    begin(s, gen, s->limits.invoke_timeout_ms);
    ssc_status st = SSC_OK;
    JSValue global = JS_GetGlobalObject(s->ctx);
    JSValue source = JS_UNDEFINED, fn = JS_UNDEFINED, result = JS_UNDEFINED, args = JS_UNDEFINED;
    JSAtom method_atom = JS_ATOM_NULL;
    /* Reserved bootstrap entry points (ADR-0002 §17.3) are globals; hooks live on `source`. */
    bool reserved = is_reserved(method, method_len);
    /* JS_ParseJSON reads buf[len]: copy into a NUL-terminated buffer. Parsed first: it runs no
     * script code, unlike the property reads below. */
    char *args_copy = malloc(args_len + 1);
    if (!args_copy) { st = SSC_ERR_INTERNAL; goto done; }
    memcpy(args_copy, args_json, args_len);
    args_copy[args_len] = 0;
    args = JS_ParseJSON(s->ctx, args_copy, args_len, "<args>");
    free(args_copy);
    if (JS_IsException(args) || !JS_IsArray(args)) {
        if (JS_IsException(args)) JS_FreeValue(s->ctx, JS_GetException(s->ctx));
        else JS_FreeValue(s->ctx, args); /* valid JSON that is not an array */
        args = JS_UNDEFINED;
        st = SSC_ERR_BAD_ARGUMENT;
        goto done;
    }
    /* `source` and the hook may be getters or proxies: a throw, timeout or quota hit inside them
     * must surface as that status, not be mistaken for a missing hook. */
    source = reserved ? JS_DupValue(s->ctx, global) : JS_GetPropertyStr(s->ctx, global, "source");
    if (JS_IsException(source)) {
        source = JS_UNDEFINED;
        st = take_exception(s);
        goto done;
    }
    method_atom = JS_NewAtomLen(s->ctx, method, method_len);
    if (method_atom == JS_ATOM_NULL) { st = take_exception(s); goto done; }
    fn = JS_GetProperty(s->ctx, source, method_atom);
    if (JS_IsException(fn)) {
        fn = JS_UNDEFINED;
        st = take_exception(s);
        goto done;
    }
    if (!JS_IsFunction(s->ctx, fn)) {
        st = SSC_ERR_NO_SUCH_HOOK;
        goto done;
    }
    int64_t argc64 = 0;
    JS_GetLength(s->ctx, args, &argc64);
    int argc = argc64 > 16 ? 16 : (int)argc64;
    JSValue argv[16];
    for (int i = 0; i < argc; i++) argv[i] = JS_GetPropertyUint32(s->ctx, args, (uint32_t)i);
    result = JS_Call(s->ctx, fn, source, argc, (JSValueConst *)argv);
    for (int i = 0; i < argc; i++) JS_FreeValue(s->ctx, argv[i]);
    if (JS_IsException(result)) {
        result = JS_UNDEFINED;
        st = take_exception(s);
        goto done;
    }
    /* Drive promises: run jobs, wait for host results, until the hook promise settles. */
    for (;;) {
        if (!drain_jobs(s, &st)) goto done;
        if (JS_PromiseState(s->ctx, result) != JS_PROMISE_PENDING) break;
        if (s->inflight == 0 && s->queued == 0) {
            st = SSC_ERR_PROMISE_STALLED;
            goto done;
        }
        if (cancelled(s)) { st = SSC_ERR_CANCELLED; goto done; }
        if (s->host.now_ms(s->host.opaque) > s->deadline_ms) { st = SSC_ERR_LIMIT_TIME; goto done; }
        s->host.async_wait(s->host.opaque, s, s->deadline_ms);
    }
    JSPromiseStateEnum state = JS_PromiseState(s->ctx, result);
    if (state == JS_PROMISE_REJECTED) {
        JSValue reason = JS_PromiseResult(s->ctx, result);
        s->source_failure = source_failure_of(s->ctx, reason);
        JS_FreeValue(s->ctx, reason);
        st = SSC_ERR_SCRIPT;
        goto done;
    }
    if (state == JS_PROMISE_FULFILLED) {
        JSValue settled = JS_PromiseResult(s->ctx, result);
        JS_FreeValue(s->ctx, result);
        result = settled;
    }
    /* toJSON/getters run during serialization: no host access, and no new work for later. */
    s->open = false;
    st = stringify_result(s, result, result_json, result_len);
done:
    if (method_atom != JS_ATOM_NULL) JS_FreeAtom(s->ctx, method_atom);
    JS_FreeValue(s->ctx, result);
    JS_FreeValue(s->ctx, args);
    JS_FreeValue(s->ctx, fn);
    JS_FreeValue(s->ctx, source);
    JS_FreeValue(s->ctx, global);
    /* Success or failure, nothing queued by this invocation may run in the next one. */
    close_invocation(s);
    if (st == SSC_OK && s->poisoned) {
        free(*result_json);
        *result_json = NULL;
        *result_len = 0;
        st = SSC_ERR_INTERNAL;
    }
    return st;
}

void ssc_cancel(ssc_runtime *s, uint64_t gen) { atomic_store(&s->cancel_gen, gen); }

ssc_source_failure ssc_last_source_failure(const ssc_runtime *s) { return s->source_failure; }

size_t ssc_memory_used(ssc_runtime *s) {
    JSMemoryUsage usage;
    JS_ComputeMemoryUsage(s->rt, &usage);
    return (size_t)usage.malloc_size;
}

uint32_t ssc_bridge_calls(ssc_runtime *s) { return s->bridge_calls; }

void ssc_set_dom_test_limits(ssc_runtime *s, uint32_t max_handles, uint32_t max_input_bytes, size_t max_memory_bytes) {
    ssc_dom_js_set_test_limits(s->owner.dom_js, max_handles, max_input_bytes, max_memory_bytes);
}
