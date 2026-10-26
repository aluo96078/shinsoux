/* lexbor-backed sandbox_dom timings: parse, select, bulk accessor use. */
#include <stdint.h>
#include "bench_common.h"
#include "sandbox_dom.h"

static void run(const char *label, const char *html, size_t len, int reps) {
    sbx_dom_limits lim;
    sbx_dom_limits_default(&lim);
    lim.max_nodes = 2000000; lim.max_input_bytes = 16u << 20; lim.max_handles = 65535; lim.max_depth = 1024;
    lim.max_memory_bytes = (size_t)1 << 30;
    double rss0 = bench_rss_mb();
    double tparse[32], tsel[BENCH_NSEL][32], tbulk[32], tabs[32];
    size_t counts[BENCH_NSEL] = {0}, mem_peak = 0;
    uint32_t nodes_hint = 0;
    for (int r = 0; r < reps; r++) {
        sbx_dom *c = sbx_dom_new(&lim);
        double t0 = bench_now_ms();
        int32_t d = sbx_dom_parse(c, html, len, "https://b.test/");
        tparse[r] = bench_now_ms() - t0;
        if (d <= 0) { printf("parse failed %d\n", d); exit(1); }
        for (int s = 0; s < BENCH_NSEL; s++) {
            int32_t *hs; size_t n;
            t0 = bench_now_ms();
            int rc = sbx_dom_select(c, d, BENCH_SELECTORS[s], &hs, &n);
            tsel[s][r] = bench_now_ms() - t0;
            if (rc) { printf("select %s -> %d\n", BENCH_SELECTORS[s], rc); exit(1); }
            counts[s] = n;
            for (size_t i = 0; i < n; i++) sbx_dom_release(c, hs[i]);
            sbx_dom_free_array(hs);
        }
        /* typical plugin loop: select li, read text of each, then attr+absUrl of each link */
        int32_t *lis; size_t nli, l;
        t0 = bench_now_ms();
        sbx_dom_select(c, d, "li", &lis, &nli);
        for (size_t i = 0; i < nli; i++) sbx_dom_text(c, lis[i], &l);
        tbulk[r] = bench_now_ms() - t0;
        for (size_t i = 0; i < nli; i++) sbx_dom_release(c, lis[i]);
        sbx_dom_free_array(lis);
        int32_t *as; size_t na;
        t0 = bench_now_ms();
        sbx_dom_select(c, d, "a", &as, &na);
        for (size_t i = 0; i < na; i++) { sbx_dom_attr(c, as[i], "href", &l); sbx_dom_abs_url(c, as[i], "href", &l); }
        tabs[r] = bench_now_ms() - t0;
        for (size_t i = 0; i < na; i++) sbx_dom_release(c, as[i]);
        sbx_dom_free_array(as);
        sbx_dom_stats st; sbx_dom_get_stats(c, &st);
        mem_peak = st.memory_peak_bytes;
        (void)nodes_hint;
        sbx_dom_free(c);
    }
    printf("| lexbor | %s | %.0f KB | %.2f | ", label, len / 1024.0, bench_median(tparse, reps));
    for (int s = 0; s < BENCH_NSEL; s++) printf("%.2f (%zu) | ", bench_median(tsel[s], reps), counts[s]);
    printf("%.2f | %.2f | %.1f | %.1f |\n", bench_median(tbulk, reps), bench_median(tabs, reps), mem_peak / 1048576.0, bench_rss_mb() - rss0);
}

int main(int argc, char **argv) {
    printf("| engine | doc | size | parse ms |");
    for (int s = 0; s < BENCH_NSEL; s++) printf(" select `%s` ms (n) |", BENCH_SELECTORS[s]);
    printf(" text() of every li ms | attr+absUrl of every a ms | peak MiB (parser) | RSS growth MiB |\n");
    printf("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
    size_t sizes[] = {50u << 10, 1u << 20};
    for (int i = 0; i < 2; i++) {
        char *h = bench_make_html(sizes[i]);
        char label[32]; snprintf(label, sizeof label, "synthetic %s", i ? "1 MB" : "50 KB");
        run(label, h, strlen(h), i ? 7 : 15);
        free(h);
    }
    for (int a = 1; a < argc; a++) {
        FILE *f = fopen(argv[a], "rb"); if (!f) continue;
        fseek(f, 0, SEEK_END); long n = ftell(f); fseek(f, 0, SEEK_SET);
        char *h = malloc((size_t)n + 1); if (fread(h, 1, (size_t)n, f) != (size_t)n) return 1; h[n] = 0; fclose(f);
        const char *base = strrchr(argv[a], '/'); run(base ? base + 1 : argv[a], h, (size_t)n, 7);
        free(h);
    }
    return 0;
}
