/* wasm2c driver: dombench_w2c [file...] (same output format as db_main.c). */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <pthread.h>
#include "dombench.h"
#include "wasm-rt.h"
#include "wasm-rt-exceptions.h"
#include "wasm-rt-impl.h"

struct w2c_env { int unused; };
u64 w2c_env_host_clock_ns(struct w2c_env *e) { (void)e; struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts); return (u64)ts.tv_sec * 1000000000ull + (u64)ts.tv_nsec; }

static w2c_dombench M; static struct w2c_env E; static int argc_; static char **argv_;

static void doc(const char *label, const char *h, uint32_t len, uint32_t existing) {
    uint8_t *mem = w2c_dombench_memory(&M)->data;
    uint32_t p = existing;
    if (!p) { p = w2c_dombench_db_alloc(&M, len); memcpy(w2c_dombench_memory(&M)->data + p, h, len); }
    uint32_t o = w2c_dombench_db_alloc(&M, 15 * 8);
    int32_t rc = (int32_t)w2c_dombench_db_run(&M, p, len, len > (512u << 10) ? 2 : 5, o);
    double out[15]; memcpy(out, w2c_dombench_memory(&M)->data + o, sizeof out); (void)mem;
    printf("DOC %s %u rc=%d", label, len, rc);
    for (int k = 0; k < 15; k++) printf(" %.3f", out[k]);
    printf("\n"); fflush(stdout);
    w2c_dombench_db_free(&M, p); w2c_dombench_db_free(&M, o);
}
static void *run(void *x) {
    (void)x;
    wasm_rt_init_thread();
    if (wasm_rt_impl_try() != 0) { fprintf(stderr, "trap\n"); return NULL; }
    wasm2c_dombench_instantiate(&M, &E);
    if (getenv("DB_WIDE")) { uint32_t lp = w2c_dombench_db_alloc(&M, 4); uint32_t hp = w2c_dombench_db_make_wide(&M, (uint32_t)atoi(getenv("DB_WIDE")), lp); uint32_t len; memcpy(&len, w2c_dombench_memory(&M)->data + lp, 4); struct timespec a, b; clock_gettime(CLOCK_MONOTONIC, &a); int32_t r = (int32_t)w2c_dombench_db_wide_select(&M, hp, len); clock_gettime(CLOCK_MONOTONIC, &b); printf("WIDE n=%s result=%d ms=%.0f\n", getenv("DB_WIDE"), r, (b.tv_sec - a.tv_sec) * 1e3 + (b.tv_nsec - a.tv_nsec) / 1e6); return NULL; }
    static const char *names[] = { "synthetic 50KB", "synthetic 200KB", "synthetic 1MB" };
    static const uint32_t sizes[] = { 50u << 10, 200u << 10, 1u << 20 };
    for (int i = 0; i < 3; i++) {
        uint32_t lp = w2c_dombench_db_alloc(&M, 4);
        uint32_t hp = w2c_dombench_db_make_html(&M, sizes[i], lp);
        uint32_t len; memcpy(&len, w2c_dombench_memory(&M)->data + lp, 4);
        doc(names[i], NULL, len, hp);
    }
    for (int i = 1; i < argc_; i++) {
        FILE *g = fopen(argv_[i], "rb"); if (!g) continue;
        fseek(g, 0, SEEK_END); long k = ftell(g); fseek(g, 0, SEEK_SET);
        char *h = malloc((size_t)k); if (fread(h, 1, (size_t)k, g) != (size_t)k) return NULL; fclose(g);
        const char *l = strrchr(argv_[i], '/'); doc(l ? l + 1 : argv_[i], h, (uint32_t)k, 0); free(h);
    }
    printf("LINEAR_MIB %.1f\n", (double)w2c_dombench_memory(&M)->size / 1048576.0);
    return NULL;
}
int main(int argc, char **argv) {
    argc_ = argc; argv_ = argv;
    wasm_rt_init();
    pthread_attr_t at; pthread_attr_init(&at); pthread_attr_setstacksize(&at, 64u << 20);
    pthread_t t; pthread_create(&t, &at, run, NULL); pthread_join(t, NULL);
    return 0;
}
