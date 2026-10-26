/* How close is the existing pure-JS DOM bootstrap (iOS) to jsoup?  Prints "### fixture / SEL / count=N"
 * in the golden format; compare with scripts/compare_golden.py --counts-only. */
#include <stdint.h>
#include "bench_common.h"
#include "shinsou_sandbox_core.h"

static uint64_t now_ms(void *o) { (void)o; return (uint64_t)bench_now_ms(); }
static int bridge_call(void *o, const char *m, const char *a, size_t l, char **out, size_t *ol) { (void)o; (void)m; (void)a; (void)l; *out = strdup("null"); *ol = 4; return 0; }
static int async_start(void *o, uint32_t op, const char *r, size_t l) { (void)o; (void)op; (void)r; (void)l; return -1; }
static void async_cancel(void *o, uint32_t op) { (void)o; (void)op; }
static int async_wait(void *o, ssc_runtime *rt, uint64_t d) { (void)o; (void)rt; (void)d; return 0; }
static void log_fn(void *o, const char *m, size_t l) { (void)o; (void)m; (void)l; }

static const char *SCRIPT =
    "var __doc=null;\n"
    "var source={baseUrl:'https://b.test',\n"
    " parse:function(html,base){__doc=Jsoup.parse(html,base);return 1;},\n"
    " sel:function(s){try{return __doc.select(s).length;}catch(e){return -1;}}};\n";

static char *slurp(const char *p, size_t *n) {
    FILE *f = fopen(p, "rb"); if (!f) { perror(p); exit(2); }
    fseek(f, 0, SEEK_END); *n = (size_t)ftell(f); fseek(f, 0, SEEK_SET);
    char *b = malloc(*n + 1); if (fread(b, 1, *n, f) != *n) exit(2); b[*n] = 0; fclose(f); return b;
}
static char *json_arr(const char *a, const char *b) {   /* ["a","b"] (b optional) */
    size_t cap = (strlen(a) + (b ? strlen(b) : 0)) * 6 + 16;
    char *o = malloc(cap), *p = o;
    const char *parts[2] = {a, b};
    *p++ = '[';
    for (int k = 0; k < 2; k++) {
        if (!parts[k]) break;
        if (k) *p++ = ',';
        *p++ = '"';
        for (const unsigned char *s = (const unsigned char *)parts[k]; *s; s++) {
            if (*s == '"' || *s == '\\') { *p++ = '\\'; *p++ = (char)*s; }
            else if (*s == '\n') { *p++ = '\\'; *p++ = 'n'; }
            else if (*s == '\r') { *p++ = '\\'; *p++ = 'r'; }
            else if (*s == '\t') { *p++ = '\\'; *p++ = 't'; }
            else if (*s < 0x20) p += snprintf(p, 8, "\\u%04x", *s);
            else *p++ = (char)*s;
        }
        *p++ = '"';
    }
    *p++ = ']'; *p = 0;
    return o;
}

int main(int argc, char **argv) {
    if (argc < 4) { fprintf(stderr, "usage: parity_js bootstrap.js golden-dir selectors...\n"); return 2; }
    size_t bl; char *bootstrap = slurp(argv[1], &bl);
    char path[4096];
    snprintf(path, sizeof path, "%s/fixtures.txt", argv[2]);
    size_t fn; char *fixtures = slurp(path, &fn);
    char **sels = NULL; size_t nsel = 0;
    for (int a = 3; a < argc; a++) {
        size_t sn; char *txt = slurp(argv[a], &sn);
        for (char *p = txt; p < txt + sn;) {
            char *e = strchr(p, '\n'); if (!e) e = txt + sn; *e = 0;
            sels = realloc(sels, (nsel + 1) * sizeof *sels); sels[nsel++] = p; p = e + 1;
        }
    }
    for (char *line = strtok(fixtures, "\n"); line; line = strtok(NULL, "\n")) {
        char *name = line, *base = strchr(name, '\t'); *base++ = 0;
        char *file = strchr(base, '\t'); *file++ = 0;
        snprintf(path, sizeof path, "%s/%s", argv[2], file);
        size_t hn; char *html = slurp(path, &hn);
        ssc_limits l = { .heap_limit_bytes = (size_t)1 << 30, .stack_limit_bytes = 8u << 20, .init_timeout_ms = 60000,
                         .invoke_timeout_ms = 600000, .max_bridge_calls = 1u << 20, .max_async_inflight = 6, .max_async_queue = 64,
                         .max_result_bytes = 1u << 20, .dom_max_nodes = 2000000, .dom_max_depth = 1024 };
        ssc_host host = { NULL, now_ms, bridge_call, async_start, async_cancel, async_wait, log_fn };
        ssc_runtime *rt = ssc_new(&l, &host);
        if (ssc_init(rt, bootstrap, bl, SCRIPT, strlen(SCRIPT)) != SSC_OK) { printf("init failed\n"); return 1; }
        char *res = NULL; size_t rl;
        char *pa = json_arr(html, base);
        if (ssc_invoke(rt, "parse", pa, &res, &rl) != SSC_OK) { printf("### %s\nPARSE FAILED\n", name); ssc_free(rt); continue; }
        free(res); free(pa);
        printf("### %s\n", name);
        for (size_t i = 0; i < nsel; i++) {
            char *sa = json_arr(sels[i], NULL);
            ssc_status st = ssc_invoke(rt, "sel", sa, &res, &rl);
            long n = st == SSC_OK && res ? atol(res) : -1;
            printf("SEL %s\n", sels[i]);
            if (n < 0) printf("  ERROR\n"); else printf("  count=%ld\n", n);
            free(res); res = NULL; free(sa);
        }
        ssc_free(rt);
        free(html);
    }
    return 0;
}
