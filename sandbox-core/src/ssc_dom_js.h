/* lexbor DOM bindings for the sandbox core (ADR-0002 §5.2, §17). */
#ifndef SSC_DOM_JS_H
#define SSC_DOM_JS_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "quickjs.h"

typedef struct ssc_dom_js ssc_dom_js;

/* The JSContext opaque must point at a struct that starts with this (ssc_runtime does). */
typedef struct {
    ssc_dom_js *dom_js;
} ssc_dom_js_owner;

/*
 * Creates the DOM context and installs the hidden native object `__ssc_dom` on the global object;
 * SSC_DOM_SHIM (evaluated after the bootstrap) captures and deletes it. DOM calls work only while
 * *open_flag is true; limit violations set *limit_flag and throw uncatchably.
 */
ssc_dom_js *ssc_dom_js_new(JSContext *ctx, uint32_t max_nodes, uint32_t max_depth, bool *limit_flag, const bool *open_flag);

/* Releases every document and handle (end of each invocation). */
void ssc_dom_js_reset(ssc_dom_js *d);
void ssc_dom_js_free(ssc_dom_js *d);

/* Tests only (parity runs over large real pages). */
void ssc_dom_js_set_test_limits(ssc_dom_js *d, uint32_t max_handles, uint32_t max_input_bytes, size_t max_memory_bytes);

extern const char SSC_DOM_SHIM[];
extern const size_t SSC_DOM_SHIM_LEN;

#endif
