/* Unit tests for sandbox_dom: Jsoup-visible behaviour and enforced limits. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "sandbox_dom.h"

static int failures;
#define CHECK(cond, ...) do { if (!(cond)) { failures++; printf("  FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

static sbx_dom *mk(void (*tweak)(sbx_dom_limits *)) {
    sbx_dom_limits l;
    sbx_dom_limits_default(&l);
    if (tweak) tweak(&l);
    sbx_dom *c = sbx_dom_new(&l);
    if (!c) { printf("sbx_dom_new failed\n"); exit(2); }
    return c;
}
static const char *S(const char *s, size_t n) { (void)n; return s; }
static int eq(const char *got, const char *want) { return strcmp(got, want) == 0; }

static void test_basic(void) {
    printf("basic\n");
    sbx_dom *c = mk(NULL);
    const char *html = "<div id=a class='x Y'><a href='/p?q=1'>One &amp; <b>two</b></a><a href='http://o.test/z'>Three</a></div>";
    int32_t d = sbx_dom_parse(c, html, strlen(html), "https://h.test/base/");
    CHECK(d > 0, "parse %d", d);
    int32_t *hs; size_t n;
    CHECK(sbx_dom_select(c, d, "div > a", &hs, &n) == 0 && n == 2, "select count %zu", n);
    size_t l;
    CHECK(eq(S(sbx_dom_text(c, hs[0], &l), l), "One & two"), "text");
    CHECK(eq(S(sbx_dom_own_text(c, hs[0], &l), l), "One &"), "ownText '%s'", sbx_dom_own_text(c, hs[0], &l));
    CHECK(eq(sbx_dom_abs_url(c, hs[0], "href", &l), "https://h.test/p?q=1"), "absUrl");
    CHECK(eq(sbx_dom_attr(c, hs[1], "abs:href", &l), "http://o.test/z"), "abs: prefix");
    CHECK(sbx_dom_has_attr(c, hs[0], "href") && !sbx_dom_has_attr(c, hs[0], "nope"), "hasAttr");
    CHECK(eq(sbx_dom_tag_name(c, hs[0], &l), "a") && l == 1, "tagName");
    int32_t p = sbx_dom_parent(c, hs[0]);
    CHECK(p > 0 && eq(sbx_dom_id(c, p, &l), "a") && eq(sbx_dom_class_name(c, p, &l), "x Y"), "parent/id/class");
    int32_t nx = sbx_dom_next_sibling(c, hs[0]);
    CHECK(nx > 0 && eq(sbx_dom_text(c, nx, &l), "Three"), "nextElementSibling");
    CHECK(sbx_dom_prev_sibling(c, hs[0]) == 0, "no previous sibling");
    /* select is relative to the element but includes itself, like jsoup */
    int32_t *hs2; size_t n2;
    CHECK(sbx_dom_select(c, p, "div", &hs2, &n2) == 0 && n2 == 1, "self match %zu", n2);
    sbx_dom_free_array(hs2);
    CHECK(sbx_dom_select(c, p, "> a", &hs2, &n2) == 0 && n2 == 2, "leading combinator %zu", n2);
    sbx_dom_free_array(hs2);
    CHECK(sbx_dom_select_first(c, d, "a:contains(three)") > 0, ":contains is case-insensitive");
    CHECK(sbx_dom_select_first(c, d, ".y") > 0 && sbx_dom_select_first(c, d, "#A") == 0, "class ci / id cs");
    CHECK(sbx_dom_select_first(c, d, "a[href*=P?Q]") > 0, "attribute values are case-insensitive");
    /* lexbor only checks the nearest ancestor for a bare :is()/:has() compound; the rewriter must avoid that */
    const char *h2 = "<p><span><a>x</a></span><a>y</a></p><div><p><a>z</a></p></div>";
    int32_t d2 = sbx_dom_parse(c, h2, strlen(h2), "");
    int32_t *hs3; size_t n3;
    CHECK(sbx_dom_select(c, d2, ":is(p) a", &hs3, &n3) == 0 && n3 == 3, ":is(p) a -> %zu", n3);
    sbx_dom_free_array(hs3);
    CHECK(sbx_dom_select(c, d2, "p:has(span) a", &hs3, &n3) == 0 && n3 == 2, "p:has(span) a -> %zu", n3);
    sbx_dom_free_array(hs3);
    CHECK(sbx_dom_select(c, d2, "li:eq(0), a:not(span a)", &hs3, &n3) == 0 && n3 == 2, "complex :not -> %zu", n3);
    sbx_dom_free_array(hs3);
    /* remove */
    sbx_dom_remove(c, hs[0]);
    CHECK(sbx_dom_select(c, d, "a", &hs2, &n2) == 0 && n2 == 1, "remove %zu", n2);
    sbx_dom_free_array(hs2);
    sbx_dom_free_array(hs);
    sbx_dom_free(c);
}

static void test_errors(void) {
    printf("errors\n");
    sbx_dom *c = mk(NULL);
    int32_t d = sbx_dom_parse(c, "<p>x</p>", 8, "");
    int32_t *hs; size_t n;
    CHECK(sbx_dom_select(c, d, "a[", &hs, &n) == SBX_ERR_SELECTOR, "syntax error");
    CHECK(sbx_dom_select(c, d, "p:matches(x)", &hs, &n) == SBX_ERR_UNSUPPORTED, "unsupported");
    CHECK(sbx_dom_select(c, 12345, "p", &hs, &n) == SBX_ERR_BAD_HANDLE, "bad handle");
    sbx_dom_release(c, d);
    CHECK(sbx_dom_select(c, d, "p", &hs, &n) == SBX_ERR_BAD_HANDLE, "released handle is stale");
    sbx_dom_stats st;
    sbx_dom_get_stats(c, &st);
    CHECK(st.live_handles == 0 && st.live_documents == 0, "document freed with its last handle (%u/%u)", st.live_handles, st.live_documents);
    sbx_dom_free(c);
}

static void small_nodes(sbx_dom_limits *l) { l->max_nodes = 1000; }
static void small_depth(sbx_dom_limits *l) { l->max_depth = 32; }
static void small_input(sbx_dom_limits *l) { l->max_input_bytes = 1024; }
static void small_handles(sbx_dom_limits *l) { l->max_handles = 16; }
static void small_sel(sbx_dom_limits *l) { l->max_selector_chars = 32; }
static void small_mem(sbx_dom_limits *l) { l->max_memory_bytes = 2u << 20; }

static char *repeat(const char *unit, size_t times, const char *pre, const char *post) {
    size_t u = strlen(unit), a = strlen(pre), b = strlen(post);
    char *s = malloc(a + u * times + b + 1);
    memcpy(s, pre, a);
    for (size_t i = 0; i < times; i++) memcpy(s + a + i * u, unit, u);
    memcpy(s + a + u * times, post, b + 1);
    return s;
}

static void test_limits(void) {
    printf("limits\n");
    char *big = repeat("<b>x</b>", 2000, "<div>", "</div>");
    sbx_dom *c = mk(small_nodes);
    CHECK(sbx_dom_parse(c, big, strlen(big), "") == SBX_ERR_LIMIT_NODES, "node limit");
    sbx_dom_stats st; sbx_dom_get_stats(c, &st);
    CHECK(st.live_documents == 0, "failed parse leaves no document");
    CHECK(sbx_dom_parse(c, "<p>ok</p>", 9, "") > 0, "still usable after a limit error");
    sbx_dom_free(c);
    free(big);

    char *deep = repeat("<div>", 200, "", "x");
    c = mk(small_depth);
    CHECK(sbx_dom_parse(c, deep, strlen(deep), "") == SBX_ERR_LIMIT_DEPTH, "depth limit");
    sbx_dom_free(c);
    free(deep);

    big = repeat("a", 4000, "<p>", "</p>");
    c = mk(small_input);
    CHECK(sbx_dom_parse(c, big, strlen(big), "") == SBX_ERR_LIMIT_INPUT, "input limit");
    sbx_dom_free(c);
    free(big);

    big = repeat("<i>x</i>", 100, "<div>", "</div>");
    c = mk(small_handles);
    int32_t d = sbx_dom_parse(c, big, strlen(big), "");
    int32_t *hs; size_t n;
    CHECK(d > 0, "parse");
    CHECK(sbx_dom_select(c, d, "i", &hs, &n) == SBX_ERR_LIMIT_HANDLES, "handle limit on select");
    sbx_dom_get_stats(c, &st);
    CHECK(st.live_handles == 1, "select rolled back its partial handles (%u)", st.live_handles);
    sbx_dom_release_all(c);
    sbx_dom_get_stats(c, &st);
    CHECK(st.live_handles == 0 && st.live_documents == 0, "releaseAll");
    sbx_dom_free(c);
    free(big);

    c = mk(small_sel);
    d = sbx_dom_parse(c, "<p>x</p>", 8, "");
    CHECK(sbx_dom_select_first(c, d, "a b c d e f g h i j k l m n o p q r s t u v w x y z a b c") == SBX_ERR_LIMIT_SELECTOR, "selector length limit");
    sbx_dom_free(c);

    /* memory budget: repeated formatting-element reconstruction is the classic amplifier */
    big = repeat("<b><i><u>x", 20000, "", "");
    c = mk(small_mem);
    int32_t r = sbx_dom_parse(c, big, strlen(big), "");
    CHECK(r < 0 && SBX_IS_LIMIT_ERROR(r), "memory/node limit on adversarial nesting (%d)", r);
    sbx_dom_get_stats(c, &st);
    CHECK(st.memory_peak_bytes <= (2u << 20), "memory peak %zu stays within budget", st.memory_peak_bytes);
    sbx_dom_free(c);
    free(big);
}

static void test_text_rules(void) {
    printf("text rules\n");
    struct { const char *html, *sel, *want; } t[] = {
        {"<p>a</p>b", "body", "a b"},
        {"<div>a</div><foo>x</foo>", "body", "ax"},
        {"<div>a</div><span>x</span>", "body", "a x"},
        {"a<br>b", "body", "a b"},
        {"<pre>  keep\n  me </pre>", "pre", "keep\n  me"},
        {"<p>x &nbsp;&nbsp; y</p>", "p", "x y"},
        {"<script>var x=1</script><p>p</p>", "body", "p"},
        {"<svg><title>T</title></svg>after", "body", "T after"},
        {"<ul><li>1<li>2</ul>", "ul", "1 2"},
    };
    sbx_dom *c = mk(NULL);
    for (size_t i = 0; i < sizeof t / sizeof *t; i++) {
        int32_t d = sbx_dom_parse(c, t[i].html, strlen(t[i].html), "");
        int32_t e = sbx_dom_select_first(c, d, t[i].sel);
        size_t l;
        const char *got = sbx_dom_text(c, e, &l);
        CHECK(eq(got, t[i].want), "text of %s: got '%s' want '%s'", t[i].html, got, t[i].want);
        sbx_dom_release_all(c);
    }
    sbx_dom_free(c);
}

static void test_poison(void) {
    printf("forged markers\n");
    sbx_dom *c = mk(NULL);
    const char *h = "<p sbx-m0=\"\" sbx-root>x</p><p>needle</p>";
    int32_t d = sbx_dom_parse(c, h, strlen(h), "");
    int32_t *hs; size_t n;
    CHECK(sbx_dom_select(c, d, "p:contains(needle)", &hs, &n) == 0 && n == 1, "page cannot forge :contains markers (%zu)", n);
    sbx_dom_free_array(hs);
    sbx_dom_free(c);
}

int main(void) {
    test_basic();
    test_errors();
    test_limits();
    test_text_rules();
    test_poison();
    printf(failures ? "FAILED: %d\n" : "ok\n", failures);
    return failures ? 1 : 0;
}
