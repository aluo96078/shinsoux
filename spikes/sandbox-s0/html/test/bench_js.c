/* The same workload on the QuickJS-hosted pure-JS DOM bootstrap (the iOS implementation). */
#include <stdint.h>
#include "bench_common.h"
#include "shinsou_sandbox_core.h"

static uint64_t now_ms(void *o) { (void)o; return (uint64_t)bench_now_ms(); }
static int bridge_call(void *o, const char *m, const char *a, size_t l, char **out, size_t *ol) {
    (void)o; (void)m; (void)a; (void)l; *out = strdup("null"); *ol = 4; return 0;
}
static int async_start(void *o, uint32_t op, const char *r, size_t l) { (void)o; (void)op; (void)r; (void)l; return -1; }
static void async_cancel(void *o, uint32_t op) { (void)o; (void)op; }
static int async_wait(void *o, ssc_runtime *rt, uint64_t d) { (void)o; (void)rt; (void)d; return 0; }
static void log_fn(void *o, const char *m, size_t l) { (void)o; (void)m; (void)l; }

static const char *SCRIPT =
    "var __doc=null;\n"
    "var source={baseUrl:'https://b.test',\n"
    " parse:function(html){__doc=Jsoup.parse(html,'https://b.test/');return 1;},\n"
    " sel:function(s){return __doc.select(s).length;},\n"
    " bulkText:function(){var a=__doc.select('li');var n=0;a.forEach(function(e){n+=e.text().length;});return n;},\n"
    " bulkAttr:function(){var a=__doc.select('a');var n=0;a.forEach(function(e){n+=e.attr('href').length+e.absUrl('href').length;});return n;}};\n";

static double invoke(ssc_runtime *rt, const char *m, const char *args, long *result) {
    char *res = NULL; size_t rl = 0;
    double t0 = bench_now_ms();
    ssc_status st = ssc_invoke(rt, m, args, &res, &rl);
    double t = bench_now_ms() - t0;
    if (st != SSC_OK) { printf("%s failed: %s\n", m, ssc_status_name(st)); exit(1); }
    if (result) *result = res ? atol(res) : 0;
    free(res);
    return t;
}

static char *read_file(const char *p, size_t *n) {
    FILE *f = fopen(p, "rb"); if (!f) { perror(p); exit(2); }
    fseek(f, 0, SEEK_END); *n = (size_t)ftell(f); fseek(f, 0, SEEK_SET);
    char *b = malloc(*n + 1); if (fread(b, 1, *n, f) != *n) exit(2); b[*n] = 0; fclose(f); return b;
}

static char *json_string_arg(const char *html) {   /* ["..."] */
    size_t n = strlen(html);
    char *o = malloc(n * 6 + 8), *p = o;
    *p++ = '['; *p++ = '"';
    for (size_t i = 0; i < n; i++) {
        unsigned char c = (unsigned char)html[i];
        if (c == '"' || c == '\\') { *p++ = '\\'; *p++ = (char)c; }
        else if (c == '\n') { *p++ = '\\'; *p++ = 'n'; }
        else if (c == '\r') { *p++ = '\\'; *p++ = 'r'; }
        else if (c == '\t') { *p++ = '\\'; *p++ = 't'; }
        else if (c < 0x20) p += snprintf(p, 8, "\\u%04x", c);
        else *p++ = (char)c;
    }
    *p++ = '"'; *p++ = ']'; *p = 0;
    return o;
}

static void run(const char *label, const char *html, size_t len, int reps, const char *bootstrap, size_t bl) {
    double tparse[16], tsel[BENCH_NSEL][16], tbulk[16], tabs[16];
    size_t counts[BENCH_NSEL] = {0}, heap = 0;
    double rss0 = bench_rss_mb();
    char *arg = json_string_arg(html);
    for (int r = 0; r < reps; r++) {
        ssc_limits l = { .heap_limit_bytes = (size_t)1 << 30, .stack_limit_bytes = 8u << 20, .init_timeout_ms = 60000,
                         .invoke_timeout_ms = 600000, .max_bridge_calls = 1u << 20, .max_async_inflight = 6, .max_async_queue = 64,
                         .max_result_bytes = 1u << 20, .dom_max_nodes = 2000000, .dom_max_depth = 1024 };
        ssc_host host = { NULL, now_ms, bridge_call, async_start, async_cancel, async_wait, log_fn };
        ssc_runtime *rt = ssc_new(&l, &host);
        if (ssc_init(rt, bootstrap, bl, SCRIPT, strlen(SCRIPT)) != SSC_OK) { printf("init failed\n"); exit(1); }
        tparse[r] = invoke(rt, "parse", arg, NULL);
        heap = ssc_memory_used(rt);
        for (int s = 0; s < BENCH_NSEL; s++) {
            char *sa = json_string_arg(BENCH_SELECTORS[s]);
            long n; tsel[s][r] = invoke(rt, "sel", sa, &n); counts[s] = (size_t)n; free(sa);
        }
        tbulk[r] = invoke(rt, "bulkText", "[]", NULL);
        tabs[r] = invoke(rt, "bulkAttr", "[]", NULL);
        ssc_free(rt);
    }
    printf("| QuickJS + JS bootstrap | %s | %.0f KB | %.2f | ", label, len / 1024.0, bench_median(tparse, reps));
    for (int s = 0; s < BENCH_NSEL; s++) printf("%.2f (%zu) | ", bench_median(tsel[s], reps), counts[s]);
    printf("%.2f | %.2f | %.1f | %.1f |\n", bench_median(tbulk, reps), bench_median(tabs, reps), heap / 1048576.0, bench_rss_mb() - rss0);
    free(arg);
}

int main(int argc, char **argv) {
    if (argc < 2) { fprintf(stderr, "usage: bench_js bootstrap.js [html files]\n"); return 2; }
    size_t bl; char *bootstrap = read_file(argv[1], &bl);
    size_t sizes[] = {50u << 10, 1u << 20};
    for (int i = 0; i < 2; i++) {
        char *h = bench_make_html(sizes[i]);
        char label[32]; snprintf(label, sizeof label, "synthetic %s", i ? "1 MB" : "50 KB");
        run(label, h, strlen(h), i ? 3 : 7, bootstrap, bl);
        free(h);
    }
    for (int a = 2; a < argc; a++) {
        size_t n; char *h = read_file(argv[a], &n);
        const char *base = strrchr(argv[a], '/'); run(base ? base + 1 : argv[a], h, n, 3, bootstrap, bl);
        free(h);
    }
    return 0;
}
