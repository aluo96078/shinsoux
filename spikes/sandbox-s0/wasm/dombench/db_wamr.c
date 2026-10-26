/* WAMR driver: dombench_wamr dombench.wasm [file...] (same output format as db_main.c). */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include "wasm_export.h"

static int64_t host_clock_ns(wasm_exec_env_t e) { (void)e; struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts); return (int64_t)ts.tv_sec * 1000000000 + ts.tv_nsec; }
static NativeSymbol nat[] = { { "host_clock_ns", host_clock_ns, "()I", NULL } };

static wasm_module_inst_t inst; static wasm_exec_env_t env;
static uint32_t call(const char *fn, int argc, uint32_t *argv) {
    wasm_function_inst_t f = wasm_runtime_lookup_function(inst, fn);
    if (!f || !wasm_runtime_call_wasm(env, f, argc, argv)) { fprintf(stderr, "%s failed: %s\n", fn, wasm_runtime_get_exception(inst)); exit(1); }
    return argv[0];
}
static void doc(const char *label, const char *h, uint32_t len, uint32_t existing) {
    uint32_t a[4] = { len };
    uint32_t p = existing;
    if (!p) { p = call("db_alloc", 1, a); memcpy(wasm_runtime_addr_app_to_native(inst, p), h, len); }
    a[0] = 15 * 8; uint32_t o = call("db_alloc", 1, a);
    a[0] = p; a[1] = len; a[2] = len > (512u << 10) ? 2 : 5; a[3] = o;
    int32_t rc = (int32_t)call("db_run", 4, a);
    double out[15]; memcpy(out, wasm_runtime_addr_app_to_native(inst, o), sizeof out);
    printf("DOC %s %u rc=%d", label, len, rc);
    for (int k = 0; k < 15; k++) printf(" %.3f", out[k]);
    printf("\n"); fflush(stdout);
    a[0] = p; call("db_free", 1, a); a[0] = o; call("db_free", 1, a);
}
#include <pthread.h>
#include <unistd.h>
static void *killer(void *x) { usleep(atoi(getenv("DB_KILL_MS")) * 1000); wasm_runtime_terminate(inst); return NULL; }
int main(int argc, char **argv) {
    RuntimeInitArgs init; memset(&init, 0, sizeof init); init.mem_alloc_type = Alloc_With_System_Allocator;
    wasm_runtime_full_init(&init);
    wasm_runtime_register_natives("env", nat, 1);
    FILE *f = fopen(argv[1], "rb"); fseek(f, 0, SEEK_END); long n = ftell(f); fseek(f, 0, SEEK_SET);
    uint8_t *buf = malloc((size_t)n); if (fread(buf, 1, (size_t)n, f) != (size_t)n) return 1; fclose(f);
    char err[256];
    wasm_module_t m = wasm_runtime_load(buf, (uint32_t)n, err, sizeof err);
    if (!m) { fprintf(stderr, "load: %s\n", err); return 1; }
    inst = wasm_runtime_instantiate(m, 16u << 20, 0, err, sizeof err);
    if (!inst) { fprintf(stderr, "inst: %s\n", err); return 1; }
    env = wasm_runtime_create_exec_env(inst, 16u << 20);
    if (getenv("DB_WIDE")) {
        uint32_t a[2] = { (uint32_t)atoi(getenv("DB_WIDE")), 0 };
        uint32_t lenp = call("db_alloc", 1, (uint32_t[]){ 4 });
        a[1] = lenp; uint32_t hp = call("db_make_wide", 2, a);
        uint32_t len; memcpy(&len, wasm_runtime_addr_app_to_native(inst, lenp), 4);
        pthread_t th; if (getenv("DB_KILL_MS")) pthread_create(&th, NULL, killer, NULL);
        struct timespec t0, t1; clock_gettime(CLOCK_MONOTONIC, &t0);
        uint32_t b2[2] = { hp, len };
        wasm_function_inst_t f = wasm_runtime_lookup_function(inst, "db_wide_select");
        int ok = wasm_runtime_call_wasm(env, f, 2, b2);
        clock_gettime(CLOCK_MONOTONIC, &t1);
        printf("WIDE n=%s ok=%d result=%d ms=%.0f exc=%s\n", getenv("DB_WIDE"), ok, (int)b2[0], (t1.tv_sec - t0.tv_sec) * 1e3 + (t1.tv_nsec - t0.tv_nsec) / 1e6, ok ? "" : wasm_runtime_get_exception(inst));
        return 0;
    }
    static const char *names[] = { "synthetic 50KB", "synthetic 200KB", "synthetic 1MB" };
    static const uint32_t sizes[] = { 50u << 10, 200u << 10, 1u << 20 };
    for (int i = 0; i < 3; i++) {
        uint32_t a[2] = { sizes[i], 0 };
        uint32_t lenp = call("db_alloc", 1, (uint32_t[]){ 4 });
        a[1] = lenp; uint32_t hp = call("db_make_html", 2, a);
        uint32_t len; memcpy(&len, wasm_runtime_addr_app_to_native(inst, lenp), 4);
        doc(names[i], NULL, len, hp);
    }
    for (int i = 2; i < argc; i++) {
        FILE *g = fopen(argv[i], "rb"); if (!g) continue;
        fseek(g, 0, SEEK_END); long k = ftell(g); fseek(g, 0, SEEK_SET);
        char *h = malloc((size_t)k); if (fread(h, 1, (size_t)k, g) != (size_t)k) return 1; fclose(g);
        const char *l = strrchr(argv[i], '/'); doc(l ? l + 1 : argv[i], h, (uint32_t)k, 0); free(h);
    }
    wasm_memory_inst_t mem = wasm_runtime_lookup_memory(inst, "memory");
    printf("LINEAR_MIB %.1f\n", (double)wasm_memory_get_cur_page_count(mem) * wasm_memory_get_bytes_per_page(mem) / 1048576.0);
    return 0;
}
