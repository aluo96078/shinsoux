/*
 * jsoup parity through the sandbox's script-visible DOM API: prints the golden line format of
 * spikes/sandbox-s0/html/oracle/Oracle.java, but every value comes from scripts calling
 * Jsoup.parse/select/text/... inside the core. Compare with spikes/sandbox-s0/html/scripts/compare_golden.py.
 *
 * usage: golden_js <bootstrap.js> <golden-dir> <selectors-file>...
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "ssc_core.h"

static uint64_t now_ms(void *o) { (void)o; struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts); return (uint64_t)ts.tv_sec * 1000u + (uint64_t)ts.tv_nsec / 1000000u; }
static int no_bridge(void *o, const char *m, const char *a, size_t l, char **out, size_t *ol) { (void)o; (void)m; (void)a; (void)l; (void)out; (void)ol; return -1; }
static int no_async(void *o, uint32_t id, const char *r, size_t l) { (void)o; (void)id; (void)r; (void)l; return -1; }
static void no_cancel(void *o, uint32_t id) { (void)o; (void)id; }
static int no_wait(void *o, ssc_runtime *rt, uint64_t d) { (void)o; (void)rt; (void)d; return 0; }
static void no_log(void *o, const char *m, size_t l) { (void)o; (void)m; (void)l; }

static char *slurp(const char *path, size_t *n) {
    FILE *f = fopen(path, "rb");
    if (!f) { fprintf(stderr, "cannot open %s\n", path); exit(2); }
    fseek(f, 0, SEEK_END); long sz = ftell(f); fseek(f, 0, SEEK_SET);
    char *b = malloc((size_t)sz + 1);
    if (fread(b, 1, (size_t)sz, f) != (size_t)sz) exit(2);
    b[sz] = 0; fclose(f); *n = (size_t)sz;
    return b;
}

/* JSON-encodes bytes as a string literal (UTF-8 passes through). */
static size_t json_str(char *out, const char *s, size_t n) {
    size_t k = 0;
    out[k++] = '"';
    for (size_t i = 0; i < n; i++) {
        unsigned char c = (unsigned char)s[i];
        if (c == '"' || c == '\\') { out[k++] = '\\'; out[k++] = (char)c; }
        else if (c < 0x20) k += (size_t)sprintf(out + k, "\\u%04x", c);
        else out[k++] = (char)c;
    }
    out[k++] = '"';
    return k;
}

/*
 * The script returns [[prefix, value-or-null], ...]; values get the oracle's escaping and
 * 20000-byte cut here (byte-accurate, which JS string lengths are not).
 */
static const char SCRIPT[] =
    "var source={};"
    "function rel(e){return e?e.tagName():null;}"
    "function dump(out,i,e){var p='  ['+i+'].';"
    " out.push([p+'tag=',e.tagName()]);out.push([p+'id=',e.id()]);out.push([p+'class=',e.className()]);"
    " out.push([p+'text=',e.text()]);out.push([p+'own=',e.ownText()]);out.push([p+'href=',e.attr('href')]);"
    " out.push([p+'src=',e.attr('src')]);out.push([p+'data-original=',e.attr('data-original')]);"
    " out.push([p+'abshref=',e.absUrl('href')]);out.push([p+'abssrc=',e.absUrl('src')]);"
    " out.push([p+'has_href='+(e.hasAttr('href')?'true':'false'),null]);"
    " out.push([p+'nchildren='+e.children().size(),null]);"
    " var r=e.parent();out.push(r?[p+'parent=',r.tagName()]:[p+'parent=<null>',null]);"
    " r=e.nextElementSibling();out.push(r?[p+'next=',r.tagName()]:[p+'next=<null>',null]);"
    " r=e.previousElementSibling();out.push(r?[p+'prev=',r.tagName()]:[p+'prev=<null>',null]);"
    " if(i<3){out.push([p+'html=',e.html()]);out.push([p+'outer=',e.outerHtml()]);}}"
    "globalThis.__shinsouGoldenDoc=function(html,base){var d=Jsoup.parse(html,base);var out=[];"
    " out.push(['DOC.tag=',d.tagName()]);out.push(['DOC.text=',d.text()]);"
    " var t=d.selectFirst('title');out.push(['DOC.title=',t?t.text():'']);"
    " var b=d.selectFirst('body');out.push(b?['DOC.body.text=',b.text()]:['DOC.body.text=<null>',null]);return out;};"
    "globalThis.__shinsouGoldenSel=function(html,base,sel){var d=Jsoup.parse(html,base);var out=[];var all;"
    " try{all=d.select(sel);}catch(e){out.push(['  ERROR',null]);return out;}"
    " out.push(['  count='+all.length,null]);"
    " for(var i=0;i<all.length;i++){if(i>=4&&i+2<all.length)continue;dump(out,i,all[i]);}"
    " var f=d.selectFirst(sel);out.push(f?['  first=',f.tagName()]:['  first=<null>',null]);return out;};";

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

/* Minimal reader for the [[string, string|null], ...] result (strings are JSON-escaped). */
static const char *read_str(const char *p, char **out, size_t *n) {
    if (*p != '"') return NULL;
    p++;
    size_t cap = 256, k = 0;
    char *b = malloc(cap);
    while (*p && *p != '"') {
        if (k + 8 > cap) { cap *= 2; b = realloc(b, cap); }
        if (*p == '\\') {
            p++;
            switch (*p) {
            case 'n': b[k++] = '\n'; break;
            case 'r': b[k++] = '\r'; break;
            case 't': b[k++] = '\t'; break;
            case 'b': b[k++] = '\b'; break;
            case 'f': b[k++] = '\f'; break;
            case 'u': {
                unsigned cp = 0;
                sscanf(p + 1, "%4x", &cp);
                p += 4;
                if (cp >= 0xd800 && cp <= 0xdbff && p[1] == '\\' && p[2] == 'u') {
                    unsigned lo = 0;
                    sscanf(p + 3, "%4x", &lo);
                    cp = 0x10000 + ((cp - 0xd800) << 10) + (lo - 0xdc00);
                    p += 6;
                }
                if (cp < 0x80) b[k++] = (char)cp;
                else if (cp < 0x800) { b[k++] = (char)(0xc0 | cp >> 6); b[k++] = (char)(0x80 | (cp & 0x3f)); }
                else if (cp < 0x10000) { b[k++] = (char)(0xe0 | cp >> 12); b[k++] = (char)(0x80 | ((cp >> 6) & 0x3f)); b[k++] = (char)(0x80 | (cp & 0x3f)); }
                else { b[k++] = (char)(0xf0 | cp >> 18); b[k++] = (char)(0x80 | ((cp >> 12) & 0x3f)); b[k++] = (char)(0x80 | ((cp >> 6) & 0x3f)); b[k++] = (char)(0x80 | (cp & 0x3f)); }
                break;
            }
            default: b[k++] = *p;
            }
            p++;
        } else {
            b[k++] = *p++;
        }
    }
    *out = b;
    *n = k;
    return *p == '"' ? p + 1 : NULL;
}

static void print_lines(const char *json) {
    const char *p = json;
    if (*p++ != '[') return;
    while (*p == '[') {
        p++;
        char *prefix; size_t pn;
        p = read_str(p, &prefix, &pn);
        if (!p || *p++ != ',') { free(prefix); return; }
        fwrite(prefix, 1, pn, stdout);
        free(prefix);
        if (*p == 'n') { p += 4; }
        else { char *v; size_t vn; p = read_str(p, &v, &vn); if (!p) return; esc(v, vn); free(v); }
        putchar('\n');
        if (*p++ != ']') return;
        if (*p == ',') p++;
    }
}

int main(int argc, char **argv) {
    if (argc < 4) { fprintf(stderr, "usage: golden_js bootstrap.js golden-dir selectors...\n"); return 2; }
    size_t blen; char *bootstrap = slurp(argv[1], &blen);
    char path[4096];
    snprintf(path, sizeof path, "%s/fixtures.txt", argv[2]);
    size_t fn; char *fixtures = slurp(path, &fn);
    char **sels = NULL; size_t nsel = 0;
    for (int a = 3; a < argc; a++) {
        size_t sn; char *txt = slurp(argv[a], &sn);
        for (char *q = txt; q < txt + sn;) {
            char *e = strchr(q, '\n'); if (!e) e = txt + sn;
            *e = 0;
            sels = realloc(sels, (nsel + 1) * sizeof *sels); sels[nsel++] = q;
            q = e + 1;
        }
    }
    ssc_limits l = { .heap_limit_bytes = 512u << 20, .stack_limit_bytes = 1u << 20, .init_timeout_ms = 10000, .invoke_timeout_ms = 60000,
                     .max_bridge_calls = 16, .max_async_inflight = 1, .max_async_queue = 1, .max_result_bytes = 256u << 20,
                     .dom_max_nodes = 2000000, .dom_max_depth = 4096 };
    ssc_host host = { NULL, now_ms, no_bridge, no_async, no_cancel, no_wait, no_log };
    ssc_runtime *rt = ssc_new(&l, &host);
    ssc_set_dom_test_limits(rt, 65535, 16u << 20, (size_t)512 << 20);
    uint64_t gen = 1;
    if (ssc_init(rt, gen++, bootstrap, blen, SCRIPT, sizeof SCRIPT - 1) != SSC_OK) { fprintf(stderr, "init failed\n"); return 1; }
    for (char *line = strtok(fixtures, "\n"); line; line = strtok(NULL, "\n")) {
        char *name = line, *base = strchr(name, '\t'); *base++ = 0;
        char *file = strchr(base, '\t'); *file++ = 0;
        snprintf(path, sizeof path, "%s/%s", argv[2], file);
        size_t hn; char *html = slurp(path, &hn);
        char *args = malloc(hn * 6 + 1024);
        printf("### %s\n", name);
        size_t k = 0;
        args[k++] = '[';
        size_t html_at = k;
        k += json_str(args + k, html, hn);
        args[k++] = ',';
        k += json_str(args + k, base, strlen(base));
        size_t prefix_len = k;
        args[k++] = ']';
        char *out = NULL; size_t olen;
        ssc_status st = ssc_invoke(rt, gen++, "__shinsouGoldenDoc", 18, args, k, &out, &olen);
        if (st != SSC_OK) { printf("PARSE FAILED %s\n", ssc_status_name(st)); free(out); free(args); free(html); continue; }
        print_lines(out); free(out);
        (void)html_at;
        for (size_t si = 0; si < nsel; si++) {
            printf("SEL "); esc(sels[si], strlen(sels[si])); putchar('\n');
            k = prefix_len;
            args[k++] = ',';
            k += json_str(args + k, sels[si], strlen(sels[si]));
            args[k++] = ']';
            out = NULL;
            st = ssc_invoke(rt, gen++, "__shinsouGoldenSel", 18, args, k, &out, &olen);
            if (st != SSC_OK) { printf("  ERROR\n  # %s\n", ssc_status_name(st)); free(out); continue; }
            print_lines(out); free(out);
        }
        free(args);
        free(html);
    }
    ssc_free(rt);
    return 0;
}
