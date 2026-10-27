/*
 * The lexbor DOM as scripts see it (Jsoup-compatible API over native handles, ADR-0002 §5.2):
 * semantics, identity, limits and lifetime.
 */
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "ssc_core.h"

static int failures;
#define CHECK(c, ...) do { if (!(c)) { failures++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

static uint64_t now_ms(void *o) {
    (void)o;
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000u + (uint64_t)ts.tv_nsec / 1000000u;
}
static int no_bridge(void *o, const char *m, const char *a, size_t l, char **out, size_t *ol) { (void)o; (void)m; (void)a; (void)l; (void)out; (void)ol; return -1; }
static int no_async(void *o, uint32_t id, const char *r, size_t l) { (void)o; (void)id; (void)r; (void)l; return -1; }
static void no_cancel(void *o, uint32_t id) { (void)o; (void)id; }
static int no_wait(void *o, ssc_runtime *rt, uint64_t d) { (void)o; (void)rt; (void)d; return 0; }
static void no_log(void *o, const char *m, size_t l) { (void)o; (void)m; (void)l; }

static char *bootstrap;
static size_t bootstrap_len;
static uint64_t gen = 1;

#if defined(__has_feature)
#if __has_feature(address_sanitizer)
#define TEST_STACK (4u << 20) /* ASan frames are several times larger */
#endif
#endif
#ifndef TEST_STACK
#define TEST_STACK (1u << 20)
#endif

static ssc_runtime *make(const char *script, uint32_t max_nodes) {
    ssc_limits l = {
        .heap_limit_bytes = 32u << 20, .stack_limit_bytes = TEST_STACK, .init_timeout_ms = 3000, .invoke_timeout_ms = 3000,
        .max_bridge_calls = 4096, .max_async_inflight = 6, .max_async_queue = 64, .max_result_bytes = 1u << 20,
        .dom_max_nodes = max_nodes, .dom_max_depth = 256,
    };
    ssc_host host = { NULL, now_ms, no_bridge, no_async, no_cancel, no_wait, no_log };
    ssc_runtime *rt = ssc_new(&l, &host);
    ssc_status st = ssc_init(rt, gen++, bootstrap, bootstrap_len, script, strlen(script));
    CHECK(st == SSC_OK, "init: %s", ssc_status_name(st));
    return rt;
}

static ssc_status call(ssc_runtime *rt, const char *method, char **out) {
    size_t len;
    *out = NULL;
    return ssc_invoke(rt, gen++, method, strlen(method), "[]", 2, out, &len);
}

static void expect_json(ssc_runtime *rt, const char *method, const char *want) {
    char *out;
    ssc_status st = call(rt, method, &out);
    CHECK(st == SSC_OK && out && strcmp(out, want) == 0, "%s -> %s %s\n     want %s", method, ssc_status_name(st), out ? out : "(null)", want);
    if (st == SSC_OK && out && strcmp(out, want) == 0) printf("  %-22s -> %s\n", method, out);
    free(out);
}

static void expect_status(ssc_runtime *rt, const char *method, ssc_status want) {
    char *out;
    ssc_status st = call(rt, method, &out);
    CHECK(st == want, "%s -> %s (want %s)", method, ssc_status_name(st), ssc_status_name(want));
    printf("  %-22s -> %s\n", method, ssc_status_name(st));
    free(out);
}

static const char SCRIPT[] =
    "var HTML='<html><head><base href=\"https://ex.test/b/\"></head><body>"
    "<ul id=list><li class=\"item a\" data-x=1><a href=\"/m/1\">第一 <b>One</b></a></li>"
    "<li class=item><a href=\"m/2\">Two</a></li><li class=other>skip</li></ul>"
    "<p>  keep   spaces  <span>in</span> text </p><script>var s=\"<p>x</p>\";</script></body></html>';"
    "var kept=null;"
    "var source={"
    " basics:function(){var d=Jsoup.parse(HTML,'https://ex.test/');var a=d.select('li.item a');"
    "   return [a.length,a[0].text(),a[0].ownText(),a[0].attr('href'),a[0].absUrl('href'),a[1].absUrl('href'),"
    "     a.get(1).attr('abs:href'),a.first().tagName(),d.selectFirst('li').className(),d.selectFirst('ul').id(),"
    "     d.selectFirst('li').hasAttr('data-x'),d.selectFirst('li').hasAttr('nope'),d.selectFirst('li').attr('nope')];},"
    " tree:function(){var d=Jsoup.parse(HTML);var ul=d.selectFirst('#list');var li=ul.children();"
    "   return [li.size(),li.get(0).nextElementSibling().className(),li.get(1).previousElementSibling().className(),"
    "     li.get(0).parent().id(),d.getElementById('list').tagName(),d.getElementsByClass('item').size(),d.getElementsByTag('a').size(),"
    "     li.get(2).nextElementSibling()===null];},"
    " identity:function(){var d=Jsoup.parse(HTML);var x=d.selectFirst('li.item');var y=d.select('li')[0];"
    "   var both=d.select('ul, #list');var uniq=d.select('li').select('a');return [x===y,both.length,uniq.length,x.parent()===d.selectFirst('ul')];},"
    " text:function(){var d=Jsoup.parse(HTML);return [d.selectFirst('p').text(),d.select('li').text(),d.select('li').eachText().length,"
    "   d.selectFirst('script').html(),d.select('a').eachAttr('href').join(',')];},"
    " remove:function(){var d=Jsoup.parse(HTML);d.selectFirst('li.other').remove();return d.select('li').length;},"
    " compat:function(){return bridge.parseHtml('<a href=\"/x\" class=c>T</a>','a.c');},"
    " badSelector:function(){var d=Jsoup.parse(HTML);try{d.select('li[');return 'no error';}catch(e){return e.message;}},"
    " unsupported:function(){var d=Jsoup.parse(HTML);try{d.select('li:matches(x)');return 'no error';}catch(e){return e.message;}},"
    " keep:function(){kept=Jsoup.parse(HTML).selectFirst('li');return kept.text();},"
    " useKept:function(){try{return kept.text();}catch(e){return e.message;}},"
    " floodNodes:function(){var s='';for(var i=0;i<5000;i++)s+='<i>x</i>';try{Jsoup.parse(s);return 'parsed';}catch(e){return 'caught '+e.message;}},"
    " floodInput:function(){try{Jsoup.parse('x'.repeat(5*1024*1024));return 'parsed';}catch(e){return 'caught';}},"
    " domInToJSON:function(){return {toJSON:function(){try{Jsoup.parse('<p>x</p>');return 'parsed';}catch(e){return 'refused';}}};},"
    " nonString:function(){var d=Jsoup.parse(HTML);var evil={toString:function(){globalThis.ran=true;return 'li';}};"
    "   try{d.select(evil);}catch(e){} return [typeof globalThis.ran, d.select(String('li')).length];},"
    " nativeMisuse:function(){var d=Jsoup.parse(HTML);var keys=Object.keys(d).length;"
    "   var evil={valueOf:function(){globalThis.ran2=true;return 1;}};var name='';"
    "   try{Object.getPrototypeOf(d).select.call({_h:evil,_e:0},'li');}catch(e){name=e.name;} return [name,typeof globalThis.ran2,keys];},"
    " forge:function(){var d=Jsoup.parse(HTML);var li=d.selectFirst('li');var out=[];"
    "   out.push(typeof li.constructor==='function'&&li.constructor!==Object?'ctor exposed':'no ctor');"
    "   var origGet=WeakMap.prototype.get;var leaked=null;WeakMap.prototype.get=function(k){var r=origGet.call(this,k);leaked=r;return r;};"
    "   li.text();WeakMap.prototype.get=origGet;out.push(leaked===null?'no leak':'leaked');"
    "   var p=Object.getPrototypeOf(li);try{p.text.call(Object.create(p));out.push('forged ok');}catch(e){out.push(e.name);}"
    "   return out;},"
    " detached:function(){var d=Jsoup.parse('<div id=a><p>hello</p><p>bye</p></div><div id=b>x</div>');var x=d.selectFirst('#a');x.remove();"
    "   return [x.select('p').length,x.select('p:contains(hello)').length,x.select('> p').length,x.select('p:eq(1)').text(),"
    "     x.outerHtml().indexOf('sbx-')<0,d.select('div').length];},"
    " longBase:function(){Jsoup.parse('<p>x</p>','https://ex.test/'+'a'.repeat(9000));return 'parsed';},"
    " tiny:function(){return Jsoup.parse('<p>x</p>').select('p').length;},"
    " many:function(){var d=Jsoup.parse(HTML);var n=0;for(var i=0;i<20000;i++)n+=d.select('li').length;return n;}"
    "};";

int main(int argc, char **argv) {
    if (argc < 2) { fprintf(stderr, "usage: dom_js_test bootstrap.js\n"); return 2; }
    FILE *f = fopen(argv[1], "rb");
    if (!f) { perror("bootstrap"); return 2; }
    fseek(f, 0, SEEK_END); bootstrap_len = (size_t)ftell(f); fseek(f, 0, SEEK_SET);
    bootstrap = malloc(bootstrap_len);
    if (fread(bootstrap, 1, bootstrap_len, f) != bootstrap_len) return 2;
    fclose(f);

    printf("[dom]\n");
    ssc_runtime *rt = make(SCRIPT, 1000);
    expect_json(rt, "basics", "[2,\"第一 One\",\"第一\",\"/m/1\",\"https://ex.test/m/1\",\"https://ex.test/b/m/2\",\"https://ex.test/b/m/2\",\"a\",\"item a\",\"list\",true,false,\"\"]");
    expect_json(rt, "tree", "[3,\"item\",\"item a\",\"list\",\"ul\",2,2,true]");
    expect_json(rt, "identity", "[true,1,2,true]");
    expect_json(rt, "text", "[\"keep spaces in text\",\"第一 One Two skip\",3,\"var s=\\\"<p>x</p>\\\";\",\"/m/1,m/2\"]");
    expect_json(rt, "remove", "2");
    expect_json(rt, "compat", "[{\"text\":\"T\",\"html\":\"T\",\"outerHtml\":\"<a href=\\\"/x\\\" class=\\\"c\\\">T</a>\",\"attr_href\":\"/x\",\"attr_src\":\"\",\"tagName\":\"a\"}]");
    expect_json(rt, "badSelector", "\"SELECTOR_SYNTAX\"");
    expect_json(rt, "unsupported", "\"UNSUPPORTED_SELECTOR\"");
    expect_json(rt, "keep", "\"第一 One\"");
    expect_json(rt, "useKept", "\"STALE_DOM_NODE\"");
    /* String() conversion happens in the JS wrapper, in the script's own realm (jsoup calls
     * toString too); the native functions themselves accept only strings and numbers. */
    expect_json(rt, "nonString", "[\"boolean\",3]");
    expect_json(rt, "detached", "[2,1,2,\"bye\",true,1]");
    expect_json(rt, "forge", "[\"no ctor\",\"no leak\",\"TypeError\"]");
    expect_json(rt, "nativeMisuse", "[\"TypeError\",\"undefined\",0]");
    expect_json(rt, "domInToJSON", "\"refused\"");
    expect_json(rt, "many", "60000");
    /* Limits are uncatchable and end the invocation; the runtime stays usable. */
    expect_status(rt, "floodNodes", SSC_ERR_LIMIT_MEMORY);
    expect_status(rt, "floodInput", SSC_ERR_LIMIT_MEMORY);
    expect_json(rt, "remove", "2");
    /* Base URIs are bounded (they are copied outside the heap): over 8 KiB is a limit violation. */
    expect_status(rt, "longBase", SSC_ERR_LIMIT_MEMORY);
    /* Handle generations cycle without ever producing a negative or revived handle. */
    {
        int bad = 0;
        for (int i = 0; i < 33000 && !bad; i++) {
            char *out;
            ssc_status st = call(rt, "tiny", &out);
            if (st != SSC_OK || !out || strcmp(out, "1") != 0) bad = i + 1;
            free(out);
        }
        CHECK(!bad, "generation cycling failed at invocation %d", bad);
        printf("  %-22s -> %s\n", "33000 invocations", bad ? "FAILED" : "all OK (generations cycled)");
    }
    ssc_free(rt);

    printf(failures ? "\n%d FAILURE(S)\n" : "\nALL PASSED\n", failures);
    free(bootstrap);
    return failures ? 1 : 0;
}
