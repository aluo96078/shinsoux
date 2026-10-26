/*
 * Backend-agnostic S0 benchmark: instance cold start, HTML parse + select, JS microbenchmarks,
 * sync bridge round trip, per-instance memory. Prints "RESULT <metric> <value>" lines that
 * wasm/run-bench.sh turns into the comparison table.
 */
#include <mach/mach.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "backend.h"

static char *bootstrap;
static size_t bootstrap_len;
static mock_net net;
static int reps = 3;

static const char *SCRIPT =
    "var source={"
    " ok:function(){return 'ok';},"
    " parseHtml:function(){var d=Jsoup.parse(bridge.httpGet('https://x.test/'),'https://x.test/');"
    "   var t=d.select('.item a').map(function(a){return a.text();});return t.length+':'+t.join('').length;},"
    " fib:function(){function f(n){return n<2?n:f(n-1)+f(n-2);}return f(27);},"
    " loop:function(){var s=0;for(var i=0;i<5000000;i++){s=(s+i*3)%1000003;}return s;},"
    " str:function(){var a=[];for(var i=0;i<100000;i++)a.push('item-'+i);var j=a.join(',');return j.split(',').length+j.indexOf('item-99999');},"
    " sort:function(){var a=[],x=12345;for(var i=0;i<100000;i++){x=(x*1103515245+12345)%2147483648;a.push(x);}a.sort(function(p,q){return p-q;});return a[0]+a[99999];},"
    " json:function(){var o=[];for(var i=0;i<20000;i++)o.push({id:i,name:'n'+i,tags:['a','b'],v:i/3});var s=JSON.stringify(o);return JSON.parse(s).length+s.length;},"
    " rtShim:function(){for(var i=0;i<20000;i++)bridge.getPreference('k');return 1;},"
    " rtRaw:function(){for(var i=0;i<20000;i++)__shinsouBridge('getPreference','[\"k\"]');return 1;},"
    "};";

static double ms_since(uint64_t t0_us);
static uint64_t now_us(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000 + (uint64_t)ts.tv_nsec / 1000;
}
static double ms_since(uint64_t t0) { return (double)(now_us() - t0) / 1000.0; }

static size_t rss_bytes(void) {
    mach_task_basic_info_data_t info;
    mach_msg_type_number_t cnt = MACH_TASK_BASIC_INFO_COUNT;
    if (task_info(mach_task_self(), MACH_TASK_BASIC_INFO, (task_info_t)&info, &cnt) != KERN_SUCCESS) return 0;
    return info.resident_size;
}

static be_limits limits(void) {
    be_limits l = {
        .heap = 128u << 20, .stack = 1u << 20, .init_ms = 20000, .invoke_ms = 600000, .max_bridge = 1000000,
        .inflight = 6, .queue = 64, .max_result = 4u << 20, .dom_nodes = 1000000, .dom_depth = 512,
        .linear_max = 128u << 20, .wasm_stack = 16u << 20, .watchdog_ms = 0,
    };
    if (getenv("SW_WASM_STACK")) l.wasm_stack = (size_t)atoi(getenv("SW_WASM_STACK"));
    return l;
}

static double create_ms_total;

static backend *fresh(void) {
    be_limits l = limits();
    backend *b = NULL;
    uint64_t c0 = now_us();
    if (be_create(&b, &l, &net) != 0) { fprintf(stderr, "create failed\n"); exit(1); }
    create_ms_total += (double)(now_us() - c0) / 1000.0;
    int st = be_init(b, bootstrap, bootstrap_len, SCRIPT, strlen(SCRIPT));
    if (st != 0) { fprintf(stderr, "init failed %s\n", be_status_name(st)); exit(1); }
    return b;
}

static double timed_invoke(backend *b, const char *hook, char **result) {
    size_t len;
    uint64_t t0 = now_us();
    int st = be_invoke(b, hook, "[]", result, &len);
    double ms = ms_since(t0);
    if (st != 0) { printf("RESULT %s FAIL(%s)\n", hook, be_status_name(st)); return -1; }
    return ms;
}

static double best_of(backend *b, const char *hook, int n, char **last) {
    double best = 1e18;
    for (int i = 0; i < n; i++) {
        char *r = NULL;
        double ms = timed_invoke(b, hook, &r);
        if (ms < 0) return -1;
        if (ms < best) best = ms;
        if (i == n - 1 && last) *last = r; else free(r);
    }
    return best;
}

/* JSON string literal holding `bytes` of HTML made of .item blocks. */
static char *make_html(size_t bytes, int *items_out) {
    size_t cap = bytes + 4096;
    char *s = malloc(cap * 2 + 64);
    size_t n = 0;
    s[n++] = '"';
    int items = 0;
    char frag[256];
    n += (size_t)sprintf(s + n, "<html><body><div id=\\\"list\\\">");
    while (n < bytes) {
        int l = snprintf(frag, sizeof frag,
                         "<div class=\\\"item\\\"><a href=\\\"/m/%d\\\">Title number %d</a><p>some filler description text</p></div>",
                         items, items);
        memcpy(s + n, frag, (size_t)l);
        n += (size_t)l;
        items++;
    }
    n += (size_t)sprintf(s + n, "</div></body></html>\"");
    s[n] = 0;
    *items_out = items;
    return s;
}

int main(int argc, char **argv) {
    if (argc < 2) { fprintf(stderr, "usage: sw_bench bootstrap.js [reps]\n"); return 2; }
    setvbuf(stdout, NULL, _IOLBF, 0);
    if (argc > 2) reps = atoi(argv[2]);
    FILE *f = fopen(argv[1], "rb");
    if (!f) { perror("bootstrap"); return 2; }
    fseek(f, 0, SEEK_END); bootstrap_len = (size_t)ftell(f); fseek(f, 0, SEEK_SET);
    bootstrap = malloc(bootstrap_len);
    if (fread(bootstrap, 1, bootstrap_len, f) != bootstrap_len) return 2;
    fclose(f);
    mock_init(&net, 0);
    be_thread_attach();
    printf("backend %s\n", be_name());

    /* per-instance memory: RSS delta of K initialised instances that each parsed 50 KB */
    {
        int items;
        char *lit = make_html(50 * 1024, &items);
        mock_set_html(&net, lit);
        free(lit);
        char *r = NULL;
        enum { K = 4 };
        backend *warm = fresh(); /* module load + allocator warm-up, kept alive and not counted */
        (void)warm;
        backend *bs[K];
        size_t before = rss_bytes();
        size_t js = 0, lin = 0;
        for (int i = 0; i < K; i++) {
            bs[i] = fresh();
            r = NULL;
            timed_invoke(bs[i], "parseHtml", &r);
            free(r);
            js += be_js_heap(bs[i]);
            lin += be_footprint(bs[i]);
        }
        size_t after = rss_bytes();
        printf("RESULT rss_per_instance_mb %.2f\n", (double)(after - before) / K / 1048576.0);
        printf("RESULT js_heap_per_instance_mb %.2f\n", (double)js / K / 1048576.0);
        printf("RESULT linear_per_instance_mb %.2f\n", (double)lin / K / 1048576.0);
        for (int i = 0; i < K; i++) be_destroy(bs[i]);
        be_destroy(warm);
    }

    /* cold start: first create includes module load/translation, later ones are instantiate+bootstrap */
    uint64_t t0 = now_us();
    backend *b = fresh();
    printf("RESULT cold_first_ms %.2f\n", ms_since(t0));
    be_destroy(b);
    int n = 10;
    double total = 0, best = 1e18;
    create_ms_total = 0;
    for (int i = 0; i < n; i++) {
        t0 = now_us();
        b = fresh();
        double ms = ms_since(t0);
        total += ms;
        if (ms < best) best = ms;
        be_destroy(b);
    }
    printf("RESULT cold_mean_ms %.2f\n", total / n);
    printf("RESULT cold_min_ms %.2f\n", best);
    printf("RESULT cold_create_only_mean_ms %.2f\n", create_ms_total / n);

    b = fresh();
    char *r = NULL;
    /* sync bridge round trip */
    double ms = best_of(b, "rtShim", reps, NULL);
    printf("RESULT bridge_shim_us_per_call %.2f\n", ms * 1000.0 / 20000);
    ms = best_of(b, "rtRaw", reps, NULL);
    printf("RESULT bridge_raw_us_per_call %.2f\n", ms * 1000.0 / 20000);

    /* microbenchmarks */
    const char *micro[] = { "fib", "loop", "str", "sort", "json" };
    for (size_t i = 0; i < sizeof micro / sizeof micro[0]; i++) {
        ms = best_of(b, micro[i], reps, &r);
        printf("RESULT js_%s_ms %.2f\n", micro[i], ms);
        free(r);
    }

    /* HTML parse + select text */
    int sizes[] = { 50 * 1024, 1024 * 1024 };
    const char *labels[] = { "html50k", "html1m" };
    for (int k = 0; k < 2; k++) {
        int items;
        char *lit = make_html((size_t)sizes[k], &items);
        mock_set_html(&net, lit);
        free(lit);
        r = NULL;
        ms = best_of(b, "parseHtml", k == 0 ? reps : 1, &r);
        printf("RESULT %s_ms %.2f\n", labels[k], ms);
        printf("INFO %s items=%d result=%s heap=%zu linear=%zu\n", labels[k], items, r ? r : "(fail)", be_js_heap(b), be_footprint(b));
        free(r);
        if (!be_alive(b)) break;
    }
    be_destroy(b);
    mock_set_html(&net, "\"<div class=\\\"item\\\"><a href=\\\"/m/1\\\">One</a></div>\"");

    be_thread_detach();
    return 0;
}
