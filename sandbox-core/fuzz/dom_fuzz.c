/*
 * libFuzzer target for the sandbox DOM (lexbor + sbx_dom): parse, select, read, remove.
 * Input: selector bytes, a 0x00 separator, then the HTML. Errors are fine; crashes, leaks and
 * sanitizer reports are not.
 */
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "sbx_dom.h"

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) {
    const uint8_t *sep = memchr(data, 0, size < 512 ? size : 512);
    if (!sep) return 0;
    size_t css_len = (size_t)(sep - data);
    char css[513];
    memcpy(css, data, css_len);
    css[css_len] = 0;
    const char *html = (const char *)sep + 1;
    size_t html_len = size - css_len - 1;

    sbx_dom_limits limits;
    sbx_dom_limits_default(&limits);
    limits.max_memory_bytes = 16u << 20;
    sbx_dom *dom = sbx_dom_new(&limits);
    if (!dom) return 0;
    int32_t doc = sbx_dom_parse(dom, html, html_len, "https://fuzz.example/a/b?c#d");
    if (doc > 0) {
        int32_t *hits = NULL;
        size_t count = 0;
        if (sbx_dom_select(dom, doc, css, &hits, &count) == SBX_OK) {
            for (size_t i = 0; i < count && i < 64; i++) {
                size_t n;
                sbx_dom_text(dom, hits[i], &n);
                sbx_dom_outer_html(dom, hits[i], &n);
                sbx_dom_attr(dom, hits[i], "abs:href", &n);
                sbx_dom_parent(dom, hits[i]);
                sbx_dom_next_sibling(dom, hits[i]);
                if (i % 3 == 2) sbx_dom_remove(dom, hits[i]);
            }
            /* Removed subtrees must stay safe to query. */
            for (size_t i = 0; i < count && i < 64; i++) {
                size_t n;
                sbx_dom_html(dom, hits[i], &n);
            }
            sbx_dom_free_array(hits);
        }
        sbx_dom_select_first(dom, doc, "a[href], img[src]");
        sbx_dom_release_all(dom);
    }
    sbx_dom_free(dom);
    return 0;
}
