/* See sandbox_dom.h.  Jsoup 1.18.3 is the behavioural reference. */
#define _GNU_SOURCE
#include "sandbox_dom.h"

#include <ctype.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <lexbor/css/css.h>
#include <lexbor/html/html.h>
#include <lexbor/html/parser.h>
#include <lexbor/html/tokenizer.h>
#include <lexbor/html/tree.h>
#include <lexbor/selectors/selectors.h>

/* ------------------------------------------------------------------------------------------ */
/* Growable byte buffer                                                                       */
/* ------------------------------------------------------------------------------------------ */

typedef struct {
    char *data;
    size_t len, cap;
    bool oom;
} sbuf;

static void sb_reserve(sbuf *b, size_t extra) {
    if (b->oom || b->len + extra + 1 <= b->cap) return;
    size_t ncap = b->cap ? b->cap : 256;
    while (ncap < b->len + extra + 1) ncap *= 2;
    char *p = realloc(b->data, ncap);
    if (!p) { b->oom = true; return; }
    b->data = p;
    b->cap = ncap;
}
static void sb_put(sbuf *b, const char *s, size_t n) {
    sb_reserve(b, n);
    if (b->oom) return;
    memcpy(b->data + b->len, s, n);
    b->len += n;
    b->data[b->len] = 0;
}
static void sb_puts(sbuf *b, const char *s) { sb_put(b, s, strlen(s)); }
static void sb_putc(sbuf *b, char c) { sb_put(b, &c, 1); }
static void sb_clear(sbuf *b) { b->len = 0; if (b->data) b->data[0] = 0; }
static void sb_free(sbuf *b) { free(b->data); memset(b, 0, sizeof *b); }

/* ------------------------------------------------------------------------------------------ */
/* Context                                                                                    */
/* ------------------------------------------------------------------------------------------ */

typedef struct sbx_doc {
    lxb_html_document_t *doc;
    char *base_uri;
    uint32_t refs;
    bool alive;
} sbx_doc;

typedef struct {
    lxb_dom_node_t *node;
    uint32_t doc;     /* index into ctx->docs */
    uint16_t gen;
    bool used;
} sbx_slot;

#define MARK_PREFIX "sbx-m"
#define ROOT_MARK "sbx-root"
#define MAX_MARKS 32
#define SEL_CACHE 16

typedef enum { MK_CONTAINS, MK_CONTAINS_OWN, MK_EQ, MK_LT, MK_GT, MK_EMPTY } mark_kind;
typedef struct {
    mark_kind kind;
    char *needle;   /* lower-cased, normalised */
    long index;
} mark_def;

typedef struct {
    char *key;
    lxb_css_selector_list_t *list;
} sel_entry;

struct sbx_dom {
    sbx_dom_limits lim;
    size_t mem, mem_peak;
    int limit_hit;                 /* sticky SBX_ERR_LIMIT_* raised by hooks during one call */
    sbx_slot *slots;
    uint32_t slot_cap, live;
    uint32_t *free_slots;
    uint32_t free_n;
    sbx_doc *docs;
    uint32_t doc_cap, live_docs;
    sbuf scratch;
    int last_error;
    uint32_t last_nodes, last_depth;
    lxb_css_parser_t *css_parser;
    lxb_selectors_t *selectors;
    sel_entry cache[SEL_CACHE];
    uint32_t cache_next;
};

/* ------------------------------------------------------------------------------------------ */
/* lexbor allocator hook: byte budget per context                                             */
/* ------------------------------------------------------------------------------------------ */

static sbx_dom *g_active;
static bool g_hooked;

typedef struct { size_t size; sbx_dom *owner; } alloc_hdr;   /* 16 bytes keeps 16-byte alignment */

static void *hook_malloc(size_t size) {
    sbx_dom *c = g_active;
    if (c) {
        if (size > c->lim.max_memory_bytes || c->mem + size + sizeof(alloc_hdr) > c->lim.max_memory_bytes) {
            c->limit_hit = SBX_ERR_LIMIT_MEMORY;
            return NULL;
        }
    }
    alloc_hdr *h = malloc(size + sizeof *h);
    if (!h) return NULL;
    h->size = size;
    h->owner = c;
    if (c) { c->mem += size + sizeof *h; if (c->mem > c->mem_peak) c->mem_peak = c->mem; }
    return h + 1;
}
static void hook_free(void *p) {
    if (!p) return;
    alloc_hdr *h = (alloc_hdr *)p - 1;
    if (h->owner) h->owner->mem -= h->size + sizeof *h;
    free(h);
}
static void *hook_calloc(size_t n, size_t m) {
    if (m && n > SIZE_MAX / m) return NULL;
    void *p = hook_malloc(n * m);
    if (p) memset(p, 0, n * m);
    return p;
}
static void *hook_realloc(void *p, size_t size) {
    if (!p) return hook_malloc(size);
    if (size == 0) { hook_free(p); return NULL; }
    alloc_hdr *h = (alloc_hdr *)p - 1;
    void *n = hook_malloc(size);
    if (!n) return NULL;
    memcpy(n, p, h->size < size ? h->size : size);
    hook_free(p);
    return n;
}

#define ENTER(c) sbx_dom *prev_active_ = g_active; g_active = (c); (c)->limit_hit = 0
#define LEAVE() g_active = prev_active_

/* ------------------------------------------------------------------------------------------ */
/* Lifecycle                                                                                  */
/* ------------------------------------------------------------------------------------------ */

void sbx_dom_limits_default(sbx_dom_limits *l) {
    l->max_input_bytes = 4u << 20;
    l->max_nodes = 65536;
    l->max_depth = 256;
    l->max_handles = 4096;
    l->max_selector_chars = 4096;
    l->max_memory_bytes = (size_t)64 << 20;
}

sbx_dom *sbx_dom_new(const sbx_dom_limits *limits) {
    if (!g_hooked) {
        if (lexbor_memory_setup(hook_malloc, hook_realloc, hook_calloc, hook_free) != LXB_STATUS_OK) return NULL;
        g_hooked = true;
    }
    sbx_dom *c = calloc(1, sizeof *c);
    if (!c) return NULL;
    if (limits) c->lim = *limits; else sbx_dom_limits_default(&c->lim);
    if (c->lim.max_handles > 65535) c->lim.max_handles = 65535;
    ENTER(c);
    c->css_parser = lxb_css_parser_create();
    c->selectors = lxb_selectors_create();
    bool ok = c->css_parser && c->selectors
        && lxb_css_parser_init(c->css_parser, NULL) == LXB_STATUS_OK
        && lxb_selectors_init(c->selectors) == LXB_STATUS_OK;
    if (ok) lxb_selectors_opt_set(c->selectors, LXB_SELECTORS_OPT_MATCH_FIRST);
    LEAVE();
    if (!ok) { sbx_dom_free(c); return NULL; }
    return c;
}

static void drop_cache(sbx_dom *c) {
    for (int i = 0; i < SEL_CACHE; i++) {
        if (c->cache[i].list) lxb_css_selector_list_destroy_memory(c->cache[i].list);
        free(c->cache[i].key);
        c->cache[i].list = NULL;
        c->cache[i].key = NULL;
    }
}

static void doc_unref(sbx_dom *c, uint32_t di) {
    sbx_doc *d = &c->docs[di];
    if (d->alive && --d->refs == 0) {
        lxb_html_document_destroy(d->doc);
        free(d->base_uri);
        d->doc = NULL; d->base_uri = NULL; d->alive = false;
        c->live_docs--;
    }
}

void sbx_dom_release_all(sbx_dom *c) {
    ENTER(c);
    for (uint32_t i = 0; i < c->slot_cap; i++) {
        if (c->slots[i].used) {
            c->slots[i].used = false;
            c->slots[i].gen++;
            doc_unref(c, c->slots[i].doc);
        }
    }
    c->live = 0;
    c->free_n = 0;
    for (uint32_t i = c->slot_cap; i > 0; i--) c->free_slots[c->free_n++] = i - 1;
    LEAVE();
}

void sbx_dom_free(sbx_dom *c) {
    if (!c) return;
    ENTER(c);
    sbx_dom_release_all(c);
    drop_cache(c);
    if (c->selectors) lxb_selectors_destroy(c->selectors, true);
    if (c->css_parser) lxb_css_parser_destroy(c->css_parser, true);
    LEAVE();
    free(c->slots); free(c->free_slots); free(c->docs);
    sb_free(&c->scratch);
    free(c);
}

void sbx_dom_get_stats(sbx_dom *c, sbx_dom_stats *s) {
    s->live_handles = c->live;
    s->live_documents = c->live_docs;
    s->memory_bytes = c->mem;
    s->memory_peak_bytes = c->mem_peak;
    s->last_parse_nodes = c->last_nodes;
    s->last_parse_depth = c->last_depth;
}
int sbx_dom_last_error(sbx_dom *c) { return c->last_error; }

/* ------------------------------------------------------------------------------------------ */
/* Handles                                                                                    */
/* ------------------------------------------------------------------------------------------ */

/* handle = (gen << 16) | (slot + 1); gen starts at 1 so handle > 0 and fits int32. */
static int32_t slot_alloc(sbx_dom *c, lxb_dom_node_t *node, uint32_t di) {
    if (c->live >= c->lim.max_handles) return SBX_ERR_LIMIT_HANDLES;
    if (c->free_n == 0) {
        uint32_t ncap = c->slot_cap ? c->slot_cap * 2 : 64;
        if (ncap > c->lim.max_handles) ncap = c->lim.max_handles;
        if (ncap <= c->slot_cap) return SBX_ERR_LIMIT_HANDLES;
        sbx_slot *ns = realloc(c->slots, ncap * sizeof *ns);
        uint32_t *nf = realloc(c->free_slots, ncap * sizeof *nf);
        if (!ns || !nf) { if (ns) c->slots = ns; if (nf) c->free_slots = nf; return SBX_ERR_LIMIT_MEMORY; }
        c->slots = ns; c->free_slots = nf;
        for (uint32_t i = ncap; i > c->slot_cap; i--) {
            ns[i - 1] = (sbx_slot){0};
            ns[i - 1].gen = 1;
            c->free_slots[c->free_n++] = i - 1;
        }
        c->slot_cap = ncap;
    }
    uint32_t s = c->free_slots[--c->free_n];
    c->slots[s].node = node;
    c->slots[s].doc = di;
    c->slots[s].used = true;
    c->live++;
    c->docs[di].refs++;
    return (int32_t)(((uint32_t)c->slots[s].gen << 16) | (s + 1));
}

static sbx_slot *slot_get(sbx_dom *c, int32_t h) {
    if (h <= 0) return NULL;
    uint32_t s = ((uint32_t)h & 0xffff);
    if (s == 0 || s > c->slot_cap) return NULL;
    sbx_slot *sl = &c->slots[s - 1];
    if (!sl->used || sl->gen != ((uint32_t)h >> 16)) return NULL;
    return sl;
}

void sbx_dom_release(sbx_dom *c, int32_t h) {
    sbx_slot *sl = slot_get(c, h);
    if (!sl) return;
    ENTER(c);
    sl->used = false;
    if (++sl->gen == 0) sl->gen = 1;
    c->live--;
    c->free_slots[c->free_n++] = (uint32_t)(sl - c->slots);
    doc_unref(c, sl->doc);
    LEAVE();
}

static inline bool is_element(const lxb_dom_node_t *n) { return n->type == LXB_DOM_NODE_TYPE_ELEMENT; }
static inline bool is_text(const lxb_dom_node_t *n) { return n->type == LXB_DOM_NODE_TYPE_TEXT; }
static inline bool is_document(const lxb_dom_node_t *n) { return n->type == LXB_DOM_NODE_TYPE_DOCUMENT; }
static inline bool is_elem_like(const lxb_dom_node_t *n) { return is_element(n) || is_document(n); }

/* ------------------------------------------------------------------------------------------ */
/* Parse                                                                                      */
/* ------------------------------------------------------------------------------------------ */

typedef struct {
    sbx_dom *ctx;
    lxb_html_tokenizer_token_f orig_cb;
    void *orig_ctx;
    lxb_html_tree_t *tree;
    uint32_t tokens;
} parse_hook;

static lxb_html_token_t *token_hook(lxb_html_tokenizer_t *tkz, lxb_html_token_t *token, void *ctx) {
    parse_hook *ph = ctx;
    /* Every token yields at most one node, so this bounds node creation without a tree walk;
     * the exact count is re-checked after the parse. */
    if (++ph->tokens > ph->ctx->lim.max_nodes + 8) {
        ph->ctx->limit_hit = SBX_ERR_LIMIT_NODES;
        return NULL;
    }
    if (ph->tree && ph->tree->open_elements && ph->tree->open_elements->length > ph->ctx->lim.max_depth) {
        ph->ctx->limit_hit = SBX_ERR_LIMIT_DEPTH;
        return NULL;
    }
    return ph->orig_cb(tkz, token, ph->orig_ctx);
}

/* Exact node count and depth, iterative. */
static int check_tree(sbx_dom *c, lxb_dom_node_t *root) {
    uint32_t count = 0, depth = 0, maxd = 0;
    c->last_nodes = c->last_depth = 0;
    lxb_dom_node_t *n = root;
    while (n) {
        if (++count > c->lim.max_nodes) return SBX_ERR_LIMIT_NODES;
        c->last_nodes = count;
        if (n->first_child) {
            n = n->first_child;
            if (++depth > maxd) { maxd = depth; c->last_depth = maxd; }
            if (maxd > c->lim.max_depth + 3) return SBX_ERR_LIMIT_DEPTH;   /* html/body slack */
            continue;
        }
        while (n && n != root && !n->next) { n = n->parent; depth--; }
        if (!n || n == root) break;
        n = n->next;
    }
    return SBX_OK;
}

static int resolve_base_href(sbx_dom *c, sbx_doc *d);

int32_t sbx_dom_parse(sbx_dom *c, const char *html, size_t len, const char *base_uri) {
    c->last_error = 0;
    if (len > c->lim.max_input_bytes) return SBX_ERR_LIMIT_INPUT;
    ENTER(c);
    int rc = SBX_ERR_INTERNAL;
    if (c->live_docs >= c->doc_cap) {
        uint32_t ncap = c->doc_cap ? c->doc_cap * 2 : 8;
        /* reuse dead entries first */
        uint32_t di = c->doc_cap;
        for (uint32_t i = 0; i < c->doc_cap; i++) if (!c->docs[i].alive) { di = i; break; }
        if (di == c->doc_cap) {
            sbx_doc *nd = realloc(c->docs, ncap * sizeof *nd);
            if (!nd) { LEAVE(); return SBX_ERR_LIMIT_MEMORY; }
            memset(nd + c->doc_cap, 0, (ncap - c->doc_cap) * sizeof *nd);
            c->docs = nd; c->doc_cap = ncap;
        }
    }
    uint32_t di = 0;
    for (; di < c->doc_cap; di++) if (!c->docs[di].alive) break;
    lxb_html_document_t *doc = lxb_html_document_create();
    if (!doc) { rc = c->limit_hit ? c->limit_hit : SBX_ERR_LIMIT_MEMORY; LEAVE(); return rc; }

    lxb_status_t st = lxb_html_document_parse_chunk_begin(doc);
    parse_hook ph = {c, NULL, NULL, NULL, 0};
    if (st == LXB_STATUS_OK) {
        lxb_html_parser_t *parser = lxb_dom_interface_document(doc)->parser;
        lxb_html_tokenizer_t *tkz = parser->tkz;
        ph.orig_cb = tkz->callback_token_done;
        ph.orig_ctx = tkz->callback_token_ctx;
        ph.tree = parser->tree;
        lxb_html_tokenizer_callback_token_done_set(tkz, token_hook, &ph);
        /* Feed in slices so a hostile document is cut off early instead of being parsed whole. */
        const size_t slice = 16384;
        for (size_t off = 0; off < len && st == LXB_STATUS_OK; off += slice) {
            size_t n = len - off < slice ? len - off : slice;
            st = lxb_html_document_parse_chunk(doc, (const lxb_char_t *)html + off, n);
        }
        if (st == LXB_STATUS_OK) st = lxb_html_document_parse_chunk_end(doc);
    }
    if (st != LXB_STATUS_OK) {
        rc = c->limit_hit ? c->limit_hit : (st == LXB_STATUS_ERROR_MEMORY_ALLOCATION ? SBX_ERR_LIMIT_MEMORY : SBX_ERR_INTERNAL);
        lxb_html_document_destroy(doc);
        LEAVE();
        return rc;
    }
    rc = check_tree(c, lxb_dom_interface_node(doc));
    if (rc != SBX_OK) { lxb_html_document_destroy(doc); LEAVE(); return rc; }

    sbx_doc *d = &c->docs[di];
    d->doc = doc;
    d->alive = true;
    d->refs = 0;
    d->base_uri = strdup(base_uri ? base_uri : "");
    c->live_docs++;
    resolve_base_href(c, d);
    int32_t h = slot_alloc(c, lxb_dom_interface_node(doc), di);
    if (h < 0) { /* refs == 0: destroy right away */
        d->refs = 1; doc_unref(c, di);
    }
    LEAVE();
    return h;
}

/* ------------------------------------------------------------------------------------------ */
/* Attribute / name helpers                                                                   */
/* ------------------------------------------------------------------------------------------ */

static const char *elem_name(lxb_dom_node_t *n, size_t *len) {
    if (is_document(n)) { *len = 5; return "#root"; }
    size_t l = 0;
    const lxb_char_t *s = lxb_dom_element_local_name(lxb_dom_interface_element(n), &l);
    *len = l;
    return (const char *)s;
}

static const lxb_char_t *get_attr(lxb_dom_node_t *n, const char *name, size_t nl, size_t *vl, bool *found) {
    *found = false;
    *vl = 0;
    if (!is_element(n)) return (const lxb_char_t *)"";
    lxb_dom_attr_t *a = lxb_dom_element_attr_by_name(lxb_dom_interface_element(n), (const lxb_char_t *)name, nl);
    if (!a) {
        /* attribute names are lower-cased by the parser; jsoup compares ignoring case */
        char low[128];
        if (nl >= sizeof low) return (const lxb_char_t *)"";
        for (size_t i = 0; i < nl; i++) low[i] = (char)tolower((unsigned char)name[i]);
        a = lxb_dom_element_attr_by_name(lxb_dom_interface_element(n), (const lxb_char_t *)low, nl);
        if (!a) return (const lxb_char_t *)"";
    }
    *found = true;
    const lxb_char_t *v = lxb_dom_attr_value(a, vl);
    return v ? v : (const lxb_char_t *)"";
}

/* ------------------------------------------------------------------------------------------ */
/* URL resolution (java.net.URL flavoured, see StringUtil.resolve in jsoup)                   */
/* ------------------------------------------------------------------------------------------ */

static bool has_uri_scheme(const char *s) {
    if (!isalpha((unsigned char)s[0])) return false;
    size_t i = 1;
    while (isalnum((unsigned char)s[i]) || s[i] == '+' || s[i] == '-' || s[i] == '.') i++;
    return s[i] == ':';
}

static bool known_scheme(const char *s, size_t n) {
    static const char *const ok[] = {"http", "https", "ftp", "file", "jar", "mailto", "jrt"};
    for (size_t i = 0; i < sizeof ok / sizeof *ok; i++)
        if (strlen(ok[i]) == n && strncasecmp(ok[i], s, n) == 0) return true;
    return false;
}

typedef struct {
    char scheme[16];
    const char *auth; size_t auth_len; bool has_auth;
    const char *path; size_t path_len;
    const char *query; size_t query_len; bool has_query;
    const char *frag; size_t frag_len; bool has_frag;
} url_parts;

/* returns 1 if it has a scheme (even unknown), 0 if relative */
static int split_url(const char *u, url_parts *p) {
    memset(p, 0, sizeof *p);
    const char *s = u;
    size_t i = 0;
    int has_scheme = 0;
    if (isalpha((unsigned char)u[0])) {
        while (isalnum((unsigned char)u[i]) || u[i] == '+' || u[i] == '-' || u[i] == '.') i++;
        if (u[i] == ':' && i < sizeof p->scheme) {
            memcpy(p->scheme, u, i);
            for (size_t k = 0; k < i; k++) p->scheme[k] = (char)tolower((unsigned char)p->scheme[k]);
            has_scheme = 1;
            s = u + i + 1;
        }
    }
    if (s[0] == '/' && s[1] == '/') {
        s += 2;
        const char *e = s;
        while (*e && *e != '/' && *e != '?' && *e != '#') e++;
        p->auth = s; p->auth_len = (size_t)(e - s); p->has_auth = true;
        s = e;
    }
    const char *e = s;
    while (*e && *e != '?' && *e != '#') e++;
    p->path = s; p->path_len = (size_t)(e - s);
    s = e;
    if (*s == '?') {
        s++;
        e = s;
        while (*e && *e != '#') e++;
        p->query = s; p->query_len = (size_t)(e - s); p->has_query = true;
        s = e;
    }
    if (*s == '#') { p->frag = s + 1; p->frag_len = strlen(s + 1); p->has_frag = true; }
    return has_scheme;
}

static void remove_dots(const char *path, size_t n, sbuf *out) {
    /* RFC 3986 5.2.4, but "/.." above the root is dropped like java.net.URL does */
    char *in = malloc(n + 1);
    if (!in) { out->oom = true; return; }
    memcpy(in, path, n); in[n] = 0;
    size_t start = out->len;
    char *p = in;
    while (*p) {
        if (strncmp(p, "../", 3) == 0) p += 3;
        else if (strncmp(p, "./", 2) == 0) p += 2;
        else if (strncmp(p, "/./", 3) == 0) p += 2;
        else if (strcmp(p, "/.") == 0) { p += 1; *p = '/'; if (!p[1]) { sb_putc(out, '/'); break; } }
        else if (strncmp(p, "/../", 4) == 0 || strcmp(p, "/..") == 0) {
            bool last = p[3] == 0;
            p += last ? 3 : 3;
            while (out->len > start && out->data[out->len - 1] != '/') out->len--;
            if (out->len > start) out->len--;
            if (last) { sb_putc(out, '/'); break; }
        } else if (strcmp(p, "..") == 0 || strcmp(p, ".") == 0) break;
        else {
            char *q = p + 1;
            while (*q && *q != '/') q++;
            sb_put(out, p, (size_t)(q - p));
            p = q;
        }
    }
    free(in);
}

static bool resolve_into(const char *base, const char *rel_in, sbuf *out) {
    /* strip control chars and surrounding blanks like StringUtil.stripControlChars + URL.trim */
    size_t rl = strlen(rel_in);
    char *rel = malloc(rl + 1);
    if (!rel) return false;
    size_t k = 0;
    for (size_t i = 0; i < rl; i++) if ((unsigned char)rel_in[i] >= 0x20) rel[k++] = rel_in[i];
    rel[k] = 0;
    while (k && rel[k - 1] == ' ') rel[--k] = 0;
    char *r = rel;
    while (*r == ' ') r++;

    url_parts b, u;
    int base_ok = 0;
    if (base && *base) {
        int hs = split_url(base, &b);
        base_ok = hs && known_scheme(b.scheme, strlen(b.scheme));
    }
    bool ok = false;
    sb_clear(out);
    char *tmp = NULL;
    if (base_ok && r[0] == '?') {
        size_t pl = b.path_len;
        tmp = malloc(pl + strlen(r) + 1);
        if (!tmp) { free(rel); return false; }
        memcpy(tmp, b.path, pl); strcpy(tmp + pl, r);
        r = tmp;
    }
    int rel_has_scheme = split_url(r, &u);
    if (!rel_has_scheme && u.has_auth && u.auth_len == 0) {
        /* "///x" / "////x": java.net.URL inherits the base authority and keeps the slashes as path */
        u.has_auth = false;
        u.path = r;
        u.path_len += 2;
    }
    if (rel_has_scheme && !known_scheme(u.scheme, strlen(u.scheme))) goto done;   /* unknown protocol */
    if (!rel_has_scheme && !base_ok) goto done;                                     /* nothing to resolve against */

    const char *scheme; const char *auth; size_t auth_len; bool has_auth;
    sbuf path = {0};
    bool has_query; const char *query; size_t query_len; bool has_frag; const char *frag; size_t frag_len;
    if (rel_has_scheme && (u.has_auth || !base_ok || strcmp(u.scheme, b.scheme) != 0)) {
        scheme = u.scheme; auth = u.auth; auth_len = u.auth_len; has_auth = u.has_auth;
        sb_put(&path, u.path, u.path_len);   /* java.net.URL keeps dot segments of an absolute spec */
        has_query = u.has_query; query = u.query; query_len = u.query_len;
    } else {
        scheme = b.scheme;
        if (u.has_auth) {
            auth = u.auth; auth_len = u.auth_len; has_auth = true;
            remove_dots(u.path, u.path_len, &path);
            has_query = u.has_query; query = u.query; query_len = u.query_len;
        } else {
            auth = b.auth; auth_len = b.auth_len; has_auth = b.has_auth;
            for (size_t q = 0; q < auth_len; q++)           /* java.net.URL drops userinfo when inheriting the authority */
                if (auth[q] == '@') { auth += q + 1; auth_len -= q + 1; break; }
            if (u.path_len == 0) {
                sb_put(&path, b.path, b.path_len);
                if (b.path_len == 0 && b.has_auth && (u.has_query || r[0] == '?')) sb_putc(&path, '/');
                if (!u.has_query && !u.has_frag && r[0] == 0) { u.has_frag = b.has_frag; u.frag = b.frag; u.frag_len = b.frag_len; }
                if (u.has_query) { has_query = true; query = u.query; query_len = u.query_len; }
                else { has_query = b.has_query; query = b.query; query_len = b.query_len; }
            } else if (u.path[0] == '/') {
                remove_dots(u.path, u.path_len, &path);
                has_query = u.has_query; query = u.query; query_len = u.query_len;
            } else {
                size_t dir = b.path_len;
                while (dir > 0 && b.path[dir - 1] != '/') dir--;
                sbuf merged = {0};
                if (b.has_auth && b.path_len == 0) sb_putc(&merged, '/');
                sb_put(&merged, b.path, dir);
                sb_put(&merged, u.path, u.path_len);
                remove_dots(merged.data ? merged.data : "", merged.len, &path);
                sb_free(&merged);
                has_query = u.has_query; query = u.query; query_len = u.query_len;
            }
        }
    }
    has_frag = u.has_frag; frag = u.frag; frag_len = u.frag_len;
    sb_puts(out, scheme);
    sb_putc(out, ':');
    if (has_auth && !(auth_len == 0 && !strcmp(scheme, "file"))) { sb_puts(out, "//"); sb_put(out, auth, auth_len); }
    if (path.data) sb_put(out, path.data, path.len);
    if (has_query) { sb_putc(out, '?'); sb_put(out, query, query_len); }
    if (has_frag) { sb_putc(out, '#'); sb_put(out, frag, frag_len); }
    sb_free(&path);
    ok = !out->oom;
done:
    free(tmp);
    free(rel);
    return ok;
}

size_t sbx_dom_resolve_url(const char *base, const char *rel, char *out, size_t cap) {
    sbuf b = {0};
    size_t n = 0;
    if (resolve_into(base, rel, &b) && b.data) {
        n = b.len < cap - 1 ? b.len : cap - 1;
        memcpy(out, b.data, n);
    } else if (has_uri_scheme(rel)) {
        n = strlen(rel) < cap - 1 ? strlen(rel) : cap - 1;
        memcpy(out, rel, n);
    }
    out[n] = 0;
    sb_free(&b);
    return n;
}

static int resolve_base_href(sbx_dom *c, sbx_doc *d) {
    (void)c;
    /* jsoup: first <base href> whose absUrl is non-empty becomes the document base URI. */
    lxb_dom_node_t *root = lxb_dom_interface_node(d->doc);
    lxb_dom_node_t *n = root;
    while (n) {
        if (is_element(n)) {
            size_t nl; const char *nm = elem_name(n, &nl);
            if (nl == 4 && memcmp(nm, "base", 4) == 0) {
                size_t vl; bool f;
                const lxb_char_t *v = get_attr(n, "href", 4, &vl, &f);
                if (f) {
                    char *rel = strndup((const char *)v, vl);
                    sbuf out = {0};
                    if (rel && resolve_into(d->base_uri, rel, &out) && out.len) {
                        free(d->base_uri);
                        d->base_uri = strdup(out.data);
                        sb_free(&out); free(rel);
                        return 1;
                    }
                    sb_free(&out); free(rel);
                }
            }
        }
        if (n->first_child) n = n->first_child;
        else {
            while (n && n != root && !n->next) n = n->parent;
            if (!n || n == root) break;
            n = n->next;
        }
    }
    return 0;
}

/* ------------------------------------------------------------------------------------------ */
/* text() / ownText() (jsoup Element.text algorithm)                                          */
/* ------------------------------------------------------------------------------------------ */

static const char *const BLOCK_TAGS[] = {   /* jsoup 1.18.3 Tag.isBlock(), sorted for bsearch */
    "address", "applet", "article", "aside", "audio", "blockquote", "body", "button", "canvas",
    "caption", "center", "col", "colgroup", "dd", "details", "dir", "div", "dl", "dt", "fieldset",
    "figcaption", "figure", "footer", "form", "frame", "frameset", "h1", "h2", "h3", "h4", "h5",
    "h6", "head", "header", "hgroup", "hr", "html", "ins", "li", "link", "listing", "main",
    "marquee", "menu", "meta", "nav", "noframes", "noscript", "ol", "p", "plaintext", "pre",
    "script", "section", "style", "table", "tbody", "td", "template", "tfoot", "th", "thead",
    "title", "tr", "ul", "video", "del",
};
static int cmp_name(const void *a, const void *b) { return strcmp((const char *)a, *(const char *const *)b); }
static bool tag_is_block(const char *name, size_t len) {
    char buf[32];
    if (len >= sizeof buf) return false;
    memcpy(buf, name, len); buf[len] = 0;
    /* "del" sorted manually below to keep the table readable */
    if (strcmp(buf, "del") == 0) return true;
    return bsearch(buf, BLOCK_TAGS, sizeof BLOCK_TAGS / sizeof *BLOCK_TAGS - 1, sizeof *BLOCK_TAGS, cmp_name) != NULL;
}
static const char *const KNOWN_TAGS[] = {   /* jsoup 1.18.3 Tag.isKnownTag(), sorted */
    "a",
    "abbr",
    "acronym",
    "address",
    "applet",
    "area",
    "article",
    "aside",
    "audio",
    "b",
    "base",
    "basefont",
    "bdi",
    "bdo",
    "bgsound",
    "big",
    "blockquote",
    "body",
    "br",
    "button",
    "canvas",
    "caption",
    "center",
    "cite",
    "code",
    "col",
    "colgroup",
    "command",
    "data",
    "datalist",
    "dd",
    "del",
    "details",
    "dfn",
    "dir",
    "div",
    "dl",
    "dt",
    "em",
    "embed",
    "fieldset",
    "figcaption",
    "figure",
    "font",
    "footer",
    "form",
    "frame",
    "frameset",
    "h1",
    "h2",
    "h3",
    "h4",
    "h5",
    "h6",
    "head",
    "header",
    "hgroup",
    "hr",
    "html",
    "i",
    "iframe",
    "img",
    "input",
    "ins",
    "kbd",
    "keygen",
    "label",
    "legend",
    "li",
    "link",
    "listing",
    "main",
    "map",
    "mark",
    "marquee",
    "math",
    "menu",
    "menuitem",
    "meta",
    "meter",
    "nav",
    "nobr",
    "noframes",
    "noscript",
    "object",
    "ol",
    "optgroup",
    "option",
    "output",
    "p",
    "param",
    "plaintext",
    "pre",
    "progress",
    "q",
    "rb",
    "rp",
    "rt",
    "rtc",
    "ruby",
    "s",
    "samp",
    "script",
    "section",
    "select",
    "small",
    "source",
    "span",
    "strike",
    "strong",
    "style",
    "sub",
    "summary",
    "sup",
    "svg",
    "table",
    "tbody",
    "td",
    "template",
    "text",
    "textarea",
    "tfoot",
    "th",
    "thead",
    "time",
    "title",
    "tr",
    "track",
    "tt",
    "u",
    "ul",
    "var",
    "video",
    "wbr",
};
static bool node_is_block(lxb_dom_node_t *n);
static bool node_is_known(lxb_dom_node_t *n);
static bool tag_is_known(const char *name, size_t len) {
    char buf[32];
    if (len >= sizeof buf) return false;
    memcpy(buf, name, len); buf[len] = 0;
    return bsearch(buf, KNOWN_TAGS, sizeof KNOWN_TAGS / sizeof *KNOWN_TAGS, sizeof *KNOWN_TAGS, cmp_name) != NULL;
}
/* Text accumulator tail(): a block element is followed by a space when the next sibling is a text
 * node or an element jsoup knows (custom elements such as amp-img do not trigger it). */
static bool tail_wants_space(lxb_dom_node_t *n) {
    if (!n->next || !node_is_block(n)) return false;
    if (is_text(n->next)) return true;
    return is_element(n->next) && node_is_known(n->next);
}
/* jsoup resolves tags per namespace: inside <svg>/<math> only the svg/math roots are block and
 * known; their children (title, text, g, ...) are not. */
static bool node_is_block(lxb_dom_node_t *n) {
    size_t l; const char *nm = elem_name(n, &l);
    if (n->ns == LXB_NS_HTML) return tag_is_block(nm, l);
    return (n->ns == LXB_NS_SVG && l == 3 && !memcmp(nm, "svg", 3)) || (n->ns == LXB_NS_MATH && l == 4 && !memcmp(nm, "math", 4));
}
static bool node_is_known(lxb_dom_node_t *n) {
    size_t l; const char *nm = elem_name(n, &l);
    if (n->ns == LXB_NS_HTML) return tag_is_known(nm, l);
    return node_is_block(n);
}
static bool tag_preserves_ws(const char *name, size_t len) {
    return (len == 3 && !memcmp(name, "pre", 3)) || (len == 5 && !memcmp(name, "title", 5)) ||
           (len == 8 && !memcmp(name, "textarea", 8)) || (len == 9 && !memcmp(name, "plaintext", 9));
}
static bool name_is(lxb_dom_node_t *n, const char *s) {
    size_t l; const char *nm = elem_name(n, &l);
    return l == strlen(s) && memcmp(nm, s, l) == 0;
}
static bool in_data_tag(lxb_dom_node_t *text) {   /* script/style children are DataNode in jsoup */
    lxb_dom_node_t *p = text->parent;
    return p && is_element(p) && (name_is(p, "script") || name_is(p, "style"));
}
static bool preserve_ws(lxb_dom_node_t *node) {   /* Element.preserveWhitespace: self + up to 5 parents */
    int i = 0;
    while (node && is_element(node) && i < 6) {
        size_t l; const char *nm = elem_name(node, &l);
        if (tag_preserves_ws(nm, l)) return true;
        node = node->parent;
        i++;
    }
    return false;
}

static void append_normalised(sbuf *b, const unsigned char *s, size_t n, bool strip_leading) {
    bool last_white = false, reached = false;
    for (size_t i = 0; i < n;) {
        unsigned char c = s[i];
        size_t cl = 1;
        bool ws = c == ' ' || c == '\t' || c == '\n' || c == '\f' || c == '\r';
        bool invisible = false;
        if (c == 0xC2 && i + 1 < n && s[i + 1] == 0xA0) { ws = true; cl = 2; }
        else if (c == 0xC2 && i + 1 < n && s[i + 1] == 0xAD) { invisible = true; cl = 2; }
        else if (c == 0xE2 && i + 2 < n && s[i + 1] == 0x80 && s[i + 2] == 0x8B) { invisible = true; cl = 3; }
        if (ws) {
            if (!((strip_leading && !reached) || last_white)) { sb_putc(b, ' '); last_white = true; }
        } else if (!invisible) {
            if (c >= 0x80) { while (cl < 4 && i + cl < n && (s[i + cl] & 0xC0) == 0x80) cl++; }
            sb_put(b, (const char *)s + i, cl);
            last_white = false;
            reached = true;
        }
        i += cl;
    }
}

static void append_text_node(sbuf *b, lxb_dom_node_t *t) {
    lxb_dom_character_data_t *cd = lxb_dom_interface_character_data(t);
    const unsigned char *s = cd->data.data;
    size_t n = cd->data.length;
    if (!s) return;
    if (preserve_ws(t->parent)) sb_put(b, (const char *)s, n);
    else append_normalised(b, s, n, b->len > 0 && b->data[b->len - 1] == ' ');
}

static bool last_ws(const sbuf *b) { return b->len > 0 && b->data[b->len - 1] == ' '; }

static void trim_java(sbuf *b) {
    size_t s = 0;
    while (s < b->len && (unsigned char)b->data[s] <= ' ') s++;
    size_t e = b->len;
    while (e > s && (unsigned char)b->data[e - 1] <= ' ') e--;
    if (s) memmove(b->data, b->data + s, e - s);
    b->len = e - s;
    if (b->data) b->data[b->len] = 0;
}

static void text_of(lxb_dom_node_t *root, sbuf *b) {
    lxb_dom_node_t *n = root;
    int depth = 0;
    while (n) {
        /* head */
        if (is_text(n)) {
            if (!in_data_tag(n)) append_text_node(b, n);
        } else if (is_element(n)) {
            size_t l; const char *nm = elem_name(n, &l);
            if (b->len > 0 && (node_is_block(n) || (l == 2 && !memcmp(nm, "br", 2))) && !last_ws(b)) sb_putc(b, ' ');
        }
        if (n->first_child && (is_elem_like(n))) { n = n->first_child; depth++; continue; }
        for (;;) {
            if (!(n->next == NULL && depth > 0)) break;
            /* tail(leaf) */
            if (is_element(n) && tail_wants_space(n) && !last_ws(b)) sb_putc(b, ' ');
            n = n->parent;
            depth--;
        }
        if (is_element(n) && tail_wants_space(n) && !last_ws(b)) sb_putc(b, ' ');
        if (n == root) break;
        n = n->next;
    }
    trim_java(b);
}

static void own_text_of(lxb_dom_node_t *el, sbuf *b) {
    for (lxb_dom_node_t *c = el->first_child; c; c = c->next) {
        if (is_text(c)) { if (!in_data_tag(c)) append_text_node(b, c); }
        else if (is_element(c) && name_is(c, "br") && !last_ws(b)) sb_putc(b, ' ');
    }
    trim_java(b);
}

/* ------------------------------------------------------------------------------------------ */
/* Serialisation                                                                              */
/* ------------------------------------------------------------------------------------------ */

static lxb_status_t ser_cb(const lxb_char_t *d, size_t l, void *ctx) {
    sb_put(ctx, (const char *)d, l);
    return ((sbuf *)ctx)->oom ? LXB_STATUS_ERROR_MEMORY_ALLOCATION : LXB_STATUS_OK;
}

static void serialize_inner(lxb_dom_node_t *n, sbuf *b) {
    lxb_html_serialize_deep_cb(n, ser_cb, b);   /* children only (lexbor "deep") */
}
static void serialize_outer(lxb_dom_node_t *n, sbuf *b) {
    if (is_document(n)) { lxb_html_serialize_deep_cb(n, ser_cb, b); return; }
    lxb_html_serialize_tree_cb(n, ser_cb, b);   /* node + descendants (lexbor "tree") */
}

/* ------------------------------------------------------------------------------------------ */
/* Selector rewriting (jsoup syntax -> lexbor syntax + marker attributes)                     */
/* ------------------------------------------------------------------------------------------ */

typedef struct {
    sbuf out;
    mark_def marks[MAX_MARKS];
    int nmarks;
    bool uses_root;
    int err;
    const char *src;
    size_t pos, len;
    int depth;
} rw;

static void rw_free(rw *r) {
    sb_free(&r->out);
    for (int i = 0; i < r->nmarks; i++) free(r->marks[i].needle);
}

static void rw_ws(rw *r) { while (r->pos < r->len && isspace((unsigned char)r->src[r->pos])) r->pos++; }

static bool ident_char(unsigned char c) { return isalnum(c) || c == '-' || c == '_' || c >= 0x80 || c == '\\'; }

/* reads an identifier (handles backslash escapes), unescapes into a malloc'd string */
static char *rw_ident(rw *r) {
    sbuf b = {0};
    while (r->pos < r->len && ident_char((unsigned char)r->src[r->pos])) {
        char ch = r->src[r->pos];
        if (ch == '\\' && r->pos + 1 < r->len) { sb_putc(&b, r->src[r->pos + 1]); r->pos += 2; }
        else { sb_putc(&b, ch); r->pos++; }
    }
    if (!b.data) sb_putc(&b, 0), b.len = 0;
    return b.data;
}

static void emit_quoted(sbuf *o, const char *s, size_t n) {
    sb_putc(o, '"');
    for (size_t i = 0; i < n; i++) {
        if (s[i] == '"' || s[i] == '\\') sb_putc(o, '\\');
        if (s[i] == '\n') { sb_puts(o, "\\a "); continue; }
        sb_putc(o, s[i]);
    }
    sb_putc(o, '"');
}

static char *ascii_lower_dup(const char *s, size_t n) {
    char *d = malloc(n + 1);
    if (!d) return NULL;
    for (size_t i = 0; i < n; i++) d[i] = (char)tolower((unsigned char)s[i]);
    d[n] = 0;
    return d;
}

static void rewrite_group_list(rw *r, char close, bool top);

/* [attr op value] */
static void rw_attr(rw *r) {
    r->pos++;   /* [ */
    rw_ws(r);
    if (r->pos < r->len && r->src[r->pos] == '^') { r->err = SBX_ERR_UNSUPPORTED; return; }   /* [^prefix] */
    size_t ns = r->pos;
    while (r->pos < r->len && !strchr("=~|^$*!] \t", r->src[r->pos])) r->pos++;
    size_t nl = r->pos - ns;
    char *name = ascii_lower_dup(r->src + ns, nl);
    rw_ws(r);
    char op[3] = {0};
    if (r->pos < r->len && r->src[r->pos] != ']') {
        size_t oi = 0;
        if (strchr("~|^$*!", r->src[r->pos])) op[oi++] = r->src[r->pos++];
        if (r->pos < r->len && r->src[r->pos] == '=') op[oi++] = r->src[r->pos++];
        else { r->err = SBX_ERR_SELECTOR; free(name); return; }
    }
    rw_ws(r);
    const char *vs = r->src + r->pos;
    size_t vl = 0;
    if (op[0]) {
        if (r->pos < r->len && (r->src[r->pos] == '"' || r->src[r->pos] == '\'')) {
            char q = r->src[r->pos++];
            vs = r->src + r->pos;
            while (r->pos < r->len && r->src[r->pos] != q) { if (r->src[r->pos] == '\\') r->pos++; r->pos++; }
            vl = (size_t)(r->src + r->pos - vs);
            if (r->pos < r->len) r->pos++;
            rw_ws(r);
        } else {
            /* jsoup reads an unquoted value up to the closing bracket (and trims it) */
            size_t depth = 0, s = r->pos;
            while (r->pos < r->len && !(r->src[r->pos] == ']' && depth == 0)) {
                if (r->src[r->pos] == '[') depth++;
                if (r->src[r->pos] == ']') depth--;
                r->pos++;
            }
            vs = r->src + s; vl = r->pos - s;
            while (vl && isspace((unsigned char)vs[vl - 1])) vl--;
        }
    }
    if (r->pos >= r->len || r->src[r->pos] != ']') { r->err = SBX_ERR_SELECTOR; free(name); return; }
    r->pos++;
    if (!name || !*name) { r->err = SBX_ERR_SELECTOR; free(name); return; }
    if (!op[0]) {
        sb_putc(&r->out, '[');
        sb_puts(&r->out, name);
        sb_putc(&r->out, ']');
    } else if (!strcmp(op, "~=")) {
        r->err = SBX_ERR_UNSUPPORTED;          /* jsoup: regex match */
    } else if (!strcmp(op, "!=")) {
        sb_puts(&r->out, ":not([");
        sb_puts(&r->out, name); sb_putc(&r->out, '=');
        emit_quoted(&r->out, vs, vl);
        sb_puts(&r->out, " i])");
    } else {
        /* jsoup matches attribute values case-insensitively (equalsIgnoreCase / lowerCase.contains) */
        sb_putc(&r->out, '[');
        sb_puts(&r->out, name); sb_puts(&r->out, op);
        emit_quoted(&r->out, vs, vl);
        sb_puts(&r->out, " i]");
    }
    free(name);
}

static int add_mark(rw *r, mark_kind kind, const char *needle, size_t nl, long idx) {
    if (r->nmarks >= MAX_MARKS) { r->err = SBX_ERR_LIMIT_SELECTOR; return -1; }
    mark_def *m = &r->marks[r->nmarks];
    m->kind = kind;
    m->index = idx;
    m->needle = needle ? ascii_lower_dup(needle, nl) : NULL;
    return r->nmarks++;
}

/* normalise(): collapse whitespace like StringUtil.normaliseWhitespace, then trim */
static char *normalise_needle(const char *s, size_t n) {
    sbuf b = {0};
    append_normalised(&b, (const unsigned char *)s, n, true);
    trim_java(&b);
    if (!b.data) { b.data = strdup(""); }
    return b.data;
}

static void rw_pseudo(rw *r) {
    r->pos++;   /* : */
    size_t ns = r->pos;
    while (r->pos < r->len && (isalnum((unsigned char)r->src[r->pos]) || r->src[r->pos] == '-' || r->src[r->pos] == '_')) r->pos++;
    char *name = ascii_lower_dup(r->src + ns, r->pos - ns);
    const char *argstart = NULL;
    size_t arglen = 0;
    bool has_args = false;
    if (r->pos < r->len && r->src[r->pos] == '(') {
        has_args = true;
        size_t d = 0, s = r->pos;
        char q = 0;
        for (; r->pos < r->len; r->pos++) {
            char c = r->src[r->pos];
            if (q) { if (c == '\\') r->pos++; else if (c == q) q = 0; continue; }
            if (c == '"' || c == '\'') q = c;
            else if (c == '(') d++;
            else if (c == ')') { if (--d == 0) break; }
        }
        if (r->pos >= r->len) { r->err = SBX_ERR_SELECTOR; free(name); return; }
        argstart = r->src + s + 1;
        arglen = r->pos - s - 1;
        r->pos++;
    }
    if (!strcmp(name, "contains") || !strcmp(name, "containsown")) {
        if (!has_args) { r->err = SBX_ERR_SELECTOR; free(name); return; }
        const char *a = argstart; size_t al = arglen;
        while (al && isspace((unsigned char)*a)) { a++; al--; }
        while (al && isspace((unsigned char)a[al - 1])) al--;
        if (al >= 2 && (a[0] == '"' || a[0] == '\'') && a[al - 1] == a[0]) { a++; al -= 2; }
        char *norm = normalise_needle(a, al);
        int k = add_mark(r, name[8] ? MK_CONTAINS_OWN : MK_CONTAINS, norm, strlen(norm), 0);
        free(norm);
        if (k >= 0) { char buf[32]; snprintf(buf, sizeof buf, "[" MARK_PREFIX "%d]", k); sb_puts(&r->out, buf); }
    } else if (!strcmp(name, "empty") && !has_args) {   /* jsoup: no children except comments, doctype and blank text */
        int k = add_mark(r, MK_EMPTY, NULL, 0, 0);
        if (k >= 0) { char buf[32]; snprintf(buf, sizeof buf, "[" MARK_PREFIX "%d]", k); sb_puts(&r->out, buf); }
    } else if (!strcmp(name, "eq") || !strcmp(name, "lt") || !strcmp(name, "gt")) {
        char *num = strndup(argstart ? argstart : "", arglen);
        char *end;
        long v = strtol(num, &end, 10);
        bool bad = !has_args || end == num;
        free(num);
        if (bad) { r->err = SBX_ERR_SELECTOR; free(name); return; }
        int k = add_mark(r, name[0] == 'e' ? MK_EQ : (name[0] == 'l' ? MK_LT : MK_GT), NULL, 0, v);
        if (k >= 0) { char buf[32]; snprintf(buf, sizeof buf, "[" MARK_PREFIX "%d]", k); sb_puts(&r->out, buf); }
    } else if (!strncmp(name, "matches", 7) || !strcmp(name, "containsdata") || !strcmp(name, "containswholetext") ||
               !strcmp(name, "containswholeowntext") || !strcmp(name, "matchtext")) {
        r->err = SBX_ERR_UNSUPPORTED;
    } else if (has_args && (!strcmp(name, "has") || !strcmp(name, "not") || !strcmp(name, "is") || !strcmp(name, "where"))) {
        if (++r->depth > 8) { r->err = SBX_ERR_LIMIT_SELECTOR; free(name); return; }
        sb_putc(&r->out, ':'); sb_puts(&r->out, name); sb_putc(&r->out, '(');
        size_t save_pos = r->pos, save_len = r->len;
        const char *save_src = r->src;
        r->src = argstart; r->pos = 0; r->len = arglen;
        rewrite_group_list(r, 0, false);
        r->src = save_src; r->pos = save_pos; r->len = save_len;
        r->depth--;
        sb_putc(&r->out, ')');
    } else {
        sb_putc(&r->out, ':'); sb_puts(&r->out, name);
        if (has_args) { sb_putc(&r->out, '('); sb_put(&r->out, argstart, arglen); sb_putc(&r->out, ')'); }
    }
    free(name);
}

/* Rewrites a comma separated selector list. */
static void rewrite_group_list(rw *r, char close, bool top) {
    (void)close;
    bool group_start = true;
    bool cstart = true;   /* at the start of a compound selector */
    while (r->pos < r->len && !r->err) {
        char c = r->src[r->pos];
        if (group_start) {
            rw_ws(r);
            if (r->pos >= r->len) break;
            c = r->src[r->pos];
            if (top && (c == '>' || c == '+' || c == '~')) { r->uses_root = true; sb_puts(&r->out, "[" ROOT_MARK "]"); sb_putc(&r->out, ' '); }
            group_start = false;
        }
        switch (c) {
        case ',':   /* jsoup tolerates empty groups: ",li" and "li,,li" */
            if (r->out.len > 0 && r->out.data[r->out.len - 1] != ',') sb_putc(&r->out, ',');
            r->pos++; group_start = true; break;
        case '[': rw_attr(r); break;
        case ':':
            /* lexbor 3.0.1 only tests the nearest ancestor when a descendant combinator is led by a
             * bare :is()/:has()/:where(); an explicit universal selector avoids that path. */
            if (cstart) sb_putc(&r->out, '*');
            rw_pseudo(r);
            break;
        case '.': {
            r->pos++;
            char *id = rw_ident(r);
            if (!id || !*id) { r->err = SBX_ERR_SELECTOR; free(id); break; }
            sb_puts(&r->out, "[class~=");           /* jsoup class matching ignores case, even without a doctype */
            emit_quoted(&r->out, id, strlen(id));
            sb_puts(&r->out, " i]");
            free(id);
            break;
        }
        case '#': {
            r->pos++;
            char *id = rw_ident(r);
            if (!id || !*id) { r->err = SBX_ERR_SELECTOR; free(id); break; }
            sb_puts(&r->out, "[id=");                /* case-sensitive, also in quirks mode */
            emit_quoted(&r->out, id, strlen(id));
            sb_putc(&r->out, ']');
            free(id);
            break;
        }
        case '"': case '\'': case '(': case ')': case '{': case '}': case ';': case '\\':
            r->err = SBX_ERR_SELECTOR;
            break;
        default:
            sb_putc(&r->out, c);
            r->pos++;
        }
        cstart = (c == ',' || c == '>' || c == '+' || c == '~' || isspace((unsigned char)c));
    }
    if (r->out.len > 0 && r->out.data[r->out.len - 1] == ',') r->err = SBX_ERR_SELECTOR;   /* "li," is an error in jsoup */
    if (r->out.oom) r->err = SBX_ERR_LIMIT_MEMORY;
}

/* ------------------------------------------------------------------------------------------ */
/* Selection                                                                                  */
/* ------------------------------------------------------------------------------------------ */

static bool contains_str(const char *hay, size_t hl, const char *needle, size_t nl) {
    if (nl == 0) return true;
    if (nl > hl) return false;
    return memmem(hay, hl, needle, nl) != NULL;
}

/* Index among element siblings in O(1): elements are visited in document order, so the previous
 * element sibling has already stored its index in node->user (reserved for library users). */
static long element_sibling_index(lxb_dom_node_t *n) {
    for (lxb_dom_node_t *p = n->prev; p; p = p->prev)
        if (is_element(p)) { n->user = (void *)((uintptr_t)p->user + 1); return (long)(uintptr_t)n->user; }
    n->user = NULL;
    return 0;
}

static void set_mark(lxb_dom_node_t *n, int k, bool on) {
    char nm[24];
    int l = snprintf(nm, sizeof nm, MARK_PREFIX "%d", k);
    if (on) lxb_dom_element_set_attribute(lxb_dom_interface_element(n), (const lxb_char_t *)nm, (size_t)l, (const lxb_char_t *)"", 0);
    else lxb_dom_element_remove_attribute(lxb_dom_interface_element(n), (const lxb_char_t *)nm, (size_t)l);
}

/* Visit all elements under root in document order. */
typedef void (*elem_fn)(lxb_dom_node_t *, void *);
static void walk_elements(lxb_dom_node_t *root, elem_fn fn, void *arg) {
    lxb_dom_node_t *n = root;
    while (n) {
        if (is_element(n)) fn(n, arg);
        if (n->first_child) n = n->first_child;
        else {
            while (n && n != root && !n->next) n = n->parent;
            if (!n || n == root) break;
            n = n->next;
        }
    }
}

/* Strip stale marker attributes (also forged ones coming from the page itself). */
static void clear_marks_fn(lxb_dom_node_t *n, void *arg) {
    (void)arg;
    lxb_dom_attr_t *a = lxb_dom_element_first_attribute(lxb_dom_interface_element(n));
    while (a) {
        lxb_dom_attr_t *next = lxb_dom_element_next_attribute_noi(a);
        size_t l;
        const lxb_char_t *nm = lxb_dom_attr_local_name_noi(a, &l);
        if ((l > 5 && !memcmp(nm, MARK_PREFIX, 5)) || (l == 8 && !memcmp(nm, ROOT_MARK, 8)))
            lxb_dom_element_remove_attribute(lxb_dom_interface_element(n), nm, l);
        a = next;
    }
}

typedef struct { rw *r; sbuf tmp; } mark_arg;

static void apply_marks_fn(lxb_dom_node_t *n, void *argp) {
    mark_arg *ma = argp;
    element_sibling_index(n);   /* fills n->user for :eq/:lt/:gt, once per element */
    for (int k = 0; k < ma->r->nmarks; k++) {
        mark_def *m = &ma->r->marks[k];
        bool hit = false;
        switch (m->kind) {
        case MK_CONTAINS:
        case MK_CONTAINS_OWN: {
            sb_clear(&ma->tmp);
            if (m->kind == MK_CONTAINS) text_of(n, &ma->tmp); else own_text_of(n, &ma->tmp);
            for (size_t i = 0; i < ma->tmp.len; i++) ma->tmp.data[i] = (char)tolower((unsigned char)ma->tmp.data[i]);
            hit = contains_str(ma->tmp.data ? ma->tmp.data : "", ma->tmp.len, m->needle, strlen(m->needle));
            break;
        }
        case MK_EMPTY: {
            hit = true;
            for (lxb_dom_node_t *k2 = n->first_child; k2; k2 = k2->next)
                if (is_text(k2)) {   /* blank text is ignored */
                    lxb_dom_character_data_t *cd = lxb_dom_interface_character_data(k2);
                    for (size_t q = 0; q < cd->data.length; q++) {
                        unsigned char ch = cd->data.data[q];
                        if (!(ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r' || ch == '\f')) { hit = false; break; }
                    }
                    if (!hit) break;
                } else if (k2->type != LXB_DOM_NODE_TYPE_COMMENT && k2->type != LXB_DOM_NODE_TYPE_DOCUMENT_TYPE) { hit = false; break; }
            break;
        }
        case MK_EQ: hit = (long)(uintptr_t)n->user == m->index; break;
        case MK_LT: hit = (long)(uintptr_t)n->user < m->index; break;
        case MK_GT: hit = (long)(uintptr_t)n->user > m->index; break;
        }
        if (hit) set_mark(n, k, true);
    }
}

typedef struct {
    sbx_dom *c;
    lxb_dom_node_t **nodes;
    size_t n, cap;
    size_t limit;     /* stop after this many (1 for selectFirst) */
    int err;
} collect;

static lxb_status_t collect_cb(lxb_dom_node_t *node, lxb_css_selector_specificity_t spec, void *ctx) {
    (void)spec;
    collect *co = ctx;
    if (co->n == co->cap) {
        size_t ncap = co->cap ? co->cap * 2 : 16;
        lxb_dom_node_t **nn = realloc(co->nodes, ncap * sizeof *nn);
        if (!nn) { co->err = SBX_ERR_LIMIT_MEMORY; return LXB_STATUS_STOP; }
        co->nodes = nn; co->cap = ncap;
    }
    co->nodes[co->n++] = node;
    if (co->n >= co->limit) return LXB_STATUS_STOP;
    if (co->n > co->c->lim.max_handles) { co->err = SBX_ERR_LIMIT_HANDLES; return LXB_STATUS_STOP; }
    return LXB_STATUS_OK;
}

static lxb_css_selector_list_t *get_list(sbx_dom *c, const char *sel, size_t sl, int *err) {
    for (int i = 0; i < SEL_CACHE; i++)
        if (c->cache[i].key && !strcmp(c->cache[i].key, sel)) return c->cache[i].list;
    /* One css memory per list: destroy_memory() frees the whole arena the list lives in. */
    lxb_css_memory_t *mem = lxb_css_memory_create();
    if (!mem || lxb_css_memory_init(mem, 64) != LXB_STATUS_OK) {
        if (mem) lxb_css_memory_destroy(mem, true);
        *err = c->limit_hit ? c->limit_hit : SBX_ERR_LIMIT_MEMORY;
        return NULL;
    }
    lxb_css_parser_memory_set(c->css_parser, mem);
    lxb_css_selector_list_t *l = lxb_css_selectors_parse(c->css_parser, (const lxb_char_t *)sel, sl);
    if (c->css_parser->status != LXB_STATUS_OK || !l) {
        c->css_parser->status = LXB_STATUS_OK;
        lxb_css_memory_destroy(mem, true);
        *err = c->limit_hit ? c->limit_hit : SBX_ERR_SELECTOR;
        return NULL;
    }
    sel_entry *e = &c->cache[c->cache_next++ % SEL_CACHE];
    if (e->list) lxb_css_selector_list_destroy_memory(e->list);
    free(e->key);
    e->key = strdup(sel);
    e->list = l;
    return l;
}

static int run_select(sbx_dom *c, sbx_slot *sl, const char *css, size_t limit, collect *co) {
    size_t cl = strlen(css);
    if (cl > c->lim.max_selector_chars) return SBX_ERR_LIMIT_SELECTOR;
    rw r;
    memset(&r, 0, sizeof r);
    r.src = css; r.len = cl;
    rewrite_group_list(&r, 0, true);
    if (r.err) { int e = r.err; rw_free(&r); return e; }
    if (!r.out.data || r.out.len == 0) { rw_free(&r); return SBX_ERR_SELECTOR; }

    int err = 0;
    lxb_css_selector_list_t *list = get_list(c, r.out.data, r.out.len, &err);
    if (!list) { rw_free(&r); return err; }

    lxb_dom_node_t *root = sl->node;
    lxb_dom_node_t *docroot = lxb_dom_interface_node(c->docs[sl->doc].doc);
    bool marked = r.nmarks > 0 || r.uses_root;
    mark_arg ma = {&r, {0}};
    if (marked) {
        walk_elements(docroot, clear_marks_fn, NULL);
        if (r.nmarks) walk_elements(docroot, apply_marks_fn, &ma);
        if (r.uses_root && is_element(root))
            lxb_dom_element_set_attribute(lxb_dom_interface_element(root), (const lxb_char_t *)ROOT_MARK, 8, (const lxb_char_t *)"", 0);
    }
    co->c = c; co->limit = limit;
    lxb_status_t st = LXB_STATUS_OK;
    if (is_element(root)) st = lxb_selectors_match_node(c->selectors, root, list, collect_cb, co);   /* jsoup includes self */
    if (st == LXB_STATUS_OK && co->n < limit && !co->err) st = lxb_selectors_find(c->selectors, root, list, collect_cb, co);
    if (marked) walk_elements(docroot, clear_marks_fn, NULL);
    sb_free(&ma.tmp);
    rw_free(&r);
    if (co->err) return co->err;
    if (c->limit_hit) return c->limit_hit;
    if (st != LXB_STATUS_OK && st != LXB_STATUS_STOP) return SBX_ERR_INTERNAL;
    return SBX_OK;
}

static int32_t* alloc_handles(sbx_dom *c, sbx_slot *base, lxb_dom_node_t **nodes, size_t n, int *rc) {
    uint32_t di = base->doc;
    if (c->live + n > c->lim.max_handles) { *rc = SBX_ERR_LIMIT_HANDLES; return NULL; }
    int32_t *out = malloc((n ? n : 1) * sizeof *out);
    if (!out) { *rc = SBX_ERR_LIMIT_MEMORY; return NULL; }
    for (size_t i = 0; i < n; i++) {
        out[i] = slot_alloc(c, nodes[i], di);
        if (out[i] < 0) {
            *rc = out[i];
            for (size_t j = 0; j < i; j++) sbx_dom_release(c, out[j]);
            free(out);
            return NULL;
        }
    }
    *rc = SBX_OK;
    return out;
}

int sbx_dom_select(sbx_dom *c, int32_t h, const char *css, int32_t **out, size_t *count) {
    *out = NULL; *count = 0;
    c->last_error = 0;
    sbx_slot *sl = slot_get(c, h);
    if (!sl) return SBX_ERR_BAD_HANDLE;
    ENTER(c);
    collect co = {0};
    co.limit = (size_t)-1;
    int rc = run_select(c, sl, css, (size_t)-1, &co);
    if (rc == SBX_OK) {
        int32_t *hs = alloc_handles(c, sl, co.nodes, co.n, &rc);
        if (rc == SBX_OK) { *out = hs; *count = co.n; }
    }
    free(co.nodes);
    LEAVE();
    return rc;
}

int32_t sbx_dom_select_first(sbx_dom *c, int32_t h, const char *css) {
    c->last_error = 0;
    sbx_slot *sl = slot_get(c, h);
    if (!sl) return SBX_ERR_BAD_HANDLE;
    ENTER(c);
    collect co = {0};
    int rc = run_select(c, sl, css, 1, &co);
    int32_t res = rc;
    if (rc == SBX_OK) res = co.n ? slot_alloc(c, co.nodes[0], sl->doc) : 0;
    free(co.nodes);
    LEAVE();
    return res;
}

void sbx_dom_free_array(int32_t *arr) { free(arr); }

/* ------------------------------------------------------------------------------------------ */
/* Accessors                                                                                  */
/* ------------------------------------------------------------------------------------------ */

#define NODE_OR(ret_empty)                                   \
    c->last_error = 0;                                       \
    sbx_slot *sl = slot_get(c, h);                           \
    if (!sl) { c->last_error = SBX_ERR_BAD_HANDLE; *len = 0; return ret_empty; } \
    lxb_dom_node_t *node = sl->node; (void)node

static const char *scratch_result(sbx_dom *c, size_t *len) {
    if (c->scratch.oom) { c->scratch.oom = false; c->last_error = SBX_ERR_LIMIT_MEMORY; sb_clear(&c->scratch); }
    *len = c->scratch.len;
    return c->scratch.data ? c->scratch.data : "";
}

const char *sbx_dom_text(sbx_dom *c, int32_t h, size_t *len) {
    NODE_OR("");
    sb_clear(&c->scratch);
    ENTER(c);
    if (is_elem_like(node)) text_of(node, &c->scratch);
    LEAVE();
    return scratch_result(c, len);
}
const char *sbx_dom_own_text(sbx_dom *c, int32_t h, size_t *len) {
    NODE_OR("");
    sb_clear(&c->scratch);
    ENTER(c);
    if (is_elem_like(node)) own_text_of(node, &c->scratch);
    LEAVE();
    return scratch_result(c, len);
}
const char *sbx_dom_html(sbx_dom *c, int32_t h, size_t *len) {
    NODE_OR("");
    sb_clear(&c->scratch);
    ENTER(c);
    if (is_elem_like(node)) { serialize_inner(node, &c->scratch); trim_java(&c->scratch); }   /* Element.html() trims when pretty-printing */
    LEAVE();
    return scratch_result(c, len);
}
const char *sbx_dom_outer_html(sbx_dom *c, int32_t h, size_t *len) {
    NODE_OR("");
    sb_clear(&c->scratch);
    ENTER(c);
    serialize_outer(node, &c->scratch);
    LEAVE();
    return scratch_result(c, len);
}

static const char *abs_url_impl(sbx_dom *c, sbx_slot *sl, const char *name, size_t nl, size_t *len) {
    size_t vl; bool f;
    const lxb_char_t *v = get_attr(sl->node, name, nl, &vl, &f);
    sb_clear(&c->scratch);
    if (f) {
        char *rel = strndup((const char *)v, vl);
        if (rel) {
            sbuf out = {0};
            if (resolve_into(c->docs[sl->doc].base_uri, rel, &out) && out.data) sb_put(&c->scratch, out.data, out.len);
            else if (has_uri_scheme(rel)) sb_puts(&c->scratch, rel);   /* jsoup: validUriScheme fallback (javascript:, data:, tel:) */
            sb_free(&out);
            free(rel);
        }
    }
    return scratch_result(c, len);
}

const char *sbx_dom_abs_url(sbx_dom *c, int32_t h, const char *name, size_t *len) {
    NODE_OR("");
    return abs_url_impl(c, sl, name, strlen(name), len);
}

const char *sbx_dom_attr(sbx_dom *c, int32_t h, const char *name, size_t *len) {
    NODE_OR("");
    size_t nl = strlen(name);
    if (nl > 4 && !strncasecmp(name, "abs:", 4)) return abs_url_impl(c, sl, name + 4, nl - 4, len);
    bool f; size_t vl;
    const lxb_char_t *v = get_attr(node, name, nl, &vl, &f);
    sb_clear(&c->scratch);
    sb_put(&c->scratch, (const char *)v, vl);
    return scratch_result(c, len);
}

int sbx_dom_has_attr(sbx_dom *c, int32_t h, const char *name) {
    c->last_error = 0;
    sbx_slot *sl = slot_get(c, h);
    if (!sl) { c->last_error = SBX_ERR_BAD_HANDLE; return 0; }
    size_t nl = strlen(name), vl; bool f;
    if (nl > 4 && !strncasecmp(name, "abs:", 4)) { name += 4; nl -= 4; }
    get_attr(sl->node, name, nl, &vl, &f);
    return f;
}

const char *sbx_dom_tag_name(sbx_dom *c, int32_t h, size_t *len) {
    NODE_OR("");
    if (!is_elem_like(node)) { *len = 0; return ""; }
    return elem_name(node, len);   /* points into lexbor's tag hash, stable for the document lifetime */
}
const char *sbx_dom_class_name(sbx_dom *c, int32_t h, size_t *len) {   /* Element.className() trims */
    const char *v = sbx_dom_attr(c, h, "class", len);
    if (c->last_error) return v;
    trim_java(&c->scratch);
    *len = c->scratch.len;
    return c->scratch.data ? c->scratch.data : "";
}
const char *sbx_dom_id(sbx_dom *c, int32_t h, size_t *len) { return sbx_dom_attr(c, h, "id", len); }

int sbx_dom_children(sbx_dom *c, int32_t h, int32_t **out, size_t *count) {
    *out = NULL; *count = 0;
    sbx_slot *sl = slot_get(c, h);
    if (!sl) return SBX_ERR_BAD_HANDLE;
    size_t n = 0;
    for (lxb_dom_node_t *k = sl->node->first_child; k; k = k->next) if (is_element(k)) n++;
    lxb_dom_node_t **nodes = malloc((n ? n : 1) * sizeof *nodes);
    if (!nodes) return SBX_ERR_LIMIT_MEMORY;
    size_t i = 0;
    for (lxb_dom_node_t *k = sl->node->first_child; k; k = k->next) if (is_element(k)) nodes[i++] = k;
    int rc;
    ENTER(c);
    *out = alloc_handles(c, sl, nodes, n, &rc);
    LEAVE();
    free(nodes);
    if (rc == SBX_OK) *count = n;
    return rc;
}

static int32_t relative(sbx_dom *c, int32_t h, lxb_dom_node_t *(*pick)(lxb_dom_node_t *)) {
    c->last_error = 0;
    sbx_slot *sl = slot_get(c, h);
    if (!sl) { c->last_error = SBX_ERR_BAD_HANDLE; return 0; }
    lxb_dom_node_t *t = pick(sl->node);
    if (!t) return 0;
    int32_t r = slot_alloc(c, t, sl->doc);
    if (r < 0) { c->last_error = r; return 0; }
    return r;
}
static lxb_dom_node_t *pick_parent(lxb_dom_node_t *n) {
    lxb_dom_node_t *p = n->parent;
    return p && is_elem_like(p) ? p : NULL;
}
static lxb_dom_node_t *pick_next(lxb_dom_node_t *n) {
    if (!is_element(n)) return NULL;
    for (n = n->next; n; n = n->next) if (is_element(n)) return n;
    return NULL;
}
static lxb_dom_node_t *pick_prev(lxb_dom_node_t *n) {
    if (!is_element(n)) return NULL;
    for (n = n->prev; n; n = n->prev) if (is_element(n)) return n;
    return NULL;
}
int32_t sbx_dom_parent(sbx_dom *c, int32_t h) { return relative(c, h, pick_parent); }
int32_t sbx_dom_next_sibling(sbx_dom *c, int32_t h) { return relative(c, h, pick_next); }
int32_t sbx_dom_prev_sibling(sbx_dom *c, int32_t h) { return relative(c, h, pick_prev); }

void sbx_dom_remove(sbx_dom *c, int32_t h) {
    sbx_slot *sl = slot_get(c, h);
    if (!sl || !is_element(sl->node)) return;
    ENTER(c);
    lxb_dom_node_remove(sl->node);
    LEAVE();
}
