/* libFuzzer target (and stand-alone driver) for sandbox_dom.
 *
 * Input layout: byte0 = selector length, then the selector, then the HTML document.  The target
 * parses with deliberately small limits so the limit paths are exercised as often as the parser. */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "sandbox_dom.h"

static sbx_dom *ctx;

static void walk(sbx_dom *c, int32_t h, int depth) {
    size_t l;
    sbx_dom_text(c, h, &l);
    sbx_dom_own_text(c, h, &l);
    sbx_dom_html(c, h, &l);
    sbx_dom_outer_html(c, h, &l);
    sbx_dom_attr(c, h, "href", &l);
    sbx_dom_abs_url(c, h, "href", &l);
    sbx_dom_abs_url(c, h, "src", &l);
    sbx_dom_has_attr(c, h, "class");
    sbx_dom_tag_name(c, h, &l);
    sbx_dom_class_name(c, h, &l);
    int32_t p = sbx_dom_parent(c, h);
    int32_t nx = sbx_dom_next_sibling(c, h);
    int32_t pv = sbx_dom_prev_sibling(c, h);
    if (depth < 3) {
        int32_t *kids; size_t n;
        if (sbx_dom_children(c, h, &kids, &n) == 0) {
            for (size_t i = 0; i < n && i < 8; i++) walk(c, kids[i], depth + 1);
            for (size_t i = 0; i < n; i++) sbx_dom_release(c, kids[i]);
            sbx_dom_free_array(kids);
        }
    }
    if (p > 0) sbx_dom_release(c, p);
    if (nx > 0) sbx_dom_release(c, nx);
    if (pv > 0) sbx_dom_release(c, pv);
}

int LLVMFuzzerInitialize(int *argc, char ***argv) {
    (void)argc; (void)argv;
    sbx_dom_limits lim;
    sbx_dom_limits_default(&lim);
    lim.max_input_bytes = 1u << 20;
    lim.max_nodes = 4096;
    lim.max_depth = 64;
    lim.max_handles = 256;
    lim.max_selector_chars = 256;
    lim.max_memory_bytes = (size_t)32 << 20;
    ctx = sbx_dom_new(&lim);
    return ctx ? 0 : 1;
}

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) {
    if (size < 2) return 0;
    size_t sl = data[0];
    if (sl + 1 > size) sl = size - 1;
    char *sel = malloc(sl + 1);
    memcpy(sel, data + 1, sl);
    sel[sl] = 0;
    const char *html = (const char *)data + 1 + sl;
    size_t hl = size - 1 - sl;
    int32_t d = sbx_dom_parse(ctx, html, hl, "https://fuzz.test/a/b?c#d");
    if (d > 0) {
        int32_t *hs; size_t n;
        int rc = sbx_dom_select(ctx, d, sel, &hs, &n);
        if (rc == 0) {
            for (size_t i = 0; i < n && i < 16; i++) walk(ctx, hs[i], 0);
            if (n) sbx_dom_remove(ctx, hs[0]);
            sbx_dom_free_array(hs);
        }
        int32_t f = sbx_dom_select_first(ctx, d, sel);
        if (f > 0) walk(ctx, f, 0);
        walk(ctx, d, 0);
    }
    sbx_dom_release_all(ctx);
    free(sel);
    return 0;
}

#ifdef SBX_STANDALONE_FUZZ
/* Dependency-free driver: mutates a corpus of seeds for a given number of seconds. */
#include <time.h>
static uint64_t rs = 88172645463325252ull;
static uint64_t rnd(void) { rs ^= rs << 13; rs ^= rs >> 7; rs ^= rs << 17; return rs; }
int main(int argc, char **argv) {
    double secs = argc > 1 ? atof(argv[1]) : 10;
    LLVMFuzzerInitialize(NULL, NULL);
    static const char *seeds[] = {
        "\x0e""a[href^=http]:has(img)<div class=a><a href=/x>t</a><img src=y><table><tr><td>1<td>2</table><p>a<p>b<b><i>x</b></i>",
        "\x14""li:nth-child(2n+1):not(.x)<ul><li>1<li class=x>2<li>3</ul><svg><title>t</title></svg><template><p>x</template>",
        "\x0d""p:contains(ab)<p>ab</p><pre>  x </pre><script>if(a<b){}</script><!-- c --><base href=//e.test/>",
        "\x08""> div:eq(1)<div><div>1</div><div>2</div></div><textarea><b></textarea><select><option>a<option>b</select>",
    };
    time_t end = time(NULL) + (time_t)secs;
    uint64_t iters = 0;
    uint8_t buf[8192];
    while (time(NULL) < end) {
        const char *sd = seeds[rnd() % 4];
        size_t n = strlen(sd + 1) + 1;
        /* seeds start with a selector length byte; strlen stops at the first NUL only */
        if (n > sizeof buf) n = sizeof buf;
        memcpy(buf, sd, n);
        size_t len = n;
        int muts = 1 + (int)(rnd() % 8);
        for (int m = 0; m < muts; m++) {
            switch (rnd() % 6) {
            case 0: if (len) buf[rnd() % len] = (uint8_t)rnd(); break;
            case 1: if (len < sizeof buf - 8) { size_t p = len ? rnd() % len : 0; memmove(buf + p + 1, buf + p, len - p); buf[p] = "<>/\"'=&;:[]()#. \n"[rnd() % 17]; len++; } break;
            case 2: if (len > 1) { size_t p = rnd() % len; memmove(buf + p, buf + p + 1, len - p - 1); len--; } break;
            case 3: if (len && len < sizeof buf / 2) { size_t p = rnd() % len, q = rnd() % len, k = rnd() % 64; if (p + k > len) k = len - p; if (len + k < sizeof buf) { memmove(buf + q + k, buf + q, len - q); memmove(buf + q, buf + (q <= p ? p + k : p), k); len += k; } } break;
            case 4: { /* deep nesting / many nodes */
                size_t k = rnd() % 600; static const char *units[] = {"<div>", "<b>", "<i><u>", "<p>", "<table>", "<a>", "<span><div>", "<li>"};
                const char *u = units[rnd() % 8]; size_t ul = strlen(u);
                size_t p = len ? rnd() % len : 0;
                if (len + k * ul < sizeof buf) { memmove(buf + p + k * ul, buf + p, len - p); for (size_t i = 0; i < k; i++) memmove(buf + p + i * ul, u, ul); len += k * ul; }
                break; }
            case 5: if (len > 2) len = 1 + rnd() % len; break;
            }
        }
        LLVMFuzzerTestOneInput(buf, len);
        iters++;
    }
    printf("standalone fuzz: %llu inputs in %.0fs, no crash\n", (unsigned long long)iters, secs);
    return 0;
}
#endif
