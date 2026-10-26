/*
 * lexbor (sandbox_dom) benchmark body. The same source runs natively (db_main.c) and as a wasm
 * module (WAMR fast interpreter, wasm2c). Timing comes from the single import host_clock_ns.
 * Mirrors html/test/bench_dom.c so the numbers divide against html/README.md section 6.
 */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "sandbox_dom.h"

#ifdef __wasi__
__attribute__((import_module("env"), import_name("host_clock_ns"))) int64_t host_clock_ns(void);
/* wasi-libc's clock_time_get would otherwise become a WASI import: resolve it here instead. */
int32_t __imported_wasi_snapshot_preview1_clock_time_get(int32_t id, int64_t p, int64_t *out) { (void)id; (void)p; *out = host_clock_ns(); return 0; }
int32_t __imported_wasi_snapshot_preview1_fd_close(int32_t fd) { (void)fd; return 8; }
int32_t __imported_wasi_snapshot_preview1_fd_fdstat_get(int32_t fd, void *s) { (void)fd; (void)s; return 8; }
int32_t __imported_wasi_snapshot_preview1_fd_seek(int32_t fd, int64_t o, int32_t w, void *r) { (void)fd; (void)o; (void)w; (void)r; return 8; }
int32_t __imported_wasi_snapshot_preview1_fd_write(int32_t fd, const void *i, int32_t n, void *r) { (void)fd; (void)i; (void)n; (void)r; return 8; }
#define EXPORT(n) __attribute__((export_name(n), used))
#else
#include <time.h>
static int64_t host_clock_ns(void) { struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts); return (int64_t)ts.tv_sec * 1000000000 + ts.tv_nsec; }
#define EXPORT(n)
#endif

static double now_ms(void) { return (double)host_clock_ns() / 1e6; }

static const char *const SEL[] = {
    "a[href*='/manhua-']", "ul.mh-list > li:nth-child(2n+1) a", "li:has(img)", "a:contains(Next)", "span.tag",
};
#define NSEL 5

/* out layout (doubles): [0]=parse ms, [1..5]=select ms, [6]=text of every li, [7]=attr+absUrl of every a,
 * [8..12]=selector match counts, [13]=peak parser MiB, [14]=node count */
#define OUT_N 15

static int cmp_d(const void *a, const void *b) { double x = *(const double *)a, y = *(const double *)b; return x < y ? -1 : x > y; }

EXPORT("db_alloc") void *db_alloc(uint32_t n) { return malloc(n ? n : 1); }
EXPORT("db_free") void db_free(void *p) { free(p); }

/* Manga-list style page, same generator as html/test/bench_common.h. */
EXPORT("db_make_html") char *db_make_html(uint32_t target, uint32_t *len_out) {
    size_t cap = (size_t)target + 4096;
    char *b = malloc(cap);
    size_t n = (size_t)snprintf(b, cap, "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>List</title></head><body><div id=\"wrap\"><ul class=\"mh-list\">");
    for (int i = 0; n + 600 < target; i++) {
        n += (size_t)snprintf(b + n, cap - n,
            "<li class=\"item n%d\"><a class=\"cover\" href=\"/manhua-title-%d/\" title=\"Title %d\"><img src=\"//img.test/c/%d.jpg\" alt=\"cover %d\"></a>"
            "<div class=\"info\"><h3 class=\"name\"><a href=\"/manhua-title-%d/\">Title %d \xe6\xa8\x99\xe9\xa1\x8c</a></h3>"
            "<p class=\"desc\">Some description text &amp; entities number %d lorem ipsum dolor sit amet</p>"
            "<span class=\"tag\">tag%d</span><span class=\"tag\">tag%d</span></div></li>\n",
            i, i, i, i, i, i, i, i, i % 7, i % 5);
    }
    n += (size_t)snprintf(b + n, cap - n, "</ul><div class=\"pager\"><a class=\"prev\" href=\"?p=1\">Prev</a><a class=\"next\" href=\"?p=3\">Next</a></div></div></body></html>");
    *len_out = (uint32_t)n;
    return b;
}

/* Returns 0 on success; medians over `reps` in out[]. */
EXPORT("db_run") int32_t db_run(const char *html, uint32_t len, uint32_t reps, double *out) {
    if (reps > 32) reps = 32;
    sbx_dom_limits lim;
    sbx_dom_limits_default(&lim);
    lim.max_nodes = 2000000; lim.max_input_bytes = 16u << 20; lim.max_handles = 65535; lim.max_depth = 1024;
    lim.max_memory_bytes = (size_t)1 << 30;
    double t[OUT_N - 3][32];
    size_t counts[NSEL] = {0};
    double peak = 0, nodes = 0;
    for (uint32_t r = 0; r < reps; r++) {
        sbx_dom *c = sbx_dom_new(&lim);
        if (!c) return -1;
        double t0 = now_ms();
        int32_t d = sbx_dom_parse(c, html, len, "https://b.test/");
        t[0][r] = now_ms() - t0;
        if (d <= 0) return d ? d : -2;
        for (int s = 0; s < NSEL; s++) {
            int32_t *hs; size_t n;
            t0 = now_ms();
            int rc = sbx_dom_select(c, d, SEL[s], &hs, &n);
            t[1 + s][r] = now_ms() - t0;
            if (rc) return rc;
            counts[s] = n;
            for (size_t i = 0; i < n; i++) sbx_dom_release(c, hs[i]);
            sbx_dom_free_array(hs);
        }
        int32_t *lis; size_t nli, l;
        t0 = now_ms();
        sbx_dom_select(c, d, "li", &lis, &nli);
        for (size_t i = 0; i < nli; i++) sbx_dom_text(c, lis[i], &l);
        t[6][r] = now_ms() - t0;
        for (size_t i = 0; i < nli; i++) sbx_dom_release(c, lis[i]);
        sbx_dom_free_array(lis);
        int32_t *as; size_t na;
        t0 = now_ms();
        sbx_dom_select(c, d, "a", &as, &na);
        for (size_t i = 0; i < na; i++) { sbx_dom_attr(c, as[i], "href", &l); sbx_dom_abs_url(c, as[i], "href", &l); }
        t[7][r] = now_ms() - t0;
        for (size_t i = 0; i < na; i++) sbx_dom_release(c, as[i]);
        sbx_dom_free_array(as);
        sbx_dom_stats st; sbx_dom_get_stats(c, &st);
        peak = st.memory_peak_bytes / 1048576.0;
        nodes = st.last_parse_nodes;
        sbx_dom_free(c);
    }
    for (int k = 0; k < 8; k++) { qsort(t[k], reps, sizeof(double), cmp_d); out[k] = t[k][reps / 2]; }
    for (int s = 0; s < NSEL; s++) out[8 + s] = (double)counts[s];
    out[13] = peak;
    out[14] = nodes;
    return 0;
}

/* Worst case from html/README.md 7.2: lexbor's :nth-child is O(n^2) over a very wide sibling list and
 * cannot be interrupted by the JS interrupt handler. */
EXPORT("db_make_wide") char *db_make_wide(uint32_t n, uint32_t *len_out) {
    char *b = malloc((size_t)n * 12 + 64);
    size_t k = (size_t)sprintf(b, "<ul>");
    for (uint32_t i = 0; i < n; i++) k += (size_t)sprintf(b + k, "<li>x</li>");
    k += (size_t)sprintf(b + k, "</ul>");
    *len_out = (uint32_t)k;
    return b;
}
EXPORT("db_wide_select") int32_t db_wide_select(const char *html, uint32_t len) {
    sbx_dom_limits lim;
    sbx_dom_limits_default(&lim);
    lim.max_nodes = 2000000; lim.max_input_bytes = 16u << 20; lim.max_handles = 65535; lim.max_depth = 1024;
    lim.max_memory_bytes = (size_t)1 << 30;
    sbx_dom *c = sbx_dom_new(&lim);
    int32_t d = sbx_dom_parse(c, html, len, "https://b.test/");
    int32_t *hs; size_t n = 0;
    int rc = sbx_dom_select(c, d, "li:nth-child(2n+1)", &hs, &n);
    return rc ? rc : (int32_t)n;
}
