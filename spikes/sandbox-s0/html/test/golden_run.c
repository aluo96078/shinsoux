/* Prints the golden line format produced by oracle/Oracle.java using sandbox_dom.
 * usage: golden_run <golden-dir> <selectors-file>... */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "sandbox_dom.h"

static void esc(const char *s, size_t n) {
    size_t lim = n > 20000 ? 20000 : n;
    for (size_t i = 0; i < lim; i++) {
        unsigned char c = (unsigned char)s[i];
        switch (c) {
        case '\\': fputs("\\\\", stdout); break;
        case '\n': fputs("\\n", stdout); break;
        case '\r': fputs("\\r", stdout); break;
        case '\t': fputs("\\t", stdout); break;
        default: fputc(c, stdout);
        }
    }
    if (n > 20000) fputs("...<cut>", stdout);
}
static void prop_s(int idx, const char *name, const char *s, size_t n) {
    printf("  [%d].%s=", idx, name); esc(s, n); putchar('\n');
}
static void prop_n(int idx, const char *name, const char *nm) {
    printf("  [%d].%s=%s\n", idx, name, nm);
}

static char *slurp(const char *path, size_t *n) {
    FILE *f = fopen(path, "rb");
    if (!f) { fprintf(stderr, "cannot open %s\n", path); exit(2); }
    fseek(f, 0, SEEK_END); long sz = ftell(f); fseek(f, 0, SEEK_SET);
    char *b = malloc((size_t)sz + 1);
    if (fread(b, 1, (size_t)sz, f) != (size_t)sz) exit(2);
    b[sz] = 0; fclose(f); *n = (size_t)sz;
    return b;
}

static void tag_of(sbx_dom *c, int32_t h, const char **s, size_t *n) { *s = sbx_dom_tag_name(c, h, n); }

static void dump(sbx_dom *c, int i, int32_t h) {
    size_t n; const char *s;
    s = sbx_dom_tag_name(c, h, &n); prop_s(i, "tag", s, n);
    s = sbx_dom_id(c, h, &n); prop_s(i, "id", s, n);
    s = sbx_dom_class_name(c, h, &n); prop_s(i, "class", s, n);
    s = sbx_dom_text(c, h, &n); prop_s(i, "text", s, n);
    s = sbx_dom_own_text(c, h, &n); prop_s(i, "own", s, n);
    s = sbx_dom_attr(c, h, "href", &n); prop_s(i, "href", s, n);
    s = sbx_dom_attr(c, h, "src", &n); prop_s(i, "src", s, n);
    s = sbx_dom_attr(c, h, "data-original", &n); prop_s(i, "data-original", s, n);
    s = sbx_dom_abs_url(c, h, "href", &n); prop_s(i, "abshref", s, n);
    s = sbx_dom_abs_url(c, h, "src", &n); prop_s(i, "abssrc", s, n);
    prop_n(i, "has_href", sbx_dom_has_attr(c, h, "href") ? "true" : "false");
    int32_t *kids; size_t kn = 0;
    if (sbx_dom_children(c, h, &kids, &kn) == 0) {
        char b[24]; snprintf(b, sizeof b, "%zu", kn);
        prop_n(i, "nchildren", b);
        for (size_t k = 0; k < kn; k++) sbx_dom_release(c, kids[k]);
        sbx_dom_free_array(kids);
    }
    int32_t r = sbx_dom_parent(c, h);
    if (r) { s = sbx_dom_tag_name(c, r, &n); prop_s(i, "parent", s, n); sbx_dom_release(c, r); } else prop_n(i, "parent", "<null>");
    r = sbx_dom_next_sibling(c, h);
    if (r) { s = sbx_dom_tag_name(c, r, &n); prop_s(i, "next", s, n); sbx_dom_release(c, r); } else prop_n(i, "next", "<null>");
    r = sbx_dom_prev_sibling(c, h);
    if (r) { s = sbx_dom_tag_name(c, r, &n); prop_s(i, "prev", s, n); sbx_dom_release(c, r); } else prop_n(i, "prev", "<null>");
    if (i < 3) {
        s = sbx_dom_html(c, h, &n); prop_s(i, "html", s, n);
        s = sbx_dom_outer_html(c, h, &n); prop_s(i, "outer", s, n);
    }
    (void)tag_of;
}

int main(int argc, char **argv) {
    if (argc == 3 && !strcmp(argv[1], "--urls")) {
        size_t un; char *txt = slurp(argv[2], &un);
        for (char *line = strtok(txt, "\n"); line; line = strtok(NULL, "\n")) {
            char *rel = strchr(line, '\t');
            *rel++ = 0;
            char out[4096];
            sbx_dom_resolve_url(line, rel, out, sizeof out);
            esc(line, strlen(line)); fputs(" | ", stdout); esc(rel, strlen(rel)); fputs(" => ", stdout); esc(out, strlen(out)); putchar('\n');
        }
        return 0;
    }
    if (argc < 3) { fprintf(stderr, "usage\n"); return 2; }
    char path[4096];
    snprintf(path, sizeof path, "%s/fixtures.txt", argv[1]);
    size_t fn; char *fixtures = slurp(path, &fn);
    /* selectors */
    char **sels = NULL; size_t nsel = 0;
    for (int a = 2; a < argc; a++) {
        size_t sn; char *txt = slurp(argv[a], &sn);
        char *p = txt;
        while (p < txt + sn) {
            char *e = strchr(p, '\n');
            if (!e) e = txt + sn;
            *e = 0;
            sels = realloc(sels, (nsel + 1) * sizeof *sels);
            sels[nsel++] = p;
            p = e + 1;
        }
    }
    sbx_dom_limits lim;
    sbx_dom_limits_default(&lim);
    lim.max_nodes = 2000000; lim.max_input_bytes = 16u << 20; lim.max_handles = 65535; lim.max_depth = 4096;
    lim.max_memory_bytes = (size_t)512 << 20;
    sbx_dom *c = sbx_dom_new(&lim);
    for (char *line = strtok(fixtures, "\n"); line; line = strtok(NULL, "\n")) {
        char *name = line, *base = strchr(name, '\t'); *base++ = 0;
        char *file = strchr(base, '\t'); *file++ = 0;
        snprintf(path, sizeof path, "%s/%s", argv[1], file);
        size_t hn; char *html = slurp(path, &hn);
        int32_t doc = sbx_dom_parse(c, html, hn, base);
        printf("### %s\n", name);
        if (doc <= 0) { printf("PARSE FAILED %d\n", doc); continue; }
        size_t n; const char *s;
        s = sbx_dom_tag_name(c, doc, &n); printf("DOC.tag="); esc(s, n); putchar('\n');
        s = sbx_dom_text(c, doc, &n); printf("DOC.text="); esc(s, n); putchar('\n');
        int32_t t = sbx_dom_select_first(c, doc, "title");
        printf("DOC.title=");
        if (t > 0) { s = sbx_dom_text(c, t, &n); esc(s, n); sbx_dom_release(c, t); }
        putchar('\n');
        int32_t body = sbx_dom_select_first(c, doc, "body");
        printf("DOC.body.text=");
        if (body > 0) { s = sbx_dom_text(c, body, &n); esc(s, n); sbx_dom_release(c, body); } else printf("<null>");
        putchar('\n');
        for (size_t si = 0; si < nsel; si++) {
            printf("SEL "); esc(sels[si], strlen(sels[si])); putchar('\n');
            int32_t *hs; size_t cnt;
            int rc = sbx_dom_select(c, doc, sels[si], &hs, &cnt);
            if (rc != 0) { printf("  ERROR\n"); if (rc != SBX_ERR_SELECTOR && rc != SBX_ERR_UNSUPPORTED) printf("  # rc=%d\n", rc); continue; }
            printf("  count=%zu\n", cnt);
            for (size_t i = 0; i < cnt; i++) {
                if (i >= 4 && i + 2 < cnt) continue;
                dump(c, (int)i, hs[i]);
            }
            int32_t f = sbx_dom_select_first(c, doc, sels[si]);
            if (f > 0) { s = sbx_dom_tag_name(c, f, &n); printf("  first="); esc(s, n); putchar('\n'); sbx_dom_release(c, f); }
            else printf("  first=<null>\n");
            for (size_t i = 0; i < cnt; i++) sbx_dom_release(c, hs[i]);
            sbx_dom_free_array(hs);
        }
        sbx_dom_release(c, doc);
        free(html);
    }
    sbx_dom_free(c);
    return 0;
}
