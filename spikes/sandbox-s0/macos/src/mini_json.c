#include "mini_json.h"
#include <ctype.h>
#include <stdlib.h>
#include <string.h>

#define MAX_DEPTH 64

static const char *skip_ws(const char *p, const char *e) {
    while (p < e && (*p == ' ' || *p == '\t' || *p == '\n' || *p == '\r')) p++;
    return p;
}

static const char *scan_string(const char *p, const char *e) {
    if (p >= e || *p != '"') return NULL;
    for (p++; p < e; p++) {
        if (*p == '\\') { p++; if (p >= e) return NULL; }
        else if (*p == '"') return p + 1;
        else if ((unsigned char)*p < 0x20) return NULL;
    }
    return NULL;
}

static const char *scan_value(const char *p, const char *e, int depth) {
    if (depth > MAX_DEPTH) return NULL;
    p = skip_ws(p, e);
    if (p >= e) return NULL;
    if (*p == '"') return scan_string(p, e);
    if (*p == '{' || *p == '[') {
        char close = *p == '{' ? '}' : ']';
        int obj = *p == '{';
        p = skip_ws(p + 1, e);
        if (p < e && *p == close) return p + 1;
        for (;;) {
            if (obj) {
                p = scan_string(skip_ws(p, e), e);
                if (!p) return NULL;
                p = skip_ws(p, e);
                if (p >= e || *p != ':') return NULL;
                p++;
            }
            p = scan_value(p, e, depth + 1);
            if (!p) return NULL;
            p = skip_ws(p, e);
            if (p >= e) return NULL;
            if (*p == ',') { p++; continue; }
            if (*p == close) return p + 1;
            return NULL;
        }
    }
    const char *start = p;
    while (p < e && (isalnum((unsigned char)*p) || *p == '-' || *p == '+' || *p == '.')) p++;
    if (p == start) return NULL;
    size_t n = (size_t)(p - start);
    if ((n == 4 && !memcmp(start, "true", 4)) || (n == 5 && !memcmp(start, "false", 5)) || (n == 4 && !memcmp(start, "null", 4)))
        return p;
    char *end;
    char tmp[64];
    if (n >= sizeof tmp) return NULL;
    memcpy(tmp, start, n); tmp[n] = 0;
    strtod(tmp, &end);
    return *end ? NULL : p;
}

int mj_validate(const char *s, size_t n) {
    const char *e = s + n;
    const char *p = scan_value(s, e, 0);
    return p && skip_ws(p, e) == e;
}

int mj_get(span obj, const char *key, span *v) {
    const char *e = obj.p + obj.n;
    const char *p = skip_ws(obj.p, e);
    if (p >= e || *p != '{') return 0;
    p = skip_ws(p + 1, e);
    size_t klen = strlen(key);
    while (p < e && *p != '}') {
        const char *ks = p;
        const char *ke = scan_string(p, e);
        if (!ke) return 0;
        p = skip_ws(ke, e);
        if (p >= e || *p != ':') return 0;
        const char *vs = skip_ws(p + 1, e);
        const char *ve = scan_value(vs, e, 1);
        if (!ve) return 0;
        /* Keys never contain escapes in our protocol; compare raw. */
        if ((size_t)(ke - ks) == klen + 2 && !memcmp(ks + 1, key, klen)) { v->p = vs; v->n = (size_t)(ve - vs); return 1; }
        p = skip_ws(ve, e);
        if (p < e && *p == ',') p = skip_ws(p + 1, e);
    }
    return 0;
}

static int hexv(char c) { return c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1; }

static int put_utf8(char *o, uint32_t c) {
    if (c < 0x80) { o[0] = (char)c; return 1; }
    if (c < 0x800) { o[0] = (char)(0xC0 | (c >> 6)); o[1] = (char)(0x80 | (c & 63)); return 2; }
    if (c < 0x10000) { o[0] = (char)(0xE0 | (c >> 12)); o[1] = (char)(0x80 | ((c >> 6) & 63)); o[2] = (char)(0x80 | (c & 63)); return 3; }
    o[0] = (char)(0xF0 | (c >> 18)); o[1] = (char)(0x80 | ((c >> 12) & 63)); o[2] = (char)(0x80 | ((c >> 6) & 63)); o[3] = (char)(0x80 | (c & 63)); return 4;
}

char *mj_string(span v, size_t *len) {
    if (v.n < 2 || v.p[0] != '"') return NULL;
    char *out = malloc(v.n + 1);
    if (!out) return NULL;
    size_t o = 0;
    const char *p = v.p + 1, *e = v.p + v.n - 1;
    while (p < e) {
        if (*p != '\\') { out[o++] = *p++; continue; }
        p++;
        switch (*p) {
        case 'n': out[o++] = '\n'; p++; break;
        case 't': out[o++] = '\t'; p++; break;
        case 'r': out[o++] = '\r'; p++; break;
        case 'b': out[o++] = '\b'; p++; break;
        case 'f': out[o++] = '\f'; p++; break;
        case 'u': {
            uint32_t c = 0;
            for (int i = 1; i <= 4; i++) { int h = p + i < e ? hexv(p[i]) : -1; if (h < 0) { free(out); return NULL; } c = c * 16 + (uint32_t)h; }
            p += 5;
            if (c >= 0xD800 && c < 0xDC00 && p + 5 < e + 0 && p[0] == '\\' && p[1] == 'u') {
                uint32_t d = 0;
                for (int i = 2; i <= 5; i++) { int h = hexv(p[i]); if (h < 0) { free(out); return NULL; } d = d * 16 + (uint32_t)h; }
                if (d >= 0xDC00 && d < 0xE000) { c = 0x10000 + ((c - 0xD800) << 10) + (d - 0xDC00); p += 6; }
            }
            o += (size_t)put_utf8(out + o, c);
            break;
        }
        default: out[o++] = *p++; break; /* " \ / */
        }
    }
    out[o] = 0;
    if (len) *len = o;
    return out;
}

int mj_u64(span v, uint64_t *out) {
    if (!v.n || v.n > 20) return 0;
    uint64_t r = 0;
    for (size_t i = 0; i < v.n; i++) { if (v.p[i] < '0' || v.p[i] > '9') return 0; r = r * 10 + (uint64_t)(v.p[i] - '0'); }
    *out = r;
    return 1;
}
