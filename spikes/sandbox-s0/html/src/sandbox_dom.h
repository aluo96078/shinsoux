/*
 * sandbox_dom: Jsoup-compatible DOM operations on top of lexbor (ADR-0002 section 5.2 evaluation).
 *
 * Nodes are integer handles (0 = null).  All limits are enforced inside this module, so the
 * Host never has to trust a counter coming from the plugin.  Strings returned by the sbx_dom_*
 * accessors point into a per-context scratch buffer and stay valid until the next call on the
 * same context; the caller copies them (for QuickJS: JS_NewStringLen).
 *
 * Threading: one context per sandbox instance; contexts must not be used concurrently.  The
 * lexbor allocator hook is process-global but routes every allocation to the context that is
 * currently executing an API call.
 */
#ifndef SANDBOX_DOM_H
#define SANDBOX_DOM_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct sbx_dom sbx_dom;

/* Mirrors PluginExecutionLimits (maxDomInputBytes, maxDomHandles, maxDomNodes, maxDomDepth,
 * maxSelectorChars) plus a byte budget for everything lexbor allocates. */
typedef struct {
    uint32_t max_input_bytes;     /* default 4 MiB */
    uint32_t max_nodes;           /* per document, default 65 536 */
    uint32_t max_depth;           /* open-element depth, default 256 */
    uint32_t max_handles;         /* live handles, default 4 096 (<= 65 535) */
    uint32_t max_selector_chars;  /* default 4 096 */
    size_t max_memory_bytes;      /* lexbor + table memory, default 64 MiB */
} sbx_dom_limits;

enum {
    SBX_OK = 0,
    SBX_ERR_BAD_HANDLE = -1,
    SBX_ERR_LIMIT_INPUT = -2,
    SBX_ERR_LIMIT_NODES = -3,
    SBX_ERR_LIMIT_DEPTH = -4,
    SBX_ERR_LIMIT_HANDLES = -5,
    SBX_ERR_LIMIT_SELECTOR = -6,
    SBX_ERR_LIMIT_MEMORY = -7,
    SBX_ERR_SELECTOR = -8,        /* syntax error (jsoup: SelectorParseException) */
    SBX_ERR_UNSUPPORTED = -9,     /* valid jsoup selector this module cannot evaluate */
    SBX_ERR_INTERNAL = -10
};

/* True for errors that must poison the invocation (PluginResourceLimitException). */
#define SBX_IS_LIMIT_ERROR(rc) ((rc) <= SBX_ERR_LIMIT_INPUT && (rc) >= SBX_ERR_LIMIT_MEMORY)

void sbx_dom_limits_default(sbx_dom_limits *limits);
sbx_dom *sbx_dom_new(const sbx_dom_limits *limits);
void sbx_dom_free(sbx_dom *ctx);

/* Jsoup.parse(html, baseUri).  Returns a Document handle (> 0) or a negative SBX_ERR_*.
 * base_uri may be NULL/empty.  A <base href> in the document overrides it like jsoup does. */
int32_t sbx_dom_parse(sbx_dom *ctx, const char *html, size_t len, const char *base_uri);

/* Element.select(css): appends handles to *out (caller frees with sbx_dom_free_array).
 * Includes the root element itself when it matches, like jsoup. */
int sbx_dom_select(sbx_dom *ctx, int32_t h, const char *css, int32_t **out, size_t *count);
/* Element.selectFirst(css): returns a handle, 0 for no match, or a negative error. */
int32_t sbx_dom_select_first(sbx_dom *ctx, int32_t h, const char *css);
void sbx_dom_free_array(int32_t *arr);

const char *sbx_dom_text(sbx_dom *ctx, int32_t h, size_t *len);
const char *sbx_dom_own_text(sbx_dom *ctx, int32_t h, size_t *len);
const char *sbx_dom_html(sbx_dom *ctx, int32_t h, size_t *len);
const char *sbx_dom_outer_html(sbx_dom *ctx, int32_t h, size_t *len);
const char *sbx_dom_attr(sbx_dom *ctx, int32_t h, const char *name, size_t *len); /* supports "abs:" */
int sbx_dom_has_attr(sbx_dom *ctx, int32_t h, const char *name);
const char *sbx_dom_abs_url(sbx_dom *ctx, int32_t h, const char *name, size_t *len);
const char *sbx_dom_tag_name(sbx_dom *ctx, int32_t h, size_t *len);
const char *sbx_dom_class_name(sbx_dom *ctx, int32_t h, size_t *len);
const char *sbx_dom_id(sbx_dom *ctx, int32_t h, size_t *len);

int sbx_dom_children(sbx_dom *ctx, int32_t h, int32_t **out, size_t *count);
int32_t sbx_dom_parent(sbx_dom *ctx, int32_t h);
int32_t sbx_dom_next_sibling(sbx_dom *ctx, int32_t h);   /* nextElementSibling */
int32_t sbx_dom_prev_sibling(sbx_dom *ctx, int32_t h);   /* previousElementSibling */
void sbx_dom_remove(sbx_dom *ctx, int32_t h);
void sbx_dom_release(sbx_dom *ctx, int32_t h);
void sbx_dom_release_all(sbx_dom *ctx);

/* Last negative status produced by a string accessor / parent / sibling call. */
int sbx_dom_last_error(sbx_dom *ctx);

typedef struct {
    uint32_t live_handles;
    uint32_t live_documents;
    size_t memory_bytes;
    size_t memory_peak_bytes;
    uint32_t last_parse_nodes;   /* nodes (elements, text, comments...) of the most recent parse */
    uint32_t last_parse_depth;
} sbx_dom_stats;
void sbx_dom_get_stats(sbx_dom *ctx, sbx_dom_stats *stats);

/* Exposed for tests. */
size_t sbx_dom_resolve_url(const char *base, const char *rel, char *out, size_t cap);

#ifdef __cplusplus
}
#endif
#endif
