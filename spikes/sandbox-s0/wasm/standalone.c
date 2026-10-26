/*
 * Reference benchmark with no host import: the same core + QuickJS in one program, built both
 * natively and as wasm32-wasi so it can run under wasmtime (JIT, for reference only; iOS cannot
 * JIT). Usage: standalone bootstrap.js
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "shinsou_sandbox_core.h"

static char *html_json;
static uint64_t now_ms(void *o) {
    (void)o;
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000 + (uint64_t)ts.tv_nsec / 1000000;
}
static double now_us(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double)ts.tv_sec * 1e6 + (double)ts.tv_nsec / 1e3;
}
static int bridge_call(void *o, const char *m, const char *a, size_t al, char **out, size_t *ol) {
    (void)o; (void)a; (void)al;
    const char *r = strcmp(m, "httpGet") == 0 ? html_json : "null";
    *out = strdup(r);
    *ol = strlen(r);
    return 0;
}
static int async_start(void *o, uint32_t op, const char *r, size_t l) { (void)o; (void)op; (void)r; (void)l; return 1; }
static void async_cancel(void *o, uint32_t op) { (void)o; (void)op; }
static int async_wait(void *o, ssc_runtime *rt, uint64_t d) { (void)o; (void)rt; (void)d; return 0; }
static void log_fn(void *o, const char *m, size_t l) { (void)o; (void)m; (void)l; }

static const char *SCRIPT =
    "var source={"
    " parseHtml:function(){var d=Jsoup.parse(bridge.httpGet('https://x.test/'),'https://x.test/');"
    "   var t=d.select('.item a').map(function(a){return a.text();});return t.length+':'+t.join('').length;},"
    " fib:function(){function f(n){return n<2?n:f(n-1)+f(n-2);}return f(27);},"
    " loop:function(){var s=0;for(var i=0;i<5000000;i++){s=(s+i*3)%1000003;}return s;},"
    " str:function(){var a=[];for(var i=0;i<100000;i++)a.push('item-'+i);var j=a.join(',');return j.split(',').length+j.indexOf('item-99999');},"
    " sort:function(){var a=[],x=12345;for(var i=0;i<100000;i++){x=(x*1103515245+12345)%2147483648;a.push(x);}a.sort(function(p,q){return p-q;});return a[0]+a[99999];},"
    " json:function(){var o=[];for(var i=0;i<20000;i++)o.push({id:i,name:'n'+i,tags:['a','b'],v:i/3});var s=JSON.stringify(o);return JSON.parse(s).length+s.length;},"
    " rtRaw:function(){for(var i=0;i<20000;i++)__shinsouBridge('getPreference','[\"k\"]');return 1;},"
    "};";

static char *make_html(size_t bytes) {
    char *s = malloc(bytes * 2 + 4096);
    size_t n = (size_t)sprintf(s, "\"<html><body><div id=\\\"list\\\">");
    int items = 0;
    while (n < bytes) {
        n += (size_t)sprintf(s + n, "<div class=\\\"item\\\"><a href=\\\"/m/%d\\\">Title number %d</a><p>some filler description text</p></div>", items, items);
        items++;
    }
    sprintf(s + n, "</div></body></html>\"");
    return s;
}

static void bench(ssc_runtime *rt, const char *hook, int reps, const char *label, double div) {
    double best = 1e18;
    for (int i = 0; i < reps; i++) {
        char *out; size_t len;
        double t0 = now_us();
        ssc_status st = ssc_invoke(rt, hook, "[]", &out, &len);
        double ms = (now_us() - t0) / 1000.0;
        if (st != SSC_OK) { printf("RESULT %s FAIL(%s)\n", label, ssc_status_name(st)); return; }
        free(out);
        if (ms < best) best = ms;
    }
    printf("RESULT %s %.2f\n", label, best / div);
}

int main(int argc, char **argv) {
    if (argc < 2) return 2;
    FILE *f = fopen(argv[1], "rb");
    if (!f) { perror("bootstrap"); return 2; }
    fseek(f, 0, SEEK_END); size_t bl = (size_t)ftell(f); fseek(f, 0, SEEK_SET);
    char *boot = malloc(bl);
    if (fread(boot, 1, bl, f) != bl) return 2;
    fclose(f);
    ssc_limits l = { .heap_limit_bytes = 128u << 20, .stack_limit_bytes = 1u << 20, .init_timeout_ms = 60000,
                     .invoke_timeout_ms = 600000, .max_bridge_calls = 1000000, .max_async_inflight = 6,
                     .max_async_queue = 64, .max_result_bytes = 4u << 20, .dom_max_nodes = 1000000, .dom_max_depth = 512 };
    ssc_host h = { NULL, now_ms, bridge_call, async_start, async_cancel, async_wait, log_fn };
    html_json = make_html(50 * 1024);
    double t0 = now_us();
    ssc_runtime *rt = ssc_new(&l, &h);
    ssc_status st = ssc_init(rt, boot, bl, SCRIPT, strlen(SCRIPT));
    printf("RESULT cold_first_ms %.2f\n", (now_us() - t0) / 1000.0);
    if (st != SSC_OK) { printf("init failed\n"); return 1; }
    bench(rt, "rtRaw", 3, "bridge_raw_us_per_call", 20.0);
    bench(rt, "fib", 3, "js_fib_ms", 1);
    bench(rt, "loop", 3, "js_loop_ms", 1);
    bench(rt, "str", 3, "js_str_ms", 1);
    bench(rt, "sort", 3, "js_sort_ms", 1);
    bench(rt, "json", 3, "js_json_ms", 1);
    bench(rt, "parseHtml", 3, "html50k_ms", 1);
    free(html_json);
    html_json = make_html(1024 * 1024);
    bench(rt, "parseHtml", 1, "html1m_ms", 1);
    ssc_free(rt);
    return 0;
}
