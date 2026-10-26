/*
 * Runs an official Shinsou extension in shinsou-sandbox-core against recorded HTTP fixtures.
 *
 *   plugin_bench <bootstrap.js> <plugin.js> <fixture-dir> <runs>
 *
 * Fixture key = FNV-1a 64 of "<method>\n<argsJson>". A missing fixture prints one
 * `MISS {"method":...,"args":...}` line on stdout (record.py fetches it) and the bridge yields null.
 * Each run uses a fresh runtime so the init time is a cold start of the script.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "shinsou_sandbox_core.h"

static const char *fixture_dir;
static int misses;

static uint64_t now_us(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000 + (uint64_t)ts.tv_nsec / 1000;
}
static uint64_t now_ms(void *o) { (void)o; return now_us() / 1000; }

static char *read_file(const char *path, size_t *len) {
    FILE *f = fopen(path, "rb");
    if (!f) return NULL;
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    char *buf = malloc((size_t)n + 1);
    if (fread(buf, 1, (size_t)n, f) != (size_t)n) { fclose(f); free(buf); return NULL; }
    fclose(f);
    buf[n] = 0;
    if (len) *len = (size_t)n;
    return buf;
}

static uint64_t fnv1a(const char *a, const char *b, size_t blen) {
    uint64_t h = 1469598103934665603ULL;
    for (const char *p = a; *p; p++) { h ^= (unsigned char)*p; h *= 1099511628211ULL; }
    h ^= '\n'; h *= 1099511628211ULL;
    for (size_t i = 0; i < blen; i++) { h ^= (unsigned char)b[i]; h *= 1099511628211ULL; }
    return h;
}

static void print_json_string(const char *s, size_t len) {
    putchar('"');
    for (size_t i = 0; i < len; i++) {
        unsigned char c = (unsigned char)s[i];
        if (c == '"' || c == '\\') printf("\\%c", c);
        else if (c < 0x20) printf("\\u%04x", c);
        else putchar(c);
    }
    putchar('"');
}

static int is_http(const char *m) { return strncmp(m, "http", 4) == 0; }
static int wants_response(const char *m) { return strstr(m, "Response") != NULL; }

static int bridge_call(void *o, const char *method, const char *args, size_t len, char **out, size_t *out_len) {
    (void)o;
    if (!is_http(method)) {
        *out = strdup("null");
        *out_len = 4;
        return 0;
    }
    char base[1024];
    uint64_t key = fnv1a(method, args, len);
    snprintf(base, sizeof base, "%s/%016llx", fixture_dir, (unsigned long long)key);
    if (getenv("BENCH_TRACE")) {
        /* Index for hosts that see requests rather than bootstrap argsJson (the Rhino baseline). */
        printf("REQ %016llx {\"method\":", (unsigned long long)key);
        print_json_string(method, strlen(method));
        printf(",\"args\":%.*s}\n", (int)len, args);
    }
    char path[1100];
    snprintf(path, sizeof path, "%s.body.json", base);
    size_t body_len = 0;
    char *body = read_file(path, &body_len);
    if (!body) {
        misses++;
        printf("MISS {\"method\":");
        print_json_string(method, strlen(method));
        printf(",\"args\":%.*s}\n", (int)len, args);
        fflush(stdout);
        return -1;
    }
    if (!wants_response(method)) {
        *out = body;
        *out_len = body_len;
        return 0;
    }
    snprintf(path, sizeof path, "%s.status", base);
    char *status = read_file(path, NULL);
    size_t cap = body_len + 64;
    *out = malloc(cap);
    *out_len = (size_t)snprintf(*out, cap, "{\"status\":%d,\"body\":%s}", status ? atoi(status) : 200, body);
    free(status);
    free(body);
    return 0;
}

static int async_start(void *o, uint32_t op, const char *r, size_t l) { (void)o; (void)op; (void)r; (void)l; return -1; }
static void async_cancel(void *o, uint32_t op) { (void)o; (void)op; }
static int async_wait(void *o, ssc_runtime *rt, uint64_t d) { (void)o; (void)rt; (void)d; return 0; }
static void log_fn(void *o, const char *m, size_t l) { (void)o; (void)m; (void)l; }

static const char STEPS[] =
    "\n;source.__b1=function(){var r=this.getPopularManga(1);globalThis.__m=r&&r.mangas&&r.mangas[0];return r&&r.mangas?r.mangas.length:-1;};"
    "source.__b2=function(){if(!globalThis.__m)return -1;var d=this.getMangaDetails(__m);if(d){for(var k in d)if(d[k]!=null&&__m[k]==null)__m[k]=d[k];}return d&&d.title?1:0;};"
    "source.__b3=function(){if(!globalThis.__m)return -1;var c=this.getChapterList(__m);globalThis.__c=c&&c[c.length-1];return c?c.length:-1;};"
    "source.__b4=function(){if(!globalThis.__c)return -1;var p=this.getPageList(__c);return p?p.length:-1;};";

static const char *STEP_NAMES[] = { "popular", "details", "chapters", "pages" };
static const char *STEP_METHODS[] = { "__b1", "__b2", "__b3", "__b4" };

static int cmp_u64(const void *a, const void *b) {
    uint64_t x = *(const uint64_t *)a, y = *(const uint64_t *)b;
    return x < y ? -1 : x > y;
}

int main(int argc, char **argv) {
    if (argc < 5) { fprintf(stderr, "usage: plugin_bench bootstrap.js plugin.js fixture-dir runs\n"); return 2; }
    size_t boot_len, plugin_len;
    char *boot = read_file(argv[1], &boot_len);
    char *plugin = read_file(argv[2], &plugin_len);
    if (!boot || !plugin) { fprintf(stderr, "cannot read inputs\n"); return 2; }
    fixture_dir = argv[3];
    int runs = atoi(argv[4]);
    if (runs < 1 || runs > 100) runs = 1;
    char *script = malloc(plugin_len + sizeof STEPS);
    memcpy(script, plugin, plugin_len);
    memcpy(script + plugin_len, STEPS, sizeof STEPS);

    ssc_limits limits = {
        .heap_limit_bytes = 64u << 20, .stack_limit_bytes = 1u << 20, .init_timeout_ms = 10000,
        .invoke_timeout_ms = 30000, .max_bridge_calls = 4096, .max_async_inflight = 6,
        .max_async_queue = 64, .max_result_bytes = 4u << 20, .dom_max_nodes = 200000, .dom_max_depth = 512,
    };
    ssc_host host = { NULL, now_ms, bridge_call, async_start, async_cancel, async_wait, log_fn };

    uint64_t samples[5][100];
    size_t peak_heap = 0;
    char results[4][64] = { "", "", "", "" };
    for (int r = 0; r < runs; r++) {
        uint64_t t0 = now_us();
        ssc_runtime *rt = ssc_new(&limits, &host);
        ssc_status st = ssc_init(rt, boot, boot_len, script, strlen(script));
        samples[0][r] = now_us() - t0;
        if (st != SSC_OK) { printf("INIT %s\n", ssc_status_name(st)); return 1; }
        for (int s = 0; s < 4; s++) {
            char *out = NULL;
            size_t out_len = 0;
            uint64_t t1 = now_us();
            st = ssc_invoke(rt, STEP_METHODS[s], "[]", &out, &out_len);
            samples[s + 1][r] = now_us() - t1;
            if (r == 0) snprintf(results[s], sizeof results[s], "%s:%s", ssc_status_name(st), out ? out : "-");
            free(out);
            size_t heap = ssc_memory_used(rt);
            if (heap > peak_heap) peak_heap = heap;
        }
        ssc_free(rt);
    }
    if (misses) { printf("MISSES %d\n", misses); return 3; }
    printf("RESULT init");
    for (int s = 0; s < 4; s++) printf(" %s=%s", STEP_NAMES[s], results[s]);
    printf("\n");
    const char *names[5] = { "init", "popular", "details", "chapters", "pages" };
    for (int i = 0; i < 5; i++) {
        qsort(samples[i], (size_t)runs, sizeof(uint64_t), cmp_u64);
        printf("TIME %-8s median=%.2fms min=%.2fms\n", names[i], samples[i][runs / 2] / 1000.0, samples[i][0] / 1000.0);
    }
    printf("HEAP peak=%.1fKiB\n", peak_heap / 1024.0);
    return 0;
}
