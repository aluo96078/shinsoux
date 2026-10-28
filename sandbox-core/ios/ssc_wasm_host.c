#include "ssc_wasm_host.h"

#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "wasm_export.h"

extern const unsigned char ssc_wasm_module[];
extern const size_t ssc_wasm_module_len;

/* S0 (§4.5): the fast interpreter needs a generous interpreter stack for QuickJS; iOS gives
 * secondary threads 512 KiB by default, so the sandbox thread gets its own. */
#define INTERP_STACK_BYTES (8u << 20)
#define THREAD_STACK_BYTES (8u << 20)
/* A host that stops reading cannot make the sandbox hold more than this; past it, writes fail. */
#define MAX_PENDING_OUTPUT (32u << 20)
/* Input waiting for the guest: above the driver's own queue (32 MiB) plus a frame being read
 * (16 MiB), so a well-behaved session never reaches it; past it, sends fail and the host closes
 * the sandbox. This memory is the app's, not the guest's linear memory. */
#define MAX_PENDING_INPUT (48u << 20)
/* After its input closes, a sandbox gets this long to stop by itself before it is terminated. */
#define CLOSE_GRACE_MS 1000

typedef struct {
    uint8_t *data;
    size_t len, cap;
} bytes;

struct ssc_wasm_session {
    wasm_module_inst_t inst;
    pthread_t thread;
    pthread_mutex_t mu;
    pthread_cond_t cv;
    bytes in, out;
    size_t in_pos, out_pos;
    int input_closed;
    int ended;
    int status;
};

static pthread_once_t g_once = PTHREAD_ONCE_INIT;
static wasm_module_t g_module;
static char g_init_err[128];

static uint64_t monotonic_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000u + (uint64_t)ts.tv_nsec / 1000000u;
}

static void deadline_after(struct timespec *ts, uint32_t ms) {
    clock_gettime(CLOCK_REALTIME, ts);
    ts->tv_sec += ms / 1000;
    ts->tv_nsec += (long)(ms % 1000) * 1000000L;
    if (ts->tv_nsec >= 1000000000L) { ts->tv_sec++; ts->tv_nsec -= 1000000000L; }
}

static int append(bytes *b, const uint8_t *p, size_t n) {
    if (n > SIZE_MAX / 2 - b->len) return -1;
    if (b->len + n > b->cap) {
        size_t cap = b->cap ? b->cap : 64 * 1024;
        while (cap < b->len + n) cap *= 2;
        uint8_t *q = realloc(b->data, cap);
        if (!q) return -1;
        b->data = q;
        b->cap = cap;
    }
    memcpy(b->data + b->len, p, n);
    b->len += n;
    return 0;
}

/* Drops what was consumed once it is most of the buffer, so a long session does not keep growing. */
static void compact(bytes *b, size_t *pos) {
    if (*pos == 0 || *pos < b->len / 2) return;
    memmove(b->data, b->data + *pos, b->len - *pos);
    b->len -= *pos;
    *pos = 0;
}

/* ---- the guest's imports ---------------------------------------------------------------- */

static ssc_wasm_session *session_of(wasm_exec_env_t env) {
    return wasm_runtime_get_custom_data(wasm_runtime_get_module_inst(env));
}

/* (buf, len, timeout_ms) -> bytes, 0 at end of stream, -2 on timeout. WAMR checked buf..buf+len. */
static int32_t host_read(wasm_exec_env_t env, uint8_t *buf, uint32_t len, int64_t timeout_ms) {
    ssc_wasm_session *s = session_of(env);
    if (len == 0) return -1;
    pthread_mutex_lock(&s->mu);
    if (s->in_pos == s->in.len && !s->input_closed && timeout_ms > 0) {
        struct timespec ts;
        deadline_after(&ts, timeout_ms > 60000 ? 60000 : (uint32_t)timeout_ms);
        while (s->in_pos == s->in.len && !s->input_closed) {
            if (pthread_cond_timedwait(&s->cv, &s->mu, &ts) != 0) break;
        }
    }
    int32_t n;
    if (s->in_pos < s->in.len) {
        size_t avail = s->in.len - s->in_pos;
        n = (int32_t)(avail < len ? avail : len);
        memcpy(buf, s->in.data + s->in_pos, (size_t)n);
        s->in_pos += (size_t)n;
        compact(&s->in, &s->in_pos);
    } else {
        n = s->input_closed ? 0 : -2;
    }
    pthread_mutex_unlock(&s->mu);
    return n;
}

static int32_t host_write(wasm_exec_env_t env, const uint8_t *buf, uint32_t len) {
    ssc_wasm_session *s = session_of(env);
    pthread_mutex_lock(&s->mu);
    /* Writes go on after the input ends (the host still reads the last replies), up to the cap. */
    int rc = -1;
    if (len <= MAX_PENDING_OUTPUT - (s->out.len - s->out_pos)) rc = append(&s->out, buf, len);
    pthread_cond_broadcast(&s->cv);
    pthread_mutex_unlock(&s->mu);
    return rc;
}

static int64_t host_now_ms(wasm_exec_env_t env, int32_t clock) {
    (void)env;
    struct timespec ts;
    clock_gettime(clock == 0 ? CLOCK_REALTIME : CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

/* Minutes, as Date.prototype.getTimezoneOffset: the device's zone, the same as the host's engine. */
static int32_t host_timezone_offset(wasm_exec_env_t env, int64_t time_ms) {
    (void)env;
    time_t t = (time_t)(time_ms / 1000);
    struct tm tm;
    if (!localtime_r(&t, &tm)) return 0;
    return (int32_t)(-tm.tm_gmtoff / 60);
}

static NativeSymbol g_natives[] = {
    { "host_read", host_read, "(*~I)i", NULL },
    { "host_write", host_write, "(*~)i", NULL },
    { "host_now_ms", host_now_ms, "(i)I", NULL },
    { "host_timezone_offset", host_timezone_offset, "(I)i", NULL },
};

/* ---- engine ----------------------------------------------------------------------------- */

static void engine_init_once(void) {
    RuntimeInitArgs init;
    memset(&init, 0, sizeof init);
    init.mem_alloc_type = Alloc_With_System_Allocator;
    if (!wasm_runtime_full_init(&init)) {
        snprintf(g_init_err, sizeof g_init_err, "WAMR init failed");
        return;
    }
    if (!wasm_runtime_register_natives("env", g_natives, sizeof g_natives / sizeof g_natives[0])) {
        snprintf(g_init_err, sizeof g_init_err, "WAMR natives failed");
        return;
    }
    /* WAMR keeps pointing into the bytes; the embedded array lives as long as the app. It copies
     * nothing, so the loader gets a private writable copy (it patches the buffer in place). */
    uint8_t *copy = malloc(ssc_wasm_module_len);
    if (!copy) { snprintf(g_init_err, sizeof g_init_err, "out of memory"); return; }
    memcpy(copy, ssc_wasm_module, ssc_wasm_module_len);
    char err[128];
    g_module = wasm_runtime_load(copy, (uint32_t)ssc_wasm_module_len, err, sizeof err);
    if (!g_module) snprintf(g_init_err, sizeof g_init_err, "guest load failed: %s", err);
}

int ssc_wasm_engine_init(char *err, size_t err_len) {
    pthread_once(&g_once, engine_init_once);
    if (g_module) return 0;
    snprintf(err, err_len, "%s", g_init_err[0] ? g_init_err : "WAMR unavailable");
    return -1;
}

/* ---- sessions --------------------------------------------------------------------------- */

static void *session_main(void *arg) {
    ssc_wasm_session *s = arg;
    int status = -1;
    if (wasm_runtime_init_thread_env()) {
        wasm_exec_env_t env = wasm_runtime_create_exec_env(s->inst, INTERP_STACK_BYTES);
        wasm_function_inst_t run = wasm_runtime_lookup_function(s->inst, "ssc_wasm_run");
        if (env && run) {
            uint32_t argv[1] = { 0 };
            if (wasm_runtime_call_wasm(env, run, 0, argv)) status = (int32_t)argv[0];
        }
        if (env) wasm_runtime_destroy_exec_env(env);
        wasm_runtime_destroy_thread_env();
    }
    pthread_mutex_lock(&s->mu);
    s->status = status;
    s->ended = 1;
    s->input_closed = 1;
    pthread_cond_broadcast(&s->cv);
    pthread_mutex_unlock(&s->mu);
    return NULL;
}

ssc_wasm_session *ssc_wasm_session_open(uint32_t linear_max_bytes, char *err, size_t err_len) {
    if (ssc_wasm_engine_init(err, err_len) != 0) return NULL;
    ssc_wasm_session *s = calloc(1, sizeof *s);
    if (!s) { snprintf(err, err_len, "out of memory"); return NULL; }
    pthread_mutex_init(&s->mu, NULL);
    pthread_cond_init(&s->cv, NULL);
    struct InstantiationArgs2 *args = NULL;
    if (!wasm_runtime_instantiation_args_create(&args)) { snprintf(err, err_len, "out of memory"); goto fail; }
    wasm_runtime_instantiation_args_set_default_stack_size(args, INTERP_STACK_BYTES);
    wasm_runtime_instantiation_args_set_host_managed_heap_size(args, 0);
    /* Can only lower the module's own maximum (160 MiB), never raise it. */
    if (linear_max_bytes) wasm_runtime_instantiation_args_set_max_memory_pages(args, linear_max_bytes / 65536);
    s->inst = wasm_runtime_instantiate_ex2(g_module, args, err, (uint32_t)err_len);
    wasm_runtime_instantiation_args_destroy(args);
    if (!s->inst) goto fail;
    wasm_runtime_set_custom_data(s->inst, s);
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    int rc = pthread_attr_setstacksize(&attr, THREAD_STACK_BYTES);
    if (rc == 0) rc = pthread_create(&s->thread, &attr, session_main, s);
    pthread_attr_destroy(&attr);
    if (rc != 0) { snprintf(err, err_len, "sandbox thread failed"); goto fail; }
    return s;
fail:
    if (s->inst) wasm_runtime_deinstantiate(s->inst);
    pthread_mutex_destroy(&s->mu);
    pthread_cond_destroy(&s->cv);
    free(s);
    return NULL;
}

int ssc_wasm_session_send(ssc_wasm_session *s, const uint8_t *data, size_t len) {
    pthread_mutex_lock(&s->mu);
    int rc = -1;
    if (!s->input_closed) {
        size_t pending = s->in.len - s->in_pos;
        if (len <= MAX_PENDING_INPUT - pending) rc = append(&s->in, data, len);
        if (rc != 0) {
            /* Over the cap (or out of memory): the session fails here, not at the caller's
             * discretion. What was waiting is dropped and the guest sees the end of its input
             * at its next read, so it stops even mid-hook. */
            s->input_closed = 1;
            free(s->in.data);
            s->in = (bytes){0};
            s->in_pos = 0;
        }
    }
    pthread_cond_broadcast(&s->cv);
    pthread_mutex_unlock(&s->mu);
    return rc;
}

void ssc_wasm_session_close_input(ssc_wasm_session *s) {
    pthread_mutex_lock(&s->mu);
    s->input_closed = 1;
    pthread_cond_broadcast(&s->cv);
    pthread_mutex_unlock(&s->mu);
}

long ssc_wasm_session_receive(ssc_wasm_session *s, uint8_t *buf, size_t cap, uint32_t timeout_ms) {
    pthread_mutex_lock(&s->mu);
    if (s->out_pos == s->out.len && !s->ended && timeout_ms > 0) {
        struct timespec ts;
        deadline_after(&ts, timeout_ms);
        while (s->out_pos == s->out.len && !s->ended) {
            if (pthread_cond_timedwait(&s->cv, &s->mu, &ts) != 0) break;
        }
    }
    long n;
    if (s->out_pos < s->out.len) {
        size_t avail = s->out.len - s->out_pos;
        n = (long)(avail < cap ? avail : cap);
        memcpy(buf, s->out.data + s->out_pos, (size_t)n);
        s->out_pos += (size_t)n;
        compact(&s->out, &s->out_pos);
    } else {
        n = s->ended ? 0 : SSC_WASM_TIMEOUT;
    }
    pthread_mutex_unlock(&s->mu);
    return n;
}

int ssc_wasm_session_status(ssc_wasm_session *s) {
    pthread_mutex_lock(&s->mu);
    int status = s->ended ? s->status : -1;
    pthread_mutex_unlock(&s->mu);
    return status;
}

void ssc_wasm_session_free(ssc_wasm_session *s) {
    if (!s) return;
    ssc_wasm_session_close_input(s);
    uint64_t give_up = monotonic_ms() + CLOSE_GRACE_MS;
    pthread_mutex_lock(&s->mu);
    while (!s->ended && monotonic_ms() < give_up) {
        struct timespec ts;
        deadline_after(&ts, 50);
        pthread_cond_timedwait(&s->cv, &s->mu, &ts);
    }
    int ended = s->ended;
    pthread_mutex_unlock(&s->mu);
    /* Stuck where the driver cannot see the end of its input: the interpreter stops it. */
    if (!ended) wasm_runtime_terminate(s->inst);
    pthread_join(s->thread, NULL);
    wasm_runtime_deinstantiate(s->inst);
    free(s->in.data);
    free(s->out.data);
    pthread_mutex_destroy(&s->mu);
    pthread_cond_destroy(&s->cv);
    free(s);
}
