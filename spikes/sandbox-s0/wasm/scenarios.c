/*
 * Scenario tests (mirror of core/test/core_test.c) running against one backend (native or WAMR),
 * plus the WASM-specific terminate / watchdog / memory-cap / isolation cases (ADR-0002 §3.4, §4.5).
 */
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "backend.h"

static int failures = 0;
#define CHECK(cond, ...) do { if (!(cond)) { failures++; printf("  FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

static char *bootstrap;
static size_t bootstrap_len;
static mock_net net;
static int is_wasm;

static const char *SCRIPT =
    "var source={"
    " ok:function(){return 'ok';},"
    " parse:function(){var d=Jsoup.parse(bridge.httpGet('https://x.test/'),'https://x.test/');"
    "   return new MangasPage(d.select('.item a').map(function(a){var m=SManga.create();m.title=a.text();m.url=a.attr('href');return m;}),false);},"
    " loop:function(){for(;;){}},"
    " swallow:function(){for(;;){try{for(;;){}}catch(e){}}},"
    " memory:function(){var a=[];for(;;)a.push('x'.repeat(1024)+a.length);},"
    " memoryCaught:function(){var a=[];try{for(;;)a.push(new Array(1e5).fill(1));}catch(e){a=null;for(;;)Math.random();}},"
    " recurse:function(){function f(){return f()+1;}return f();},"
    " regex:function(){return /(a+)+$/.test('a'.repeat(40)+'!');},"
    " bridgeFlood:function(){for(;;){try{bridge.getPreference('x');}catch(e){}}},"
    " big:function(){return 'y'.repeat(2<<20);},"
    " globals:function(){return [typeof std,typeof os,typeof require,typeof process,typeof __shinsouAsync,typeof fetch,typeof XMLHttpRequest,typeof WebAssembly].join(',');},"
    " fetchMany:async function(){var r=await Promise.all([1,2,3,4,5,6,7,8,9,10].map(function(i){return bridge.net.fetch('https://x.test/'+i);}));return r.map(function(x){return x.text();});},"
    " queueFull:async function(){var ps=[];for(var i=0;i<71;i++)ps.push(bridge.net.fetch('https://x.test/'+i).then(function(){return 'ok';},function(e){return e.code;}));var r=await Promise.all(ps);return r.filter(function(x){return x==='ASYNC_QUEUE_FULL';}).length;},"
    " leak:function(){bridge.net.fetch('https://x.test/late');return 'returned';},"
    " stalled:function(){return new Promise(function(){});},"
    " prep:function(){globalThis.arr=[];for(var i=0;i<400000;i++)globalThis.arr.push((i*7919)%400009);return arr.length;},"
    " nativeSort:function(){globalThis.arr.sort();return arr[0];},"
    " slowFetch:async function(){var r=await bridge.net.fetch('https://x.test/slow');return r.text();},"
    " mixed:async function(){var a=bridge.net.fetch('https://x.test/a');var s=bridge.httpGet('https://x.test/');var r=await a;return [r.text(),s.length>0];},"
    "};";

static int time_scale = 1;

static be_limits default_limits(void) {
    be_limits l = {
        .heap = 16u << 20, .stack = 1u << 20, .init_ms = 2000, .invoke_ms = 300, .max_bridge = 4096,
        .inflight = 6, .queue = 64, .max_result = 1u << 20, .dom_nodes = 100000, .dom_depth = 512,
        .linear_max = 128u << 20, .wasm_stack = 16u << 20, .watchdog_ms = 0,
    };
    if (getenv("SW_WASM_STACK")) l.wasm_stack = (size_t)atoi(getenv("SW_WASM_STACK"));
    l.init_ms *= (uint32_t)time_scale;
    l.invoke_ms *= (uint32_t)time_scale;
    return l;
}

static backend *make_with(be_limits l, const char *script, int *init_status) {
    mock_reset(&net);
    backend *b = NULL;
    if (be_create(&b, &l, &net) != 0) { CHECK(0, "be_create failed"); return NULL; }
    int st = be_init(b, bootstrap, bootstrap_len, script, strlen(script));
    if (init_status) *init_status = st;
    CHECK(st == 0, "init failed: %s", be_status_name(st));
    return b;
}

static backend *make(be_limits l, const char *script) { return make_with(l, script, NULL); }

static int run(backend *b, const char *method, char **out) {
    size_t len;
    *out = NULL;
    return be_invoke(b, method, "[]", out, &len);
}

static void expect(backend *b, const char *method, int want, const char *label) {
    char *out;
    uint64_t t0 = mock_now_ms();
    int st = run(b, method, &out);
    printf("  %-38s -> %-16s (%llu ms)\n", label, be_status_name(st), (unsigned long long)(mock_now_ms() - t0));
    CHECK(st == want, "%s: want %s got %s", label, be_status_name(want), be_status_name(st));
    free(out);
}

typedef struct { backend *b; unsigned delay_ms; } term_arg;
static void *terminate_later(void *p) {
    term_arg *a = p;
    usleep(a->delay_ms * 1000);
    be_cancel(a->b);
    return NULL;
}

/* A second instance that keeps working on its own thread while the first is terminated. */
typedef struct { int ok_runs, bad_runs; volatile int stop; } neighbour;
static void *neighbour_main(void *p) {
    neighbour *n = p;
    be_thread_attach();
    mock_net local;
    mock_init(&local, 0);
    be_limits l = default_limits();
    backend *b = NULL;
    if (be_create(&b, &l, &local) != 0) { n->bad_runs++; return NULL; }
    be_init(b, bootstrap, bootstrap_len, SCRIPT, strlen(SCRIPT));
    while (!n->stop) {
        char *out; size_t len;
        int st = be_invoke(b, "ok", "[]", &out, &len);
        if (st == 0 && out && strcmp(out, "\"ok\"") == 0) n->ok_runs++; else n->bad_runs++;
        free(out);
        usleep(2000);
    }
    be_destroy(b);
    be_thread_detach();
    return NULL;
}

int main(int argc, char **argv) {
    if (argc < 2) { fprintf(stderr, "usage: sw_test bootstrap.js\n"); return 2; }
    FILE *f = fopen(argv[1], "rb");
    if (!f) { perror("bootstrap"); return 2; }
    fseek(f, 0, SEEK_END); bootstrap_len = (size_t)ftell(f); fseek(f, 0, SEEK_SET);
    bootstrap = malloc(bootstrap_len);
    if (fread(bootstrap, 1, bootstrap_len, f) != bootstrap_len) return 2;
    fclose(f);
    mock_init(&net, 0);
    if (getenv("SW_TIME_SCALE")) time_scale = atoi(getenv("SW_TIME_SCALE"));
    be_thread_attach();

    printf("backend: %s\n", be_name());
    be_limits l = default_limits();
    backend *b = make(l, SCRIPT);
    is_wasm = be_footprint(b) != 0;
    char *out;

    printf("[functional]\n");
    int st = run(b, "parse", &out);
    CHECK(st == 0 && out && strstr(out, "\"title\":\"Two\"") && strstr(out, "/m/1"), "parse %s: %s", be_status_name(st), out ? out : "(null)");
    printf("  parse -> %s\n", out ? out : "(null)"); free(out);
    st = run(b, "globals", &out);
    CHECK(st == 0 && out && strcmp(out, "\"undefined,undefined,undefined,undefined,undefined,undefined,undefined,undefined\"") == 0, "globals: %s", out ? out : "(null)");
    printf("  globals -> %s\n", out ? out : "(null)"); free(out);

    printf("[limits]\n");
    expect(b, "loop", SSC_ERR_LIMIT_TIME, "infinite loop");
    expect(b, "swallow", SSC_ERR_LIMIT_TIME, "try/catch swallowing interrupt");
    expect(b, "regex", SSC_ERR_LIMIT_TIME, "catastrophic backtracking regex");
    expect(b, "recurse", SSC_ERR_LIMIT_STACK, "unbounded recursion (deep)");
    {   /* a tight C loop (string repeat) is ~30x slower in the interpreter: give the bomb time to fill 16 MiB */
        be_limits bl = l; bl.invoke_ms = 10000u * (uint32_t)time_scale;
        backend *mb = make(bl, SCRIPT);
        expect(mb, "memory", SSC_ERR_LIMIT_MEMORY, "memory bomb (JS heap limit 16 MiB)");
        be_limits cl = l; cl.invoke_ms = 800u * (uint32_t)time_scale;
        be_destroy(mb);
        mb = make(cl, SCRIPT);
        expect(mb, "memoryCaught", SSC_ERR_LIMIT_TIME, "OOM caught then spin");
        be_destroy(mb);
    }
    expect(b, "bridgeFlood", SSC_ERR_LIMIT_BRIDGE, "bridge flood with catch");
    expect(b, "big", SSC_ERR_LIMIT_RESULT, "oversized result");
    expect(b, "missing", SSC_ERR_NO_SUCH_HOOK, "missing hook");
    expect(b, "ok", SSC_OK, "instance reusable after limits");
    printf("  JS heap after limit tests: %zu bytes; linear memory: %zu bytes\n", be_js_heap(b), be_footprint(b));

    pthread_t t;
    term_arg ta = { b, 50 };
    pthread_create(&t, NULL, terminate_later, &ta);
    printf("[cancel/terminate from another thread]\n");
    st = run(b, "loop", &out);
    pthread_join(t, NULL);
    printf("  terminate(50 ms) during loop -> %s alive=%d\n", be_status_name(st), be_alive(b));
    if (is_wasm) {
        CHECK(st == SW_ERR_TRAPPED && !be_alive(b), "terminate must kill the instance");
        be_destroy(b);
        b = make(l, SCRIPT);
        expect(b, "ok", SSC_OK, "rebuilt instance works");
    } else {
        CHECK(st == SSC_ERR_CANCELLED, "native cancel");
        expect(b, "ok", SSC_OK, "runtime reusable after cancel");
    }

    printf("[async]\n");
    mock_reset(&net);
    st = run(b, "fetchMany", &out);
    CHECK(st == 0 && out && strstr(out, "op-"), "fetchMany: %s %s", be_status_name(st), out ? out : "(null)");
    printf("  fetchMany -> %s (max in flight %d)\n", out ? out : "(null)", net.max_inflight_seen); free(out);
    CHECK(net.max_inflight_seen <= 6, "max in flight %d > 6", net.max_inflight_seen);
    CHECK(net.started_count == 10, "started %d", net.started_count);

    mock_reset(&net);
    st = run(b, "queueFull", &out);
    CHECK(st == 0 && out && strcmp(out, "1") == 0, "queueFull rejected count: %s", out ? out : "(null)");
    printf("  queueFull rejected -> %s\n", out ? out : "(null)"); free(out);

    mock_reset(&net);
    st = run(b, "leak", &out);
    CHECK(st == 0, "leak"); free(out);
    CHECK(net.cancelled == 1, "pending op must be cancelled at invocation end, cancelled=%d", net.cancelled);
    expect(b, "ok", SSC_OK, "next invocation after late result");

    mock_reset(&net);
    st = run(b, "mixed", &out);
    CHECK(st == 0 && out && strstr(out, "true"), "mixed: %s", out ? out : "(null)");
    printf("  mixed sync+async -> %s\n", out ? out : "(null)"); free(out);

    /* Real asynchrony: the host returns from async_start at once, results arrive 30 ms later from
       other threads and are delivered while the guest waits inside host_bridge(ASYNC_WAIT). */
    mock_reset(&net);
    net.delay_ms = 30;
    uint64_t t0 = mock_now_ms();
    st = run(b, "fetchMany", &out);
    uint64_t dt = mock_now_ms() - t0;
    CHECK(st == 0 && out && strstr(out, "op-"), "delayed fetchMany: %s", out ? out : "(null)");
    printf("  fetchMany with 30 ms latency -> %s in %llu ms (6 in flight, 10 req => ~2 waves)\n", out ? "ok" : "(null)", (unsigned long long)dt);
    CHECK(dt >= 55 && dt < 600, "expected ~60 ms (2 waves), got %llu", (unsigned long long)dt);
    free(out);
    net.delay_ms = 0;
    be_destroy(b);

    be_limits stall = l;
    b = make(l, SCRIPT);
    (void)stall;
    mock_reset(&net);
    /* never-settling promise: no async work is left, so the core must fail rather than wait. */
    expect(b, "stalled", SSC_ERR_PROMISE_STALLED, "never-settling promise");
    be_destroy(b);

    if (is_wasm) {
        printf("[wasm: hard terminate / isolation]\n");
        mock_reset(&net);

        /* 1. watchdog kills a call whose JS deadline is far away (stands in for a guest that never
              reaches an interrupt check). */
        be_limits wl = l; wl.invoke_ms = 60000; wl.watchdog_ms = 200;
        b = make(wl, SCRIPT);
        t0 = mock_now_ms();
        st = run(b, "loop", &out);
        dt = mock_now_ms() - t0;
        printf("  watchdog 200 ms on loop (JS deadline 60 s) -> %s after %llu ms, alive=%d\n", be_status_name(st), (unsigned long long)dt, be_alive(b));
        CHECK(st == SW_ERR_TRAPPED && !be_alive(b) && dt < 400, "watchdog");
        st = run(b, "ok", &out);
        CHECK(st == SW_ERR_TRAPPED, "dead instance must refuse further calls");
        be_destroy(b);

        /* 2. terminate while the guest is blocked in ASYNC_WAIT (host import). */
        mock_reset(&net);
        net.delay_ms = 5000;
        wl.watchdog_ms = 200;
        b = make(wl, SCRIPT);
        t0 = mock_now_ms();
        st = run(b, "slowFetch", &out);
        dt = mock_now_ms() - t0;
        printf("  watchdog 200 ms while blocked in async wait -> %s after %llu ms\n", be_status_name(st), (unsigned long long)dt);
        CHECK(st == SW_ERR_TRAPPED && dt < 600, "terminate while blocked in host import: %llu ms", (unsigned long long)dt);
        be_destroy(b);
        mock_reset(&net);
        net.delay_ms = 0;

        /* 3. neighbour instance (other thread) is unaffected by a terminate. */
        neighbour nb = {0, 0, 0};
        pthread_t nt;
        pthread_create(&nt, NULL, neighbour_main, &nb);
        usleep(100 * 1000);
        int before = nb.ok_runs;
        b = make(wl, SCRIPT);
        ta = (term_arg){ b, 80 };
        pthread_create(&t, NULL, terminate_later, &ta);
        be_limits long_l = l; long_l.invoke_ms = 60000; (void)long_l;
        st = run(b, "loop", &out);
        pthread_join(t, NULL);
        CHECK(st == SW_ERR_TRAPPED, "terminate kills loop");
        be_destroy(b);
        usleep(100 * 1000);
        nb.stop = 1;
        pthread_join(nt, NULL);
        printf("  neighbour instance ran %d ok / %d bad calls (%d before the terminate)\n", nb.ok_runs, nb.bad_runs, before);
        CHECK(nb.bad_runs == 0 && nb.ok_runs > before, "neighbour disturbed");

        /* 3b. a long stretch of pure engine code (Array.prototype.sort: no interrupt check, no import
               call): can the runtime still preempt it? Reported for each WASM runtime. */
        {
            mock_reset(&net);
            be_limits sl2 = l; sl2.invoke_ms = 120000; sl2.init_ms = 120000; sl2.watchdog_ms = 0;
            b = make(sl2, SCRIPT);
            st = run(b, "prep", &out); free(out);
            CHECK(st == 0, "prep");
            /* un-interrupted duration first */
            t0 = mock_now_ms();
            st = run(b, "nativeSort", &out); free(out);
            uint64_t full = mock_now_ms() - t0;
            be_destroy(b);
            b = make(sl2, SCRIPT);
            run(b, "prep", &out); free(out);
            ta = (term_arg){ b, (unsigned)(full / 4 + 1) };
            pthread_create(&t, NULL, terminate_later, &ta);
            t0 = mock_now_ms();
            st = run(b, "nativeSort", &out); free(out);
            dt = mock_now_ms() - t0;
            pthread_join(t, NULL);
            printf("  engine-only stretch (sort 400k): %llu ms alone; terminate after %u ms -> %s after %llu ms\n",
                   (unsigned long long)full, (unsigned)(full / 4 + 1), be_status_name(st), (unsigned long long)dt);
            be_destroy(b);
        }

        /* 4. linear memory hard cap below the JS heap limit: the allocator fails first. */
        mock_reset(&net);
        be_limits ml = l; ml.heap = 256u << 20; ml.linear_max = 24u << 20; ml.invoke_ms = 20000;
        b = make(ml, SCRIPT);
        t0 = mock_now_ms();
        st = run(b, "memory", &out);
        printf("  linear memory cap 24 MiB (JS heap limit 256 MiB), memory bomb -> %s (%llu ms), linear=%zu MiB, alive=%d\n",
               be_status_name(st), (unsigned long long)(mock_now_ms() - t0), be_footprint(b) >> 20, be_alive(b));
        CHECK(st == SSC_ERR_LIMIT_MEMORY || st == SW_ERR_TRAPPED, "linear cap -> %s", be_status_name(st));
        if (strcmp(be_name(), "wasm2c") != 0) CHECK(be_footprint(b) <= (24u << 20), "linear memory %zu above cap", be_footprint(b)); /* wasm2c: cap is the module's link-time max */
        if (be_alive(b)) expect(b, "ok", SSC_OK, "instance usable after linear-cap OOM");
        be_destroy(b);

        /* 5. recreate 50 instances: nothing leaks into later ones. */
        mock_reset(&net);
        int good = 0;
        for (int i = 0; i < 50; i++) {
            b = make(l, SCRIPT);
            if (!b) break;
            if (run(b, "ok", &out) == 0) good++;
            free(out);
            be_destroy(b);
        }
        CHECK(good == 50, "recreate loop %d/50", good);
        printf("  create/destroy x50 -> %d ok\n", good);
    }

    printf(failures ? "\n%d FAILURE(S)\n" : "\nALL PASSED\n", failures);
    be_thread_detach();
    return failures ? 1 : 0;
}
