#include "wamr_host.h"

#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "wasm_export.h"
#include "frame_host.h"
#include "wasm_frames.h"

struct sw_module {
    wasm_module_t module;
    uint8_t *bytes; /* WAMR keeps pointing into the buffer */
};

struct sw_instance {
    wasm_module_inst_t inst;
    wasm_exec_env_t env;
    sw_limits limits;
    sw_frame_host fh;
    atomic_int dead;
    char last_exception[128];

    wasm_function_inst_t f_alloc, f_free, f_new, f_init, f_invoke, f_rptr, f_rlen, f_mem, f_calls, f_freert;

    /* watchdog */
    pthread_t wd_thread;
    pthread_mutex_t wd_mu;
    pthread_cond_t wd_cv;
    uint64_t wd_deadline_ms; /* 0 = disarmed */
    int wd_quit;
    int wd_fired;
};

static uint64_t now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000 + (uint64_t)ts.tv_nsec / 1000000;
}


/* ---- host imports ------------------------------------------------------------------------ */

static int64_t host_clock_ms(wasm_exec_env_t env, int32_t clock) {
    (void)env;
    struct timespec ts;
    clock_gettime(clock == 0 ? CLOCK_REALTIME : CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static int32_t host_bridge(wasm_exec_env_t env, const uint8_t *frame, uint32_t frame_len, uint8_t *out, uint32_t out_cap) {
    sw_instance *s = wasm_runtime_get_custom_data(wasm_runtime_get_module_inst(env));
    return sw_frame_dispatch(&s->fh, frame, frame_len, out, out_cap);
}

static NativeSymbol g_natives[] = {
    { "host_bridge", host_bridge, "(*~*~)i", NULL },
    { "host_clock_ms", host_clock_ms, "(i)I", NULL },
};

/* ---- engine / module -------------------------------------------------------------------- */

int sw_engine_init(void) {
    RuntimeInitArgs init;
    memset(&init, 0, sizeof init);
    init.mem_alloc_type = Alloc_With_System_Allocator;
    if (!wasm_runtime_full_init(&init)) return -1;
    if (!wasm_runtime_register_natives("env", g_natives, sizeof g_natives / sizeof g_natives[0])) return -1;
    return 0;
}

void sw_engine_shutdown(void) { wasm_runtime_destroy(); }
void sw_thread_attach(void) { wasm_runtime_init_thread_env(); }
void sw_thread_detach(void) { wasm_runtime_destroy_thread_env(); }

sw_module *sw_module_load(const uint8_t *bytes, size_t len, char *err, size_t err_len) {
    sw_module *m = calloc(1, sizeof *m);
    m->bytes = malloc(len);
    memcpy(m->bytes, bytes, len);
    m->module = wasm_runtime_load(m->bytes, (uint32_t)len, err, (uint32_t)err_len);
    if (!m->module) { free(m->bytes); free(m); return NULL; }
    return m;
}

void sw_module_free(sw_module *m) {
    wasm_runtime_unload(m->module);
    free(m->bytes);
    free(m);
}

/* ---- watchdog --------------------------------------------------------------------------- */

static void *watchdog_main(void *arg) {
    sw_instance *s = arg;
    pthread_mutex_lock(&s->wd_mu);
    while (!s->wd_quit) {
        if (s->wd_deadline_ms == 0) {
            pthread_cond_wait(&s->wd_cv, &s->wd_mu);
            continue;
        }
        uint64_t now = now_ms();
        if (now >= s->wd_deadline_ms) {
            s->wd_deadline_ms = 0;
            s->wd_fired = 1;
            atomic_store(&s->dead, 1);
            wasm_runtime_terminate(s->inst);
            continue;
        }
        uint64_t rel = s->wd_deadline_ms - now;
        struct timespec ts = { (time_t)(rel / 1000), (long)(rel % 1000) * 1000000L };
        pthread_cond_timedwait_relative_np(&s->wd_cv, &s->wd_mu, &ts);
    }
    pthread_mutex_unlock(&s->wd_mu);
    return NULL;
}

static void watchdog_arm(sw_instance *s, uint64_t timeout_ms) {
    pthread_mutex_lock(&s->wd_mu);
    s->wd_deadline_ms = now_ms() + timeout_ms;
    pthread_cond_signal(&s->wd_cv);
    pthread_mutex_unlock(&s->wd_mu);
}

static void watchdog_disarm(sw_instance *s) {
    pthread_mutex_lock(&s->wd_mu);
    s->wd_deadline_ms = 0;
    pthread_mutex_unlock(&s->wd_mu);
}

/* ---- instance --------------------------------------------------------------------------- */

#define LOOKUP(field, name) \
    do { s->field = wasm_runtime_lookup_function(s->inst, name); if (!s->field) { snprintf(err, err_len, "missing export %s", name); goto fail; } } while (0)

sw_instance *sw_instance_create(sw_module *m, const sw_limits *l, const sw_callbacks *cb, char *err, size_t err_len) {
    sw_instance *s = calloc(1, sizeof *s);
    s->limits = *l;
    s->fh.cb = *cb;
    s->fh.dead = &s->dead;
    struct InstantiationArgs2 *args = NULL;
    wasm_runtime_instantiation_args_create(&args);
    wasm_runtime_instantiation_args_set_default_stack_size(args, l->wasm_stack_bytes ? l->wasm_stack_bytes : 4u << 20);
    wasm_runtime_instantiation_args_set_host_managed_heap_size(args, 0);
    if (l->linear_max_bytes) wasm_runtime_instantiation_args_set_max_memory_pages(args, l->linear_max_bytes / 65536);
    s->inst = wasm_runtime_instantiate_ex2(m->module, args, err, (uint32_t)err_len);
    wasm_runtime_instantiation_args_destroy(args);
    if (!s->inst) { free(s); return NULL; }
    wasm_runtime_set_custom_data(s->inst, s);
    s->env = wasm_runtime_create_exec_env(s->inst, l->wasm_stack_bytes ? l->wasm_stack_bytes : 4u << 20);
    if (!s->env) { snprintf(err, err_len, "exec env"); goto fail; }
    LOOKUP(f_alloc, "sw_alloc");
    LOOKUP(f_free, "sw_free");
    LOOKUP(f_new, "sw_new");
    LOOKUP(f_init, "sw_init");
    LOOKUP(f_invoke, "sw_invoke");
    LOOKUP(f_rptr, "sw_result_ptr");
    LOOKUP(f_rlen, "sw_result_len");
    LOOKUP(f_mem, "sw_memory_used");
    LOOKUP(f_calls, "sw_bridge_calls");
    LOOKUP(f_freert, "sw_free_runtime");

    uint32_t argv[10] = { l->heap, l->stack, l->init_ms, l->invoke_ms, l->max_bridge, l->inflight,
                          l->queue, l->max_result, l->dom_nodes, l->dom_depth };
    if (!wasm_runtime_call_wasm(s->env, s->f_new, 10, argv) || (int32_t)argv[0] != 0) {
        snprintf(err, err_len, "sw_new failed: %s", wasm_runtime_get_exception(s->inst));
        goto fail;
    }
    pthread_mutex_init(&s->wd_mu, NULL);
    pthread_cond_init(&s->wd_cv, NULL);
    pthread_create(&s->wd_thread, NULL, watchdog_main, s);
    return s;
fail:
    if (s->env) wasm_runtime_destroy_exec_env(s->env);
    wasm_runtime_deinstantiate(s->inst);
    free(s);
    return NULL;
}

static const char *exception_text(sw_instance *s) {
    const char *e = wasm_runtime_get_exception(s->inst);
    snprintf(s->last_exception, sizeof s->last_exception, "%s", e ? e : "");
    return s->last_exception;
}

/* Copies a C string into guest memory; returns the guest offset (0 on failure). */
static uint32_t guest_put(sw_instance *s, const void *data, size_t len, int nul) {
    uint32_t argv[1] = { (uint32_t)(len + (nul ? 1 : 0)) };
    if (!wasm_runtime_call_wasm(s->env, s->f_alloc, 1, argv) || argv[0] == 0) return 0;
    uint32_t off = argv[0];
    void *dst = wasm_runtime_addr_app_to_native(s->inst, off);
    memcpy(dst, data, len);
    if (nul) ((char *)dst)[len] = 0;
    return off;
}

static void guest_free(sw_instance *s, uint32_t off) {
    if (!off) return;
    uint32_t argv[1] = { off };
    wasm_runtime_call_wasm(s->env, s->f_free, 1, argv);
}

int sw_instance_init(sw_instance *s, const char *boot, size_t boot_len, const char *script, size_t script_len) {
    if (atomic_load(&s->dead)) return SW_TRAPPED;
    watchdog_arm(s, (s->limits.watchdog_ms ? s->limits.watchdog_ms : s->limits.init_ms + 2000));
    uint32_t b = guest_put(s, boot, boot_len, 0), sc = guest_put(s, script, script_len, 0);
    uint32_t argv[4] = { b, (uint32_t)boot_len, sc, (uint32_t)script_len };
    int ok = b && sc && wasm_runtime_call_wasm(s->env, s->f_init, 4, argv);
    watchdog_disarm(s);
    if (!ok) {
        exception_text(s);
        atomic_store(&s->dead, 1);
        return SW_TRAPPED;
    }
    guest_free(s, b);
    guest_free(s, sc);
    return (int)argv[0];
}

int sw_instance_invoke(sw_instance *s, const char *method, const char *args_json, char **out, size_t *out_len) {
    *out = NULL;
    *out_len = 0;
    if (atomic_load(&s->dead)) return SW_TRAPPED;
    uint32_t m = guest_put(s, method, strlen(method), 1), a = guest_put(s, args_json, strlen(args_json), 1);
    if (!m || !a) { atomic_store(&s->dead, 1); return SW_TRAPPED; }
    watchdog_arm(s, (s->limits.watchdog_ms ? s->limits.watchdog_ms : s->limits.invoke_ms + 2000));
    uint32_t argv[2] = { m, a };
    int ok = wasm_runtime_call_wasm(s->env, s->f_invoke, 2, argv);
    watchdog_disarm(s);
    if (!ok || atomic_load(&s->dead)) {
        exception_text(s);
        atomic_store(&s->dead, 1);
        return SW_TRAPPED;
    }
    int status = (int)argv[0];
    if (status == 0) {
        uint32_t p[1] = { 0 }, n[1] = { 0 };
        wasm_runtime_call_wasm(s->env, s->f_rptr, 0, p);
        wasm_runtime_call_wasm(s->env, s->f_rlen, 0, n);
        if (p[0]) {
            *out = malloc(n[0] + 1);
            memcpy(*out, wasm_runtime_addr_app_to_native(s->inst, p[0]), n[0]);
            (*out)[n[0]] = 0;
            *out_len = n[0];
        }
    }
    guest_free(s, m);
    guest_free(s, a);
    return status;
}

void sw_instance_terminate(sw_instance *s) {
    atomic_store(&s->dead, 1);
    wasm_runtime_terminate(s->inst);
}

int sw_instance_alive(sw_instance *s) { return !atomic_load(&s->dead); }

static uint32_t call0(sw_instance *s, wasm_function_inst_t f) {
    uint32_t argv[1] = { 0 };
    if (atomic_load(&s->dead) || !wasm_runtime_call_wasm(s->env, f, 0, argv)) return 0;
    return argv[0];
}

size_t sw_instance_js_heap(sw_instance *s) { return call0(s, s->f_mem); }
uint32_t sw_instance_bridge_calls(sw_instance *s) { return call0(s, s->f_calls); }
const char *sw_instance_last_exception(sw_instance *s) { return s->last_exception; }

size_t sw_instance_linear_bytes(sw_instance *s) {
    wasm_memory_inst_t mem = wasm_runtime_lookup_memory(s->inst, "memory");
    if (!mem) return 0;
    return (size_t)wasm_memory_get_cur_page_count(mem) * wasm_memory_get_bytes_per_page(mem);
}

void sw_instance_destroy(sw_instance *s) {
    pthread_mutex_lock(&s->wd_mu);
    s->wd_quit = 1;
    pthread_cond_signal(&s->wd_cv);
    pthread_mutex_unlock(&s->wd_mu);
    pthread_join(s->wd_thread, NULL);
    /* A live instance may release its JS runtime; a dead one is simply dropped with its memory. */
    wasm_runtime_destroy_exec_env(s->env);
    wasm_runtime_deinstantiate(s->inst);
    free(s->fh.pending);
    pthread_mutex_destroy(&s->wd_mu);
    pthread_cond_destroy(&s->wd_cv);
    free(s);
}
