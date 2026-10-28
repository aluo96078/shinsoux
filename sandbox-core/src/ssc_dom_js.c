/*
 * lexbor DOM inside the sandbox, exposed to scripts through the same Jsoup-compatible API as the
 * in-process engines (ADR-0002 §5.2, §17, increment c2). The bootstrap's pure-JS parser stays in
 * the bootstrap but is no longer reachable: DOM_SHIM rebinds Jsoup.parse and bridge.parseHtml.
 *
 * Native functions live on a hidden object that the shim captures and deletes. They accept only
 * numbers (handles) and strings, so no script toString/valueOf runs inside them. Resource limit
 * violations end the invocation uncatchably (LIMIT_MEMORY: the DOM is part of the memory budget,
 * §7); selector syntax errors are ordinary, catchable errors like jsoup's.
 */
#include "ssc_dom_js.h"

#include <stdlib.h>
#include <string.h>

#include "sbx_dom.h"

struct ssc_dom_js {
    sbx_dom *dom;
    bool *limit_flag;
    const bool *open_flag;
    uint32_t epoch; /* bumped by every reset: the shim drops wrappers of released handles */
};

/* The context opaque is an ssc_dom_js_owner (the core's ssc_runtime starts with one). */
static ssc_dom_js *state(JSContext *ctx, JSValueConst this_val) {
    (void)this_val;
    ssc_dom_js_owner *owner = JS_GetContextOpaque(ctx);
    return owner ? owner->dom_js : NULL;
}

static JSValue throw_plain(JSContext *ctx, const char *code) {
    JSValue err = JS_NewError(ctx);
    if (JS_IsException(err)) return err;
    JS_DefinePropertyValueStr(ctx, err, "message", JS_NewString(ctx, code), JS_PROP_C_W_E);
    JS_DefinePropertyValueStr(ctx, err, "code", JS_NewString(ctx, code), JS_PROP_C_W_E);
    return JS_Throw(ctx, err);
}

static JSValue fail(JSContext *ctx, ssc_dom_js *d, int rc) {
    if (SBX_IS_LIMIT_ERROR(rc)) {
        *d->limit_flag = true;
        JSValue err = JS_NewError(ctx);
        if (JS_IsException(err)) return err;
        JS_DefinePropertyValueStr(ctx, err, "message", JS_NewString(ctx, "__ssc_dom_limit"), JS_PROP_C_W_E);
        JS_SetUncatchableError(ctx, err);
        return JS_Throw(ctx, err);
    }
    switch (rc) {
    case SBX_ERR_SELECTOR: return throw_plain(ctx, "SELECTOR_SYNTAX");
    case SBX_ERR_UNSUPPORTED: return throw_plain(ctx, "UNSUPPORTED_SELECTOR");
    case SBX_ERR_BAD_HANDLE: return throw_plain(ctx, "STALE_DOM_NODE");
    default: return throw_plain(ctx, "DOM_INTERNAL");
    }
}

/* Argument guards: numbers and strings only (no conversions that could run script code). */
static bool arg_handle(JSContext *ctx, int argc, JSValueConst *argv, int i, int32_t *h) {
    if (i >= argc || !JS_IsNumber(argv[i])) return false;
    return JS_ToInt32(ctx, h, argv[i]) == 0;
}

static const char *arg_str(JSContext *ctx, int argc, JSValueConst *argv, int i, size_t *len) {
    if (i >= argc || !JS_IsString(argv[i])) return NULL;
    return JS_ToCStringLen(ctx, len, argv[i]);
}

#define GUARD(d)                                                                           \
    ssc_dom_js *d = state(ctx, this_val);                                                  \
    if (!d || !*d->open_flag) return throw_plain(ctx, "DOM_CLOSED")

static JSValue js_parse(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    GUARD(d);
    size_t hlen = 0, blen = 0;
    const char *html = arg_str(ctx, argc, argv, 0, &hlen);
    if (!html) return JS_ThrowTypeError(ctx, "html must be a string");
    const char *base = arg_str(ctx, argc, argv, 1, &blen);
    int32_t h = sbx_dom_parse(d->dom, html, hlen, base ? base : "");
    JS_FreeCString(ctx, html);
    if (base) JS_FreeCString(ctx, base);
    if (h < 0) return fail(ctx, d, h);
    return JS_NewInt32(ctx, h);
}

static JSValue handles_array(JSContext *ctx, int32_t *hs, size_t n) {
    JSValue arr = JS_NewArray(ctx);
    if (JS_IsException(arr)) return arr;
    for (size_t i = 0; i < n; i++) JS_DefinePropertyValueUint32(ctx, arr, (uint32_t)i, JS_NewInt32(ctx, hs[i]), JS_PROP_C_W_E);
    return arr;
}

static JSValue js_select(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    GUARD(d);
    int32_t h;
    size_t len;
    if (!arg_handle(ctx, argc, argv, 0, &h)) return JS_ThrowTypeError(ctx, "bad node");
    const char *css = arg_str(ctx, argc, argv, 1, &len);
    if (!css) return JS_ThrowTypeError(ctx, "selector must be a string");
    if (memchr(css, 0, len)) { JS_FreeCString(ctx, css); return fail(ctx, d, SBX_ERR_SELECTOR); }
    int32_t *hs = NULL;
    size_t n = 0;
    int rc = sbx_dom_select(d->dom, h, css, &hs, &n);
    JS_FreeCString(ctx, css);
    if (rc < 0) return fail(ctx, d, rc);
    JSValue arr = handles_array(ctx, hs, n);
    sbx_dom_free_array(hs);
    return arr;
}

static JSValue js_select_first(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    GUARD(d);
    int32_t h;
    size_t len;
    if (!arg_handle(ctx, argc, argv, 0, &h)) return JS_ThrowTypeError(ctx, "bad node");
    const char *css = arg_str(ctx, argc, argv, 1, &len);
    if (!css) return JS_ThrowTypeError(ctx, "selector must be a string");
    if (memchr(css, 0, len)) { JS_FreeCString(ctx, css); return fail(ctx, d, SBX_ERR_SELECTOR); }
    int32_t r = sbx_dom_select_first(d->dom, h, css);
    JS_FreeCString(ctx, css);
    if (r < 0) return fail(ctx, d, r);
    return JS_NewInt32(ctx, r);
}

static JSValue js_children(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    GUARD(d);
    int32_t h;
    if (!arg_handle(ctx, argc, argv, 0, &h)) return JS_ThrowTypeError(ctx, "bad node");
    int32_t *hs = NULL;
    size_t n = 0;
    int rc = sbx_dom_children(d->dom, h, &hs, &n);
    if (rc < 0) return fail(ctx, d, rc);
    JSValue arr = handles_array(ctx, hs, n);
    sbx_dom_free_array(hs);
    return arr;
}

/* String accessors: (node) or (node, name). NULL from sbx_dom means an error (see last_error). */
typedef const char *(*str0)(sbx_dom *, int32_t, size_t *);
typedef const char *(*str1)(sbx_dom *, int32_t, const char *, size_t *);

/* The accessors report failures (a stale handle, memory) through last_error, not the pointer. */
static JSValue string_result(JSContext *ctx, ssc_dom_js *d, const char *p, size_t len) {
    int rc = sbx_dom_last_error(d->dom);
    if (rc < 0) return fail(ctx, d, rc);
    if (!p) return fail(ctx, d, SBX_ERR_INTERNAL);
    return JS_NewStringLen(ctx, p, len);
}

static JSValue call_str0(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv, str0 fn) {
    GUARD(d);
    int32_t h;
    if (!arg_handle(ctx, argc, argv, 0, &h)) return JS_ThrowTypeError(ctx, "bad node");
    size_t len = 0;
    const char *p = fn(d->dom, h, &len);
    return string_result(ctx, d, p, len);
}

static JSValue call_str1(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv, str1 fn) {
    GUARD(d);
    int32_t h;
    size_t nlen;
    if (!arg_handle(ctx, argc, argv, 0, &h)) return JS_ThrowTypeError(ctx, "bad node");
    const char *name = arg_str(ctx, argc, argv, 1, &nlen);
    if (!name) return JS_ThrowTypeError(ctx, "name must be a string");
    size_t len = 0;
    const char *p = memchr(name, 0, nlen) ? "" : fn(d->dom, h, name, &len);
    JSValue r = string_result(ctx, d, p, len);
    JS_FreeCString(ctx, name);
    return r;
}

#define STR0(js, fn) static JSValue js(JSContext *c, JSValueConst t, int a, JSValueConst *v) { return call_str0(c, t, a, v, fn); }
#define STR1(js, fn) static JSValue js(JSContext *c, JSValueConst t, int a, JSValueConst *v) { return call_str1(c, t, a, v, fn); }
STR0(js_text, sbx_dom_text)
STR0(js_own_text, sbx_dom_own_text)
STR0(js_html, sbx_dom_html)
STR0(js_outer_html, sbx_dom_outer_html)
STR0(js_tag_name, sbx_dom_tag_name)
STR0(js_class_name, sbx_dom_class_name)
STR0(js_id, sbx_dom_id)
STR1(js_attr, sbx_dom_attr)
STR1(js_abs_url, sbx_dom_abs_url)

static JSValue js_has_attr(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    GUARD(d);
    int32_t h;
    size_t nlen;
    if (!arg_handle(ctx, argc, argv, 0, &h)) return JS_ThrowTypeError(ctx, "bad node");
    const char *name = arg_str(ctx, argc, argv, 1, &nlen);
    if (!name) return JS_ThrowTypeError(ctx, "name must be a string");
    int r = memchr(name, 0, nlen) ? 0 : sbx_dom_has_attr(d->dom, h, name);
    JS_FreeCString(ctx, name);
    if (r < 0) return fail(ctx, d, r);
    if (sbx_dom_last_error(d->dom) < 0) return fail(ctx, d, sbx_dom_last_error(d->dom));
    return JS_NewBool(ctx, r > 0);
}

typedef int32_t (*rel_fn)(sbx_dom *, int32_t);

static JSValue call_rel(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv, rel_fn fn) {
    GUARD(d);
    int32_t h;
    if (!arg_handle(ctx, argc, argv, 0, &h)) return JS_ThrowTypeError(ctx, "bad node");
    int32_t r = fn(d->dom, h);
    if (r < 0) return fail(ctx, d, r);
    if (sbx_dom_last_error(d->dom) < 0) return fail(ctx, d, sbx_dom_last_error(d->dom));
    return JS_NewInt32(ctx, r);
}

static JSValue js_parent(JSContext *c, JSValueConst t, int a, JSValueConst *v) { return call_rel(c, t, a, v, sbx_dom_parent); }
static JSValue js_next(JSContext *c, JSValueConst t, int a, JSValueConst *v) { return call_rel(c, t, a, v, sbx_dom_next_sibling); }
static JSValue js_prev(JSContext *c, JSValueConst t, int a, JSValueConst *v) { return call_rel(c, t, a, v, sbx_dom_prev_sibling); }

static JSValue js_remove(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    GUARD(d);
    int32_t h;
    if (!arg_handle(ctx, argc, argv, 0, &h)) return JS_ThrowTypeError(ctx, "bad node");
    sbx_dom_remove(d->dom, h);
    return JS_UNDEFINED;
}

static JSValue js_epoch(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    (void)argc; (void)argv;
    ssc_dom_js *d = state(ctx, this_val);
    return JS_NewUint32(ctx, d ? d->epoch : 0);
}

static const JSCFunctionListEntry DOM_FUNCS[] = {
    JS_CFUNC_DEF("epoch", 0, js_epoch),
    JS_CFUNC_DEF("parse", 2, js_parse),
    JS_CFUNC_DEF("select", 2, js_select),
    JS_CFUNC_DEF("selectFirst", 2, js_select_first),
    JS_CFUNC_DEF("children", 1, js_children),
    JS_CFUNC_DEF("text", 1, js_text),
    JS_CFUNC_DEF("ownText", 1, js_own_text),
    JS_CFUNC_DEF("html", 1, js_html),
    JS_CFUNC_DEF("outerHtml", 1, js_outer_html),
    JS_CFUNC_DEF("tagName", 1, js_tag_name),
    JS_CFUNC_DEF("className", 1, js_class_name),
    JS_CFUNC_DEF("id", 1, js_id),
    JS_CFUNC_DEF("attr", 2, js_attr),
    JS_CFUNC_DEF("absUrl", 2, js_abs_url),
    JS_CFUNC_DEF("hasAttr", 2, js_has_attr),
    JS_CFUNC_DEF("parent", 1, js_parent),
    JS_CFUNC_DEF("next", 1, js_next),
    JS_CFUNC_DEF("prev", 1, js_prev),
    JS_CFUNC_DEF("remove", 1, js_remove),
};

/*
 * Jsoup-compatible wrappers over native handles: the same methods, names and return shapes as
 * the in-process engines' DOM (IosJavaScriptBootstrap.kt). One wrapper per handle (cached), and
 * one handle per node, so identity comparisons behave as before. release()/releaseAll() stay
 * no-op hints: every handle is released when the invocation ends.
 */
const char SSC_DOM_SHIM[] =
    /* Runs before any plugin script: the intrinsics captured here (bound, so later changes to
     * WeakMap/Map/Function prototypes cannot intercept them) are pristine. */
    "(function(g){var D=g.__ssc_dom;delete g.__ssc_dom;"
    "var call=Function.prototype.call,wmGet=call.bind(WeakMap.prototype.get),wmSet=call.bind(WeakMap.prototype.set),"
    "mGet=call.bind(Map.prototype.get),mSet=call.bind(Map.prototype.set),mkMap=function(){return new Map();},"
    "create=Object.create;var cache=mkMap(),epoch=-1;"
    "function norm(v){return String(v||'').replace(/\\s+/g,' ').trim();}"
    /* Handle and epoch live in a closure-private WeakMap, so scripts can neither read nor forge
     * them; every call checks the epoch, so a node kept across invocations throws STALE_DOM_NODE
     * instead of reaching a recycled handle, and a forged receiver is a TypeError. */
    "var priv=new WeakMap();var P={};"
    /* No constructor is reachable: wrappers come only from wrap(). */
    "function stale(){var e=new Error('STALE_DOM_NODE');e.code='STALE_DOM_NODE';throw e;}"
    "function H(e){var p=wmGet(priv,e);if(!p)throw new TypeError('not a DOM node');if(p.e!==D.epoch())stale();return p.h;}"
    "function wrap(h){if(!h)return null;var now=D.epoch();if(now!==epoch){cache=mkMap();epoch=now;}"
    "var e=mGet(cache,h);if(!e){e=create(P);wmSet(priv,e,{h:h,e:now});mSet(cache,h,e);}return e;}"
    "function L(values){this._arr=values||[];this.length=this._arr.length;for(var i=0;i<this.length;i++)this[i]=this._arr[i];}"
    "function list(hs){var a=[];for(var i=0;i<hs.length;i++)a.push(wrap(hs[i]));return new L(a);}"
    "P.tagName=function(){return D.tagName(H(this));};"
    "P.attr=function(n){return D.attr(H(this),String(n));};"
    "P.hasAttr=function(n){return D.hasAttr(H(this),String(n));};"
    "P.id=function(){return D.id(H(this));};"
    "P.className=function(){return D.className(H(this));};"
    "P.children=function(){return list(D.children(H(this)));};"
    "P.parent=function(){return wrap(D.parent(H(this)));};"
    "P.nextElementSibling=function(){return wrap(D.next(H(this)));};"
    "P.previousElementSibling=function(){return wrap(D.prev(H(this)));};"
    "P.remove=function(){D.remove(H(this));};"
    "P.release=function(){};"
    "P.ownText=function(){return D.ownText(H(this));};"
    "P.text=function(){return D.text(H(this));};"
    "P.html=function(){return D.html(H(this));};"
    "P.outerHtml=function(){return D.outerHtml(H(this));};"
    "P.absUrl=function(n){return D.absUrl(H(this),String(n));};"
    "P.select=function(s){return list(D.select(H(this),String(s)));};"
    "P.selectFirst=function(s){return wrap(D.selectFirst(H(this),String(s)));};"
    "P.getElementsByTag=function(t){return this.select(String(t));};"
    "P.getElementById=function(id){return this.selectFirst('#'+String(id));};"
    "P.getElementsByClass=function(n){return this.select('.'+String(n));};"
    "P.toString=function(){return this.outerHtml();};"
    "var Q=L.prototype;"
    "Q.get=function(i){return i>=0&&i<this.length?this._arr[i]:null;};"
    "Q.first=function(){return this.get(0);};Q.last=function(){return this.get(this.length-1);};"
    "Q.size=function(){return this.length;};Q.isEmpty=function(){return this.length===0;};"
    "Q.forEach=function(fn){for(var i=0;i<this.length;i++)fn(this._arr[i],i);};"
    "Q.map=function(fn){var out=[];for(var i=0;i<this.length;i++)out.push(fn(this._arr[i],i));return out;};"
    "Q.filter=function(fn){var out=[];for(var i=0;i<this.length;i++)if(fn(this._arr[i],i))out.push(this._arr[i]);return new L(out);};"
    "Q.text=function(){return norm(this.map(function(e){return e.text();}).join(' '));};"
    "Q.attr=function(n){return this.length?this._arr[0].attr(n):'';};"
    "Q.hasAttr=function(n){return this.length?this._arr[0].hasAttr(n):false;};"
    "Q.html=function(){return this.length?this._arr[0].html():'';};"
    "Q.select=function(s){var out=[];this.forEach(function(e){e.select(s).forEach(function(x){if(out.indexOf(x)<0)out.push(x);});});return new L(out);};"
    "Q.eachAttr=function(n){return this.map(function(e){return e.attr(n);});};"
    "Q.eachText=function(){return this.map(function(e){return e.text();});};"
    "Q.releaseAll=function(){};"
    "function parse(html,base){return wrap(D.parse(String(html||''),base?String(base):''));}"
    "g.Jsoup={parse:function(html,baseUri){return parse(html,baseUri);}};"
    "if(g.bridge)g.bridge.parseHtml=function(html,selector){return parse(html,'').select(String(selector)).map(function(e){"
    "return{text:e.text(),html:e.html(),outerHtml:e.outerHtml(),attr_href:e.attr('href'),attr_src:e.attr('src'),tagName:e.tagName()};});};"
    "})(globalThis);";
const size_t SSC_DOM_SHIM_LEN = sizeof SSC_DOM_SHIM - 1;

ssc_dom_js *ssc_dom_js_new(JSContext *ctx, uint32_t max_nodes, uint32_t max_depth, bool *limit_flag, const bool *open_flag) {
    ssc_dom_js *d = calloc(1, sizeof *d);
    if (!d) return NULL;
    sbx_dom_limits lim;
    sbx_dom_limits_default(&lim);
    /* Host-owned (Init limits, from PluginExecutionLimits); the module's defaults otherwise. */
    if (max_nodes) lim.max_nodes = max_nodes;
    if (max_depth) lim.max_depth = max_depth;
    d->dom = sbx_dom_new(&lim);
    if (!d->dom) { free(d); return NULL; }
    d->limit_flag = limit_flag;
    d->open_flag = open_flag;
    JSValue global = JS_GetGlobalObject(ctx);
    JSValue obj = JS_NewObject(ctx);
    JS_SetPropertyFunctionList(ctx, obj, DOM_FUNCS, sizeof DOM_FUNCS / sizeof DOM_FUNCS[0]);
    JS_SetPropertyStr(ctx, global, "__ssc_dom", obj);
    JS_FreeValue(ctx, global);
    return d;
}

void ssc_dom_js_reset(ssc_dom_js *d) {
    if (!d) return;
    sbx_dom_release_all(d->dom);
    d->epoch++;
}

void ssc_dom_js_free(ssc_dom_js *d) {
    if (!d) return;
    sbx_dom_free(d->dom);
    free(d);
}

void ssc_dom_js_set_test_limits(ssc_dom_js *d, uint32_t max_handles, uint32_t max_input_bytes, size_t max_memory_bytes) {
    sbx_dom_set_limits_for_tests(d->dom, max_handles, max_input_bytes, max_memory_bytes);
}
