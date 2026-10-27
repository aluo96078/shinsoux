/* Malicious-corpus and async-semantics tests for shinsou-sandbox-core (ADR-0002 §13). */
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include "ssc_core.h"

static int failures = 0;
#define CHECK(cond, ...) do { if (!(cond)) { failures++; printf("  FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

typedef struct {
    uint32_t started[256];
    int started_count;
    int delivered_upto;
    int max_inflight_seen;
    int inflight;
    int cancelled;
    uint32_t last_op;
    int logs;
    int host_bridge_calls;
    ssc_runtime *reenter_rt; /* when set, async_cancel re-enters ssc_deliver_async synchronously */
    int reentered_accepted;
    int start_mode;          /* async_start re-entry: 1 self, 2 an older op, 3 self then report failure */
    int delivered[256];      /* per started index: already delivered by the host */
    int fail_deliveries;     /* async_wait reports network failures instead of bodies */
} mock_host;

static uint64_t now_ms(void *o) {
    (void)o;
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000 + (uint64_t)ts.tv_nsec / 1000000;
}

static int bridge_call(void *o, const char *method, const char *args, size_t len, char **out, size_t *out_len) {
    (void)args; (void)len;
    mock_host *mh = o;
    mh->host_bridge_calls++;
    /* Called from a script `then` getter while a delivery settles: try to deliver the same op again. */
    if (mh->reenter_rt && strcmp(method, "getPreference") == 0 && strstr(args, "reenter"))
        if (ssc_deliver_async(mh->reenter_rt, mh->last_op, 1, "{\"status\":200}", 14) == 0) mh->reentered_accepted++;
    const char *reply = "null";
    if (strcmp(method, "httpGet") == 0)
        reply = "\"<div class=\\\"item\\\"><a href=\\\"/m/1\\\">One</a></div><div class=\\\"item\\\"><a href=\\\"/m/2\\\">Two</a></div>\"";
    *out = strdup(reply);
    *out_len = strlen(reply);
    return 0;
}

static void deliver_index(mock_host *h, ssc_runtime *rt, int index) {
    if (h->delivered[index]) return;
    h->delivered[index] = 1;
    h->inflight--;
    char body[96];
    int len = snprintf(body, sizeof body, "{\"status\":200,\"body\":\"op-%u\"}", h->started[index]);
    if (ssc_deliver_async(rt, h->started[index], 1, body, (size_t)len) == 0) h->reentered_accepted++;
}

static int async_start(void *o, uint32_t op, const char *req, size_t len) {
    (void)req; (void)len;
    mock_host *h = o;
    int index = h->started_count++;
    h->started[index] = op;
    h->inflight++;
    if (h->inflight > h->max_inflight_seen) h->max_inflight_seen = h->inflight;
    h->last_op = op;
    if (h->start_mode == 1 || h->start_mode == 3) deliver_index(h, h->reenter_rt, index);
    if (h->start_mode == 2 && index > 0) deliver_index(h, h->reenter_rt, index - 1);
    return h->start_mode == 3 ? -1 : 0;
}

static void async_cancel(void *o, uint32_t op) {
    mock_host *h = o;
    h->cancelled++;
    h->inflight--;
    if (h->reenter_rt && ssc_deliver_async(h->reenter_rt, op, 1, "{\"status\":200}", 14) == 0) h->reentered_accepted++;
}

/* Simulated network: deliver every started op in order. */
static int async_wait(void *o, ssc_runtime *rt, uint64_t deadline) {
    (void)deadline;
    mock_host *h = o;
    int n = 0;
    while (h->delivered_upto < h->started_count) {
        int index = h->delivered_upto++;
        if (h->delivered[index]) continue;
        h->delivered[index] = 1;
        uint32_t op = h->started[index];
        char body[96];
        int len = snprintf(body, sizeof body, "{\"status\":200,\"body\":\"op-%u\"}", op);
        memcpy(body + len, "GARBAGE", 8); /* transports hand over frames that are not NUL-terminated */
        h->inflight--;
        if (ssc_deliver_async(rt, op, h->fail_deliveries ? 0 : 1, body, (size_t)len) == 0) n++;
    }
    return n;
}

static int no_wait(void *o, ssc_runtime *rt, uint64_t deadline) { (void)o; (void)rt; (void)deadline; return 0; }

static void log_fn(void *o, const char *m, size_t l) { (void)m; (void)l; ((mock_host *)o)->logs++; }

static char *bootstrap;
static size_t bootstrap_len;

#if defined(__has_feature)
#if __has_feature(address_sanitizer)
#define SSC_TEST_STACK (4u << 20) /* ASan frames are several times larger than release frames */
#endif
#endif
#ifndef SSC_TEST_STACK
#define SSC_TEST_STACK (1u << 20) /* ADR-0002 §7 */
#endif

static ssc_limits default_limits(void) {
    ssc_limits l = {
        .heap_limit_bytes = 16u << 20, .stack_limit_bytes = SSC_TEST_STACK,
        .init_timeout_ms = 2000, .invoke_timeout_ms = 300, .max_bridge_calls = 4096,
        .max_async_inflight = 6, .max_async_queue = 64, .max_result_bytes = 1u << 20,
        .dom_max_nodes = 100000, .dom_max_depth = 512,
    };
    return l;
}

/* Every init/invoke gets a fresh generation, like the frame driver's call ids. */
static uint64_t next_gen = 1;
static _Atomic uint64_t running_gen;

static ssc_runtime *make(mock_host *h, ssc_limits l, const char *script, int (*wait)(void *, ssc_runtime *, uint64_t)) {
    memset(h, 0, sizeof *h);
    ssc_host host = { h, now_ms, bridge_call, async_start, async_cancel, wait, log_fn };
    ssc_runtime *rt = ssc_new(&l, &host);
    ssc_status st = ssc_init(rt, next_gen++, bootstrap, bootstrap_len, script, strlen(script));
    CHECK(st == SSC_OK, "init failed: %s", ssc_status_name(st));
    return rt;
}

static ssc_status run(ssc_runtime *rt, const char *method, char **out) {
    size_t len;
    *out = NULL;
    uint64_t gen = next_gen++;
    atomic_store(&running_gen, gen);
    return ssc_invoke(rt, gen, method, strlen(method), "[]", 2, out, &len);
}

static void expect(ssc_runtime *rt, const char *method, ssc_status want, const char *label) {
    char *out;
    uint64_t t0 = now_ms(NULL);
    ssc_status st = run(rt, method, &out);
    printf("  %-34s -> %-16s (%llu ms)\n", label, ssc_status_name(st), (unsigned long long)(now_ms(NULL) - t0));
    CHECK(st == want, "%s: want %s got %s", label, ssc_status_name(want), ssc_status_name(st));
    free(out);
}

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
    " logs:function(){for(var i=0;i<5000;i++)console.log('line '+i);return 'logged';},"
    " toJsonJob:function(){return {toJSON:function(){Promise.resolve().then(function(){bridge.net.fetch('https://x.test/late');bridge.getPreference('late');});return 1;}};},"
    " toJsonDirect:function(){return {toJSON:function(){bridge.getPreference('x');return 2;}};},"
    " leftoverJob:function(){Promise.resolve().then(function(){bridge.getPreference('late');bridge.net.fetch('https://x.test/late');});return 'r';},"
    " jobBomb:function(){function f(){Promise.resolve().then(f,f);}f();return 1;},"
    " toJsonBomb:function(){return {toJSON:function(){function f(){Promise.resolve().then(f,f);}f();return 3;}};},"
    " wide:function(n){return '\u4e2d'.repeat(n);},"
    " reenter:function(){for(var i=0;i<10;i++)bridge.net.fetch('https://x.test/'+i).then(function(){bridge.getPreference('late');});return 'r';},"
    " thenReenter:async function(){Object.defineProperty(Object.prototype,'then',{configurable:true,get:function(){bridge.getPreference('reenter');return undefined;}});"
    "   try{var r=await bridge.net.fetch('https://x.test/one');return r.text();}finally{delete Object.prototype.then;}},"
    " errorSetter:async function(){var d={configurable:true,set:function(v){bridge.getPreference('reenter');}};"
    "   Object.defineProperty(Error.prototype,'code',d);Object.defineProperty(Error.prototype,'message',d);"
    "   try{await bridge.net.fetch('https://x.test/fail');return 'resolved';}catch(e){return e.code;}"
    "   finally{delete Error.prototype.code;delete Error.prototype.message;}},"
    " stringifyOverride:async function(){var orig=JSON.stringify;JSON.stringify=function(){JSON.stringify=orig;"
    "   return {toString:function(){bridge.net.fetch('https://x.test/inner');return '{}';}};};"
    "   try{await bridge.net.fetch('https://x.test/outer');return 'resolved';}catch(e){return e.code;}finally{JSON.stringify=orig;}},"
    " forgeLimit:function(){throw new Error('__ssc_bridge_limit');},"
    " forgeGetter:function(){var e=new Error('x');Object.defineProperty(e,'message',{get:function(){bridge.getPreference('getter');return 'out of memory';}});throw e;},"
    " poison:function(){return {toJSON:function(){for(var i=0;i<150000;i++)Promise.resolve().then();return 4;}};},"
    " big:function(){return 'y'.repeat(2<<20);},"
    " globals:function(){return [typeof std,typeof os,typeof require,typeof process,typeof __shinsouAsync,typeof fetch,typeof XMLHttpRequest].join(',');},"
    " fetchMany:async function(){var r=await Promise.all([1,2,3,4,5,6,7,8,9,10].map(function(i){return bridge.net.fetch('https://x.test/'+i);}));return r.map(function(x){return x.text();});},"
    " queueFull:async function(){var ps=[];for(var i=0;i<71;i++)ps.push(bridge.net.fetch('https://x.test/'+i).then(function(){return 'ok';},function(e){return e.code;}));var r=await Promise.all(ps);return r.filter(function(x){return x==='ASYNC_QUEUE_FULL';}).length;},"
    " leak:function(){bridge.net.fetch('https://x.test/late');return 'returned';},"
    " stalled:function(){return new Promise(function(){});},"
    " mixed:async function(){var a=bridge.net.fetch('https://x.test/a');var s=bridge.httpGet('https://x.test/');var r=await a;return [r.text(),s.length>0];},"
    "};"
    "Object.defineProperty(source,'getterThrows',{get:function(){throw new Error('boom');}});"
    "Object.defineProperty(source,'getterLoops',{get:function(){for(;;){}}});"
    "Object.defineProperty(source,'getterFloods',{get:function(){for(;;){try{bridge.getPreference('x');}catch(e){}}}});"
    "Object.defineProperty(source,'getterOk',{get:function(){return function(){return 'via getter';};}});";

static void *cancel_later(void *arg) { usleep(50 * 1000); ssc_cancel(arg, atomic_load(&running_gen)); return NULL; }

int main(int argc, char **argv) {
    if (argc < 2) { fprintf(stderr, "usage: core_test bootstrap.js\n"); return 2; }
    FILE *f = fopen(argv[1], "rb");
    if (!f) { perror("bootstrap"); return 2; }
    fseek(f, 0, SEEK_END); bootstrap_len = (size_t)ftell(f); fseek(f, 0, SEEK_SET);
    bootstrap = malloc(bootstrap_len); fread(bootstrap, 1, bootstrap_len, f); fclose(f);

    mock_host h;
    ssc_limits l = default_limits();
    ssc_runtime *rt = make(&h, l, SCRIPT, async_wait);

    printf("[functional]\n");
    char *out;
    ssc_status parse_status = run(rt, "parse", &out);
    CHECK(parse_status == SSC_OK && out && strstr(out, "\"title\":\"Two\"") && strstr(out, "/m/1"), "parse %s result: %s", ssc_status_name(parse_status), out ? out : "(null)");
    printf("  parse -> %s\n", out ? out : "(null)"); free(out);
    CHECK(run(rt, "globals", &out) == SSC_OK && out && strcmp(out, "\"undefined,undefined,undefined,undefined,undefined,undefined,undefined\"") == 0, "globals: %s", out ? out : "(null)");
    printf("  globals -> %s\n", out ? out : "(null)"); free(out);

    printf("[limits]\n");
    expect(rt, "loop", SSC_ERR_LIMIT_TIME, "infinite loop");
    expect(rt, "swallow", SSC_ERR_LIMIT_TIME, "try/catch swallowing interrupt");
    expect(rt, "regex", SSC_ERR_LIMIT_TIME, "catastrophic backtracking regex");
    expect(rt, "recurse", SSC_ERR_LIMIT_STACK, "unbounded recursion");
    expect(rt, "memory", SSC_ERR_LIMIT_MEMORY, "memory bomb");
    expect(rt, "memoryCaught", SSC_ERR_LIMIT_TIME, "OOM caught then spin");
    expect(rt, "bridgeFlood", SSC_ERR_LIMIT_BRIDGE, "bridge flood with catch");
    expect(rt, "big", SSC_ERR_LIMIT_RESULT, "oversized result");
    expect(rt, "missing", SSC_ERR_NO_SUCH_HOOK, "missing hook");
    expect(rt, "getterThrows", SSC_ERR_SCRIPT, "hook getter throws");
    expect(rt, "getterLoops", SSC_ERR_LIMIT_TIME, "hook getter times out");
    expect(rt, "getterFloods", SSC_ERR_LIMIT_BRIDGE, "hook getter exhausts bridge quota");
    expect(rt, "getterOk", SSC_OK, "hook returned by a getter");
    expect(rt, "ok", SSC_OK, "runtime reusable after limits");
    printf("  heap after limit tests: %zu bytes\n", ssc_memory_used(rt));

    pthread_t t;
    pthread_create(&t, NULL, cancel_later, rt);
    expect(rt, "loop", SSC_ERR_CANCELLED, "cancel from another thread");
    pthread_join(t, NULL);
    expect(rt, "ok", SSC_OK, "runtime reusable after cancel");
    memset(&h, 0, sizeof h);
    CHECK(run(rt, "logs", &out) == SSC_OK && h.logs == 5000 && ssc_bridge_calls(rt) == 0,
          "log must be local and free of bridge quota: logs=%d bridge=%u", h.logs, ssc_bridge_calls(rt));
    printf("  5000 console.log -> host.log=%d bridge_calls=%u\n", h.logs, ssc_bridge_calls(rt));
    free(out);

    printf("[invocation isolation]\n");
    memset(&h, 0, sizeof h);
    CHECK(run(rt, "toJsonJob", &out) == SSC_OK && out && strcmp(out, "1") == 0, "toJsonJob: %s", out ? out : "(null)");
    free(out);
    expect(rt, "ok", SSC_OK, "next invocation after toJSON job");
    CHECK(h.started_count == 0 && h.host_bridge_calls == 0,
          "toJSON job reached the host: async=%d bridge=%d", h.started_count, h.host_bridge_calls);
    printf("  toJSON-queued job -> host async=%d bridge=%d\n", h.started_count, h.host_bridge_calls);

    memset(&h, 0, sizeof h);
    expect(rt, "toJsonDirect", SSC_ERR_SCRIPT, "toJSON calling the host");
    CHECK(h.host_bridge_calls == 0, "toJSON reached the host: bridge=%d", h.host_bridge_calls);

    /* Microtasks queued by a hook still run inside that invocation (and count against it); the
     * op they start is cancelled when it ends. Nothing reaches the next invocation. */
    memset(&h, 0, sizeof h);
    CHECK(run(rt, "leftoverJob", &out) == SSC_OK, "leftoverJob");
    free(out);
    CHECK(h.cancelled == 1, "op started by a leftover microtask must be cancelled, cancelled=%d", h.cancelled);
    memset(&h, 0, sizeof h);
    expect(rt, "ok", SSC_OK, "next invocation after leftover job");
    CHECK(h.started_count == 0 && h.host_bridge_calls == 0,
          "leftover job reached the next invocation: async=%d bridge=%d", h.started_count, h.host_bridge_calls);
    printf("  leftover microtask -> next invocation host async=%d bridge=%d\n", h.started_count, h.host_bridge_calls);

    /* A host that delivers from inside async_cancel must not resurrect the cancelled ops. */
    memset(&h, 0, sizeof h);
    h.reenter_rt = rt;
    CHECK(run(rt, "reenter", &out) == SSC_OK, "reenter");
    free(out);
    int queued_started = h.started_count;
    h.reenter_rt = NULL;
    int bridge_before = h.host_bridge_calls;
    expect(rt, "ok", SSC_OK, "next invocation after re-entrant cancel");
    CHECK(h.reentered_accepted == 0, "re-entrant delivery accepted %d times", h.reentered_accepted);
    CHECK(queued_started == 6 && h.started_count == 6, "queued ops started after close: %d -> %d", queued_started, h.started_count);
    CHECK(h.host_bridge_calls == bridge_before, "re-entrant delivery ran JS later: bridge %d -> %d", bridge_before, h.host_bridge_calls);
    printf("  re-entrant deliver from async_cancel -> accepted=%d started=%d\n", h.reentered_accepted, h.started_count);

    printf("[re-entrant host]\n");
    {
        /* Fresh runtime: the script patches Object.prototype. */
        mock_host rh;
        ssc_runtime *r2 = make(&rh, l, SCRIPT, async_wait);
        rh.reenter_rt = r2;
        char *o2 = NULL;
        ssc_status st = run(r2, "thenReenter", &o2);
        printf("  then getter re-delivers same op   -> %s %s, accepted twice=%d\n", ssc_status_name(st), o2 ? o2 : "", rh.reentered_accepted);
        CHECK(st == SSC_OK && o2 && strcmp(o2, "\"op-1\"") == 0, "thenReenter: %s %s", ssc_status_name(st), o2 ? o2 : "");
        CHECK(rh.reentered_accepted == 0, "duplicate delivery accepted %d times", rh.reentered_accepted);
        free(o2);
        rh.reenter_rt = NULL;
        expect(r2, "ok", SSC_OK, "runtime after then-getter re-entry");
        ssc_free(r2);

        static const char *modes[] = { "", "deliver self", "deliver older op", "deliver self then fail" };
        for (int mode = 1; mode <= 3; mode++) {
            r2 = make(&rh, l, SCRIPT, async_wait);
            rh.reenter_rt = r2;
            rh.start_mode = mode;
            o2 = NULL;
            st = run(r2, "fetchMany", &o2);
            printf("  async_start %-22s -> %s, max in flight %d, started %d\n", modes[mode], ssc_status_name(st), rh.max_inflight_seen, rh.started_count);
            CHECK(rh.max_inflight_seen <= 6, "%s: %d in flight", modes[mode], rh.max_inflight_seen);
            CHECK(rh.started_count == 10, "%s: started %d", modes[mode], rh.started_count);
            if (mode == 3) CHECK(st == SSC_OK || st == SSC_ERR_SCRIPT, "%s: %s", modes[mode], ssc_status_name(st));
            else CHECK(st == SSC_OK && o2 && strstr(o2, "op-10"), "%s: %s %s", modes[mode], ssc_status_name(st), o2 ? o2 : "");
            free(o2);
            rh.reenter_rt = NULL;
            rh.start_mode = 0;
            expect(r2, "ok", SSC_OK, "runtime after async_start re-entry");
            ssc_free(r2);
        }
    }

    {
        mock_host rh;
        ssc_runtime *r3 = make(&rh, l, SCRIPT, async_wait);
        rh.reenter_rt = r3;
        rh.fail_deliveries = 1;
        char *o3 = NULL;
        ssc_status st = run(r3, "errorSetter", &o3);
        printf("  Error.prototype setter on failure -> %s %s, setter host calls=%d accepted=%d\n", ssc_status_name(st), o3 ? o3 : "", rh.host_bridge_calls, rh.reentered_accepted);
        CHECK(st == SSC_OK && o3 && strcmp(o3, "\"NETWORK_FAILED\"") == 0, "errorSetter: %s %s", ssc_status_name(st), o3 ? o3 : "");
        CHECK(rh.host_bridge_calls == 0 && rh.reentered_accepted == 0, "setter ran: bridge=%d accepted=%d", rh.host_bridge_calls, rh.reentered_accepted);
        free(o3);
        ssc_free(r3);

        r3 = make(&rh, l, SCRIPT, async_wait);
        o3 = NULL;
        st = run(r3, "stringifyOverride", &o3);
        printf("  non-string request with toString  -> %s %s, started=%d\n", ssc_status_name(st), o3 ? o3 : "", rh.started_count);
        CHECK(st == SSC_OK && o3 && strcmp(o3, "\"BAD_ARGUMENT\"") == 0, "stringifyOverride: %s %s", ssc_status_name(st), o3 ? o3 : "");
        CHECK(rh.started_count == 0, "toString re-entered fetch: started %d", rh.started_count);
        free(o3);
        rh.host_bridge_calls = 0;
        expect(r3, "forgeLimit", SSC_ERR_SCRIPT, "forged bridge-limit error");
        expect(r3, "forgeGetter", SSC_ERR_SCRIPT, "forged message getter");
        CHECK(rh.host_bridge_calls == 0, "classification ran a script getter: bridge=%d", rh.host_bridge_calls);
        ssc_free(r3);
    }

    printf("[async]\n");
    memset(&h, 0, sizeof h);
    CHECK(run(rt, "fetchMany", &out) == SSC_OK && out && strstr(out, "op-"), "fetchMany: %s", out ? out : "(null)");
    printf("  fetchMany -> %s (max in flight %d)\n", out ? out : "(null)", h.max_inflight_seen); free(out);
    CHECK(h.max_inflight_seen <= 6, "max in flight %d > 6", h.max_inflight_seen);
    CHECK(h.started_count == 10, "started %d", h.started_count);

    memset(&h, 0, sizeof h);
    CHECK(run(rt, "queueFull", &out) == SSC_OK && out && strcmp(out, "1") == 0, "queueFull rejected count: %s", out ? out : "(null)");
    printf("  queueFull rejected -> %s\n", out ? out : "(null)"); free(out);

    memset(&h, 0, sizeof h);
    CHECK(run(rt, "leak", &out) == SSC_OK, "leak");
    free(out);
    CHECK(h.cancelled == 1, "pending op must be cancelled at invocation end, cancelled=%d", h.cancelled);
    CHECK(ssc_deliver_async(rt, h.last_op, 1, "{\"status\":200}", 14) == -1, "late result must be dropped");
    expect(rt, "ok", SSC_OK, "next invocation after late result");

    memset(&h, 0, sizeof h);
    CHECK(run(rt, "mixed", &out) == SSC_OK && out && strstr(out, "true"), "mixed: %s", out ? out : "(null)");
    printf("  mixed sync+async -> %s\n", out ? out : "(null)"); free(out);
    ssc_free(rt);

    rt = make(&h, l, SCRIPT, no_wait);
    expect(rt, "stalled", SSC_ERR_PROMISE_STALLED, "never-settling promise");
    ssc_free(rt);

    /* A self re-queueing job chain must neither hang nor survive into the next invocation. */
    rt = make(&h, l, SCRIPT, no_wait);
    {
        uint64_t t0 = now_ms(NULL);
        ssc_status st = run(rt, "jobBomb", &out);
        free(out);
        ssc_status next = run(rt, "ok", &out);
        free(out);
        printf("  self re-queueing job chain       -> %s, next -> %s (%llu ms)\n", ssc_status_name(st),
               ssc_status_name(next), (unsigned long long)(now_ms(NULL) - t0));
        /* Started inside the invocation, the chain runs until the deadline; then it is discarded. */
        CHECK(st == SSC_ERR_LIMIT_TIME && next == SSC_OK, "job bomb: %s then %s", ssc_status_name(st), ssc_status_name(next));
    }
    ssc_free(rt);
    rt = make(&h, l, SCRIPT, no_wait);
    {
        /* Started during serialization, the chain only exists at close time: it must be cut off
         * by the interrupt or poison the runtime, never hang or survive. */
        uint64_t t0 = now_ms(NULL);
        ssc_status st = run(rt, "toJsonBomb", &out);
        free(out);
        ssc_status next = run(rt, "ok", &out);
        free(out);
        printf("  job chain started in toJSON      -> %s, next -> %s (%llu ms)\n", ssc_status_name(st),
               ssc_status_name(next), (unsigned long long)(now_ms(NULL) - t0));
        CHECK((st == SSC_OK && next == SSC_OK) || (st == SSC_ERR_INTERNAL && next == SSC_ERR_INTERNAL),
              "toJSON job bomb: %s then %s", ssc_status_name(st), ssc_status_name(next));
    }
    ssc_free(rt);

    /* Native-only reaction jobs never reach the interrupt poll: the discard bound must poison the
     * runtime, which then refuses work and can still be freed safely. */
    {
        ssc_limits roomy = l;
        roomy.heap_limit_bytes = 256u << 20;
        roomy.invoke_timeout_ms = 10000; /* building 150k promises must finish before close (ASan is slow) */
        rt = make(&h, roomy, SCRIPT, no_wait);
        uint64_t t0 = now_ms(NULL);
        ssc_status st = run(rt, "poison", &out);
        free(out);
        ssc_status next = run(rt, "ok", &out);
        free(out);
        printf("  undrainable jobs from toJSON      -> %s, next -> %s (%llu ms)\n", ssc_status_name(st),
               ssc_status_name(next), (unsigned long long)(now_ms(NULL) - t0));
        CHECK(st == SSC_ERR_INTERNAL && next == SSC_ERR_INTERNAL, "poison: %s then %s", ssc_status_name(st), ssc_status_name(next));
        ssc_free(rt);
    }

    /* Result conversion near the heap limit: every outcome must be a clean status (ASan/UBSan). */
    {
        ssc_limits tight = l;
        tight.heap_limit_bytes = 4u << 20;
        tight.max_result_bytes = 64u << 20;
        int ok = 0, mem = 0, other = 0;
        for (int n = 200000; n <= 1400000; n += 2500) {
            rt = make(&h, tight, SCRIPT, no_wait);
            char args[32];
            snprintf(args, sizeof args, "[%d]", n);
            size_t len;
            out = NULL;
            ssc_status st = ssc_invoke(rt, next_gen++, "wide", 4, args, strlen(args), &out, &len);
            if (st == SSC_OK) ok++; else if (st == SSC_ERR_LIMIT_MEMORY) mem++; else other++;
            free(out);
            ssc_free(rt);
        }
        printf("  non-ASCII result near heap limit -> OK=%d LIMIT_MEMORY=%d other=%d\n", ok, mem, other);
        CHECK(other == 0 && ok > 0 && mem > 0, "result conversion sweep: ok=%d mem=%d other=%d", ok, mem, other);
    }

    printf("[generations and entry points]\n");
    {
        /* A cancel that arrives before its invocation starts still applies to it... */
        rt = make(&h, l, SCRIPT, async_wait);
        uint64_t gen = next_gen++;
        ssc_cancel(rt, gen);
        size_t len;
        out = NULL;
        ssc_status st = ssc_invoke(rt, gen, "loop", 4, "[]", 2, &out, &len);
        CHECK(st == SSC_ERR_CANCELLED, "early cancel -> %s", ssc_status_name(st));
        printf("  cancel before start            -> %s\n", ssc_status_name(st));
        /* ...and a late cancel for that generation cannot reach the next invocation. */
        ssc_cancel(rt, gen);
        st = run(rt, "ok", &out);
        CHECK(st == SSC_OK, "after a stale cancel -> %s", ssc_status_name(st));
        printf("  stale cancel, next invocation  -> %s\n", ssc_status_name(st));
        free(out);
        /* Method and arguments are (pointer, length): no NUL terminator needed or honoured. */
        static const char m[] = "okXXXX";
        static const char a[] = "[]garbage";
        out = NULL;
        st = ssc_invoke(rt, next_gen++, m, 2, a, 2, &out, &len);
        CHECK(st == SSC_OK && out && strcmp(out, "\"ok\"") == 0, "length-delimited invoke -> %s %s", ssc_status_name(st), out ? out : "");
        free(out);
        out = NULL;
        st = ssc_invoke(rt, next_gen++, "o\0k", 3, "[]", 2, &out, &len);
        CHECK(st == SSC_ERR_BAD_ARGUMENT, "embedded NUL in method -> %s", ssc_status_name(st));
        CHECK(ssc_invoke(rt, 0, "ok", 2, "[]", 2, &out, &len) == SSC_ERR_BAD_ARGUMENT, "generation 0 must be refused");
        ssc_free(rt);

        /* Valid JSON that is not an array is refused without leaking it (Codex review). */
        rt = make(&h, l, SCRIPT, no_wait);
        size_t before = ssc_memory_used(rt);
        for (int i = 0; i < 20000; i++) {
            out = NULL;
            st = ssc_invoke(rt, next_gen++, "ok", 2, "{\"k\":\"vvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvv\"}", 38, &out, &len);
            if (st != SSC_ERR_BAD_ARGUMENT) break;
        }
        size_t after = ssc_memory_used(rt);
        CHECK(st == SSC_ERR_BAD_ARGUMENT && after < before + 64 * 1024, "non-array args: %s, heap %zu -> %zu", ssc_status_name(st), before, after);
        printf("  20000 non-array args           -> heap %zu -> %zu bytes\n", before, after);
        ssc_free(rt);

        /* Queued net.fetch requests are bounded in bytes, not only in count (Codex review). */
        static const char big_queue[] =
            "var source={q:async function(){var big='x'.repeat(3*1024*1024);var r=[];"
            "for(var i=0;i<20;i++){r.push(bridge.net.fetch('https://x.test/'+i,{method:'POST',body:big}).then(function(){return 'ok';},function(e){return e.message;}));}"
            "var out=await Promise.all(r);return out.filter(function(m){return m==='ASYNC_QUEUE_FULL';}).length;},"
            " huge:async function(){try{await bridge.net.fetch('https://x.test/',{method:'POST',body:'y'.repeat(5*1024*1024)});return 'sent';}catch(e){return e.message;}}};";
        ssc_limits big = l;
        big.heap_limit_bytes = 64u << 20;
        big.max_async_inflight = 1;
        big.invoke_timeout_ms = 5000; /* building 3 MiB strings is slow under ASan */
        rt = make(&h, big, big_queue, async_wait);
        out = NULL;
        st = run(rt, "q", &out);
        printf("  20 x 3 MiB queued requests      -> %s, rejected ASYNC_QUEUE_FULL: %s\n", ssc_status_name(st), out ? out : "");
        CHECK(st == SSC_OK && out && atoi(out) >= 16, "queue byte budget: %s %s", ssc_status_name(st), out ? out : "");
        free(out);
        out = NULL;
        st = run(rt, "huge", &out);
        CHECK(st == SSC_OK && out && strcmp(out, "\"LIMIT_REQUEST\"") == 0, "5 MiB request: %s %s", ssc_status_name(st), out ? out : "");
        printf("  one 5 MiB request              -> %s\n", out ? out : "");
        free(out);
        ssc_free(rt);

        /* Reserved entry points are globals, not hooks; ordinary names never reach globals. */
        static const char reserved_script[] =
            "var source={ok:function(){return 'hook';}};"
            "globalThis.__shinsouEcho=function(x){return 'echo:'+x;};"
            "globalThis.notAHook=function(){return 'global';};";
        rt = make(&h, l, reserved_script, no_wait);
        out = NULL;
        st = ssc_invoke(rt, next_gen++, "__shinsouEcho", 13, "[7]", 3, &out, &len);
        CHECK(st == SSC_OK && out && strcmp(out, "\"echo:7\"") == 0, "reserved -> %s %s", ssc_status_name(st), out ? out : "");
        printf("  reserved entry point           -> %s %s\n", ssc_status_name(st), out ? out : "");
        free(out);
        out = NULL;
        st = ssc_invoke(rt, next_gen++, "notAHook", 8, "[]", 2, &out, &len);
        CHECK(st == SSC_ERR_NO_SUCH_HOOK, "global non-reserved -> %s", ssc_status_name(st));
        ssc_free(rt);

        /* Source-failure markers: only an exact marker name, thrown or rejected, never via a getter. */
        static const char marker_script[] =
            "var source={"
            " quota:function(){throw new Error('SHINSOU_SOURCE_QUOTA_EXCEEDED');},"
            " challenge:async function(){await null;throw new Error('SHINSOU_SOURCE_HTTP_CHALLENGE');},"
            " str:function(){throw 'SHINSOU_SOURCE_HTTP_BLOCKED';},"
            " suffix:function(){throw new Error('SHINSOU_SOURCE_QUOTA_EXCEEDED: details');},"
            " other:function(){throw new Error('SHINSOU_SOURCE_SOMETHING_ELSE');},"
            " getter:function(){var e=new Error('x');Object.defineProperty(e,'message',{get:function(){globalThis.ran=1;return 'SHINSOU_SOURCE_HTTP_FORBIDDEN';}});throw e;},"
            " inherited:function(){var e=Object.create(new Error('SHINSOU_SOURCE_HTTP_FORBIDDEN'));throw e;},"
            " ran:function(){return !!globalThis.ran;},"
            " caught:function(){try{throw new Error('SHINSOU_SOURCE_QUOTA_EXCEEDED');}catch(e){} throw new Error('plain');},"
            " ok:function(){return 1;}};";
        static const struct { const char *method; ssc_status st; ssc_source_failure marker; } cases[] = {
            { "quota", SSC_ERR_SCRIPT, SSC_SOURCE_FAILURE_QUOTA_EXCEEDED },
            { "ok", SSC_OK, SSC_SOURCE_FAILURE_NONE }, /* reset by the next invocation */
            { "challenge", SSC_ERR_SCRIPT, SSC_SOURCE_FAILURE_HTTP_CHALLENGE },
            { "str", SSC_ERR_SCRIPT, SSC_SOURCE_FAILURE_HTTP_BLOCKED },
            { "suffix", SSC_ERR_SCRIPT, SSC_SOURCE_FAILURE_NONE },
            { "other", SSC_ERR_SCRIPT, SSC_SOURCE_FAILURE_NONE },
            { "getter", SSC_ERR_SCRIPT, SSC_SOURCE_FAILURE_NONE },
            { "inherited", SSC_ERR_SCRIPT, SSC_SOURCE_FAILURE_NONE },
            { "caught", SSC_ERR_SCRIPT, SSC_SOURCE_FAILURE_NONE },
        };
        rt = make(&h, l, marker_script, async_wait);
        for (size_t i = 0; i < sizeof cases / sizeof cases[0]; i++) {
            out = NULL;
            st = run(rt, cases[i].method, &out);
            ssc_source_failure m = ssc_last_source_failure(rt);
            CHECK(st == cases[i].st && m == cases[i].marker, "marker %s -> %s marker %d", cases[i].method, ssc_status_name(st), (int)m);
            free(out);
        }
        out = NULL;
        st = run(rt, "ran", &out);
        CHECK(st == SSC_OK && out && strcmp(out, "false") == 0, "the message getter must not run: %s", out ? out : "");
        free(out);
        printf("  source-failure markers         -> %zu cases\n", sizeof cases / sizeof cases[0]);
        ssc_free(rt);
    }

    printf(failures ? "\n%d FAILURE(S)\n" : "\nALL PASSED\n", failures);
    free(bootstrap);
    return failures ? 1 : 0;
}
