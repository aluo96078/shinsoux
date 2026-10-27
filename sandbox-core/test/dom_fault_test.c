/*
 * Fault injection over the DOM module: every allocation is charged against the budget and can
 * fail, so for N = 1, 2, ... the Nth allocation fails and every operation must report an error
 * (or succeed) without crashing or leaking. Run under ASan.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "sbx_dom.h"

static const char HTML[] =
    "<html><head><base href='https://ex.test/b/'><title>T</title></head><body>"
    "<ul id=list><li class='item a' data-x=1><a href='/m/1'>One <b>1</b></a></li>"
    "<li class=item><a href='m/2' title='Two Two'>Two</a></li><li class=other>skip</li><li></li></ul>"
    "<p>  text  <span>in</span> p </p><div><img src='x.png' data-original='y.png'></div>"
    "<script>var s='<p>';</script></body></html>";

static const char *SELECTORS[] = {
    "li", "li.item > a", "ul li:nth-child(2)", "li:contains(Two)", "li:containsOwn(skip)", "a:has(b)",
    "li:eq(1)", "li:lt(2)", "li:gt(0)", "li:empty", "a[href^=/m]", "a[title~=Two]", "*:not(li)",
    ":root > body", "li:first-child, li:last-child", "div img[data-original]", "a:matchesOwn(x)",
};

static int run_ops(sbx_dom *c) {
    int32_t doc = sbx_dom_parse(c, HTML, sizeof HTML - 1, "https://ex.test/");
    if (doc < 0) return 0;
    size_t n;
    for (size_t i = 0; i < sizeof SELECTORS / sizeof SELECTORS[0]; i++) {
        int32_t *hs = NULL;
        size_t cnt = 0;
        if (sbx_dom_select(c, doc, SELECTORS[i], &hs, &cnt) == 0) {
            for (size_t k = 0; k < cnt; k++) {
                sbx_dom_text(c, hs[k], &n);
                sbx_dom_own_text(c, hs[k], &n);
                sbx_dom_html(c, hs[k], &n);
                sbx_dom_outer_html(c, hs[k], &n);
                sbx_dom_attr(c, hs[k], "href", &n);
                sbx_dom_attr(c, hs[k], "abs:href", &n);
                sbx_dom_abs_url(c, hs[k], "src", &n);
                sbx_dom_class_name(c, hs[k], &n);
                sbx_dom_id(c, hs[k], &n);
                sbx_dom_tag_name(c, hs[k], &n);
                sbx_dom_has_attr(c, hs[k], "data-x");
                sbx_dom_parent(c, hs[k]);
                sbx_dom_next_sibling(c, hs[k]);
                sbx_dom_prev_sibling(c, hs[k]);
                int32_t *kids = NULL;
                size_t kn = 0;
                if (sbx_dom_children(c, hs[k], &kids, &kn) == 0) sbx_dom_free_array(kids);
            }
            sbx_dom_free_array(hs);
        }
        sbx_dom_select_first(c, doc, SELECTORS[i]);
    }
    sbx_dom_text(c, doc, &n);
    sbx_dom_release_all(c);
    return 1;
}

int main(void) {
    sbx_dom_limits lim;
    sbx_dom_limits_default(&lim);
    /* Reference pass without faults: how many charged allocations one full pass makes. */
    sbx_dom *ref = sbx_dom_new(&lim);
    long before = sbx_dom_test_charged_allocs();
    run_ops(ref);
    long total = sbx_dom_test_charged_allocs() - before;
    sbx_dom_free(ref);
    printf("[dom fault injection]\n  one pass makes %ld charged allocations\n", total);
    for (long n = 1; n <= total; n++) {
        sbx_dom *c = sbx_dom_new(&lim);
        sbx_dom_test_fail_after(n);
        run_ops(c);
        sbx_dom_test_fail_after(0);
        sbx_dom_stats st;
        sbx_dom_get_stats(c, &st);
        if (st.memory_bytes > 1u << 20) { printf("FAIL: %zu bytes still charged after release_all at n=%ld\n", st.memory_bytes, n); return 1; }
        sbx_dom_free(c);
    }
    printf("  failed each of the %ld allocations in turn: no crash\n\nALL PASSED\n", total);
    return 0;
}
