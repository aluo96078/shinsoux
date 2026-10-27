#include "ssc_frames.h"

#include <stdlib.h>
#include <string.h>

/* ---- output buffer ---------------------------------------------------------------------- */

void ssc_buf_free(ssc_buf *b) {
    free(b->buf);
    b->buf = NULL;
    b->len = b->cap = 0;
}

static void put(ssc_buf *b, const void *p, size_t n) {
    if (b->failed) return;
    if (b->len + n < b->len) { b->failed = 1; return; }
    if (b->len + n > b->cap) {
        size_t cap = b->cap ? b->cap : 64;
        while (cap < b->len + n) {
            if (cap > SIZE_MAX / 2) { cap = b->len + n; break; }
            cap *= 2;
        }
        uint8_t *nb = realloc(b->buf, cap);
        if (!nb) { b->failed = 1; return; }
        b->buf = nb;
        b->cap = cap;
    }
    memcpy(b->buf + b->len, p, n);
    b->len += n;
}

static void put_byte(ssc_buf *b, uint8_t v) { put(b, &v, 1); }

/* Shortest-form head: the only encoding readers accept. */
static void head(ssc_buf *b, unsigned major, uint64_t v) {
    uint8_t m = (uint8_t)(major << 5);
    uint8_t tmp[9];
    size_t n;
    if (v < 24) { tmp[0] = (uint8_t)(m | v); n = 1; }
    else if (v <= 0xff) { tmp[0] = m | 24; tmp[1] = (uint8_t)v; n = 2; }
    else if (v <= 0xffff) { tmp[0] = m | 25; n = 3; for (int i = 0; i < 2; i++) tmp[1 + i] = (uint8_t)(v >> (8 * (1 - i))); }
    else if (v <= 0xffffffffu) { tmp[0] = m | 26; n = 5; for (int i = 0; i < 4; i++) tmp[1 + i] = (uint8_t)(v >> (8 * (3 - i))); }
    else { tmp[0] = m | 27; n = 9; for (int i = 0; i < 8; i++) tmp[1 + i] = (uint8_t)(v >> (8 * (7 - i))); }
    put(b, tmp, n);
}

#define MAJOR_UINT 0u
#define MAJOR_BYTES 2u
#define MAJOR_TEXT 3u
#define MAJOR_ARRAY 4u
#define S_FALSE 0xf4
#define S_TRUE 0xf5
#define S_NULL 0xf6

static void w_uint(ssc_buf *b, uint64_t v) { head(b, MAJOR_UINT, v); }
static void w_arr(ssc_buf *b, uint64_t n) { head(b, MAJOR_ARRAY, n); }
static void w_bool(ssc_buf *b, int v) { put_byte(b, v ? S_TRUE : S_FALSE); }
static void w_text(ssc_buf *b, ssc_slice s) { head(b, MAJOR_TEXT, s.len); if (s.len) put(b, s.ptr, s.len); }
static void w_bytes(ssc_buf *b, ssc_slice s) { head(b, MAJOR_BYTES, s.len); if (s.len) put(b, s.ptr, s.len); }

static void w_outcome(ssc_buf *b, const ssc_frame *f) {
    if (f->ok) {
        w_bool(b, 1);
        if (f->has_value) w_text(b, f->text); else put_byte(b, S_NULL);
    } else {
        w_bool(b, 0);
        w_uint(b, f->code);
    }
}

int ssc_encode_body(const ssc_frame *f, ssc_buf *b) {
    size_t start = b->len;
    switch (f->type) {
    case SSC_F_INIT:
        w_arr(b, 6); w_uint(b, f->type); w_uint(b, f->call_id); w_uint(b, f->version); w_uint(b, f->api_level);
        w_arr(b, SSC_LIMIT_FIELDS);
        for (int i = 0; i < SSC_LIMIT_FIELDS; i++) w_uint(b, f->limits[i]);
        w_text(b, f->text);
        break;
    case SSC_F_INVOKE: w_arr(b, 4); w_uint(b, f->type); w_uint(b, f->call_id); w_text(b, f->text); w_text(b, f->text2); break;
    case SSC_F_CANCEL: w_arr(b, 2); w_uint(b, f->type); w_uint(b, f->call_id); break;
    case SSC_F_BRIDGE_RESULT:
    case SSC_F_ASYNC_BRIDGE_RESULT: w_arr(b, 4); w_uint(b, f->type); w_uint(b, f->id); w_outcome(b, f); break;
    case SSC_F_ASYNC_CANCEL: w_arr(b, 2); w_uint(b, f->type); w_uint(b, f->id); break;
    case SSC_F_READY: w_arr(b, 3); w_uint(b, f->type); w_uint(b, f->call_id); w_uint(b, f->version); break;
    case SSC_F_BRIDGE_CALL:
    case SSC_F_ASYNC_BRIDGE_CALL:
        w_arr(b, 5); w_uint(b, f->type); w_uint(b, f->call_id); w_uint(b, f->id); w_text(b, f->text); w_text(b, f->text2);
        break;
    case SSC_F_RESULT: w_arr(b, 3); w_uint(b, f->type); w_uint(b, f->call_id); w_text(b, f->text); break;
    case SSC_F_FAILURE: w_arr(b, 4); w_uint(b, f->type); w_uint(b, f->call_id); w_uint(b, f->code); w_uint(b, f->marker); break;
    case SSC_F_LOG: w_arr(b, 2); w_uint(b, f->type); w_bytes(b, f->text); break;
    case SSC_F_LIMIT_EXCEEDED: w_arr(b, 3); w_uint(b, f->type); w_uint(b, f->call_id); w_uint(b, f->code); break;
    default: return SSC_FR_MALFORMED;
    }
    if (b->failed) { b->len = start; return SSC_FR_NOMEM; }
    return SSC_FR_OK;
}

static void be32(uint8_t *p, uint32_t v) { p[0] = (uint8_t)(v >> 24); p[1] = (uint8_t)(v >> 16); p[2] = (uint8_t)(v >> 8); p[3] = (uint8_t)v; }

int ssc_encode_envelopes(const ssc_frame *f, ssc_buf *out) {
    ssc_buf body = {0};
    int rc = ssc_encode_body(f, &body);
    if (rc) { ssc_buf_free(&body); return rc; }
    uint8_t hdr[4];
    if (body.len <= SSC_MAX_FRAME_BYTES) {
        be32(hdr, (uint32_t)body.len);
        put(out, hdr, 4);
        put(out, body.buf, body.len);
    } else {
        for (size_t off = 0; off < body.len;) {
            size_t n = body.len - off < SSC_CHUNK_PAYLOAD_BYTES ? body.len - off : SSC_CHUNK_PAYLOAD_BYTES;
            ssc_buf c = {0};
            w_arr(&c, 3); w_uint(&c, SSC_F_CHUNK); w_bool(&c, off + n < body.len);
            w_bytes(&c, (ssc_slice){ body.buf + off, n });
            if (c.failed) { ssc_buf_free(&c); ssc_buf_free(&body); return SSC_FR_NOMEM; }
            be32(hdr, (uint32_t)c.len);
            put(out, hdr, 4);
            put(out, c.buf, c.len);
            ssc_buf_free(&c);
            off += n;
        }
    }
    ssc_buf_free(&body);
    return out->failed ? SSC_FR_NOMEM : SSC_FR_OK;
}

/* ---- strict reader ---------------------------------------------------------------------- */

typedef struct {
    const uint8_t *p;
    size_t pos;
    size_t end;
    int bad;
} rd;

static int r_head(rd *r, unsigned major, uint64_t *v) {
    if (r->bad || r->pos >= r->end) return r->bad = 1, 0;
    uint8_t ib = r->p[r->pos++];
    if ((unsigned)(ib >> 5) != major) return r->bad = 1, 0;
    unsigned info = ib & 0x1f;
    if (info < 24) { *v = info; return 1; }
    size_t n = info == 24 ? 1 : info == 25 ? 2 : info == 26 ? 4 : info == 27 ? 8 : 0;
    if (!n || r->end - r->pos < n) return r->bad = 1, 0;
    uint64_t x = 0;
    for (size_t i = 0; i < n; i++) x = (x << 8) | r->p[r->pos++];
    int minimal = n == 1 ? x >= 24 : n == 2 ? x > 0xff : n == 4 ? x > 0xffff : x > 0xffffffffu;
    if (!minimal || x > (uint64_t)INT64_MAX) return r->bad = 1, 0;
    *v = x;
    return 1;
}

static uint64_t r_uint(rd *r, uint64_t max) {
    uint64_t v = 0;
    if (!r_head(r, MAJOR_UINT, &v) || v > max) { r->bad = 1; return 0; }
    return v;
}

static int r_bool(rd *r) {
    if (r->bad || r->pos >= r->end) { r->bad = 1; return 0; }
    uint8_t b = r->p[r->pos++];
    if (b == S_TRUE) return 1;
    if (b != S_FALSE) r->bad = 1;
    return 0;
}

static int r_null_next(rd *r) {
    if (r->bad || r->pos >= r->end) { r->bad = 1; return 0; }
    if (r->p[r->pos] != S_NULL) return 0;
    r->pos++;
    return 1;
}

static ssc_slice r_str(rd *r, unsigned major, uint32_t max) {
    ssc_slice s = { NULL, 0 };
    uint64_t n = 0;
    if (!r_head(r, major, &n) || n > max || n > r->end - r->pos) { r->bad = 1; return s; }
    s.ptr = r->p + r->pos;
    s.len = (size_t)n;
    r->pos += (size_t)n;
    if (major == MAJOR_TEXT && !ssc_utf8_valid(s.ptr, s.len)) r->bad = 1;
    return s;
}

static uint64_t r_arr(rd *r, uint64_t max) {
    uint64_t n = 0;
    if (!r_head(r, MAJOR_ARRAY, &n) || n > max || n > r->end - r->pos) { r->bad = 1; return 0; }
    return n;
}

int ssc_utf8_valid(const uint8_t *p, size_t len) {
    size_t i = 0;
    while (i < len) {
        uint8_t c = p[i];
        if (c < 0x80) { i++; continue; }
        size_t n;
        uint32_t cp, min;
        if (c >= 0xc2 && c <= 0xdf) { n = 2; cp = c & 0x1f; min = 0x80; }
        else if (c >= 0xe0 && c <= 0xef) { n = 3; cp = c & 0x0f; min = 0x800; }
        else if (c >= 0xf0 && c <= 0xf4) { n = 4; cp = c & 0x07; min = 0x10000; }
        else return 0;
        if (len - i < n) return 0;
        for (size_t k = 1; k < n; k++) {
            if ((p[i + k] & 0xc0) != 0x80) return 0;
            cp = (cp << 6) | (p[i + k] & 0x3f);
        }
        if (cp < min || cp > 0x10ffff || (cp >= 0xd800 && cp <= 0xdfff)) return 0;
        i += n;
    }
    return 1;
}

void ssc_field_limits_default(ssc_field_limits *l) {
    l->max_script_bytes = 8u << 20;
    l->max_method_bytes = 256;
    l->max_json_bytes = 4u << 20;
    l->max_log_bytes = 16u * 1024;
}

void ssc_frame_free(ssc_frame *f) {
    if (!f) return;
    free(f->owned);
    f->owned = NULL;
}

static int accepts(ssc_peer reader, unsigned type) {
    if (reader == SSC_PEER_SANDBOX) return type >= SSC_F_INIT && type <= SSC_F_ASYNC_CANCEL;
    return type >= SSC_F_READY && type <= SSC_F_LIMIT_EXCEEDED;
}

static int known_error(uint64_t c) { return (c >= 1 && c <= 11) || (c >= 32 && c <= 37); }
static int known_limit(uint64_t c) { return c >= 1 && c <= 9; }

int ssc_frame_decode(const uint8_t *body, size_t len, ssc_peer reader, const ssc_field_limits *lim, ssc_frame *out) {
    memset(out, 0, sizeof(*out));
    uint8_t *copy = malloc(len ? len : 1);
    if (!copy) return SSC_FR_NOMEM;
    if (len) memcpy(copy, body, len);
    rd r = { copy, 0, len, 0 };
    uint64_t n = r_arr(&r, 8);
    uint64_t type = n >= 1 ? r_uint(&r, 255) : (r.bad = 1, 0);
    if (r.bad || type == SSC_F_CHUNK || !accepts(reader, (unsigned)type)) goto bad;
    out->type = (ssc_frame_type)type;
#define ARITY(k) do { if (n != (k)) goto bad; } while (0)
    switch (out->type) {
    case SSC_F_INIT:
        ARITY(6);
        out->call_id = r_uint(&r, INT64_MAX);
        out->version = (uint32_t)r_uint(&r, 0xffff);
        out->api_level = (uint32_t)r_uint(&r, 0xffff);
        if (r_arr(&r, SSC_LIMIT_FIELDS) != SSC_LIMIT_FIELDS) goto bad;
        for (int i = 0; i < SSC_LIMIT_FIELDS; i++) {
            out->limits[i] = r_uint(&r, INT64_MAX);
            if (!r.bad && out->limits[i] == 0) goto bad;
        }
        out->text = r_str(&r, MAJOR_TEXT, lim->max_script_bytes);
        break;
    case SSC_F_INVOKE:
        ARITY(4);
        out->call_id = r_uint(&r, INT64_MAX);
        out->text = r_str(&r, MAJOR_TEXT, lim->max_method_bytes);
        out->text2 = r_str(&r, MAJOR_TEXT, lim->max_json_bytes);
        break;
    case SSC_F_CANCEL: ARITY(2); out->call_id = r_uint(&r, INT64_MAX); break;
    case SSC_F_BRIDGE_RESULT:
    case SSC_F_ASYNC_BRIDGE_RESULT:
        ARITY(4);
        out->id = r_uint(&r, INT64_MAX);
        out->ok = r_bool(&r);
        if (out->ok) {
            if (r_null_next(&r)) out->has_value = 0;
            else { out->has_value = 1; out->text = r_str(&r, MAJOR_TEXT, lim->max_json_bytes); }
        } else {
            uint64_t c = r_uint(&r, 0xffff);
            if (!r.bad && !known_error(c)) goto bad;
            out->code = (uint32_t)c;
        }
        break;
    case SSC_F_ASYNC_CANCEL: ARITY(2); out->id = r_uint(&r, INT64_MAX); break;
    case SSC_F_READY: ARITY(3); out->call_id = r_uint(&r, INT64_MAX); out->version = (uint32_t)r_uint(&r, 0xffff); break;
    case SSC_F_BRIDGE_CALL:
    case SSC_F_ASYNC_BRIDGE_CALL:
        ARITY(5);
        out->call_id = r_uint(&r, INT64_MAX);
        out->id = r_uint(&r, INT64_MAX);
        out->text = r_str(&r, MAJOR_TEXT, lim->max_method_bytes);
        out->text2 = r_str(&r, MAJOR_TEXT, lim->max_json_bytes);
        break;
    case SSC_F_RESULT: ARITY(3); out->call_id = r_uint(&r, INT64_MAX); out->text = r_str(&r, MAJOR_TEXT, lim->max_json_bytes); break;
    case SSC_F_FAILURE: {
        ARITY(4);
        out->call_id = r_uint(&r, INT64_MAX);
        uint64_t c = r_uint(&r, 0xffff);
        if (!r.bad && !known_error(c)) goto bad;
        out->code = (uint32_t)c;
        uint64_t m = r_uint(&r, SSC_SOURCE_FAILURE_MAX);
        /* Only a script's own throw can carry a marker. */
        if (!r.bad && m != 0 && c != 1) goto bad;
        out->marker = (uint32_t)m;
        break;
    }
    case SSC_F_LOG: ARITY(2); out->text = r_str(&r, MAJOR_BYTES, lim->max_log_bytes); break;
    case SSC_F_LIMIT_EXCEEDED: {
        ARITY(3);
        out->call_id = r_uint(&r, INT64_MAX);
        uint64_t k = r_uint(&r, 0xff);
        if (!r.bad && !known_limit(k)) goto bad;
        out->code = (uint32_t)k;
        break;
    }
    default: goto bad;
    }
#undef ARITY
    if (r.bad || r.pos != r.end) goto bad;
    out->owned = copy;
    return SSC_FR_OK;
bad:
    free(copy);
    memset(out, 0, sizeof(*out));
    return SSC_FR_MALFORMED;
}

/* ---- incremental envelope reader -------------------------------------------------------- */

struct ssc_reader {
    ssc_peer peer;
    size_t max_message;
    ssc_field_limits limits;
    uint8_t *pending;
    size_t pending_len;
    size_t pending_cap;
    uint8_t *chunks; /* reassembly buffer; NULL when no chunk sequence is open */
    size_t chunk_len;
    size_t chunk_cap;
    int broken;
};

ssc_reader *ssc_reader_new(ssc_peer peer, size_t max_message_bytes, const ssc_field_limits *limits) {
    ssc_reader *r = calloc(1, sizeof(*r));
    if (!r) return NULL;
    r->peer = peer;
    r->max_message = max_message_bytes;
    if (limits) r->limits = *limits; else ssc_field_limits_default(&r->limits);
    return r;
}

void ssc_reader_free(ssc_reader *r) {
    if (!r) return;
    free(r->pending);
    free(r->chunks);
    free(r);
}

int ssc_reader_idle(const ssc_reader *r) { return r->pending_len == 0 && r->chunks == NULL; }

static int on_body(ssc_reader *r, const uint8_t *body, size_t len, ssc_on_frame cb, void *opaque) {
    if (len >= 2 && body[0] == 0x83 && body[1] == 0x00) { /* [0, more, bytes] */
        rd c = { body, 0, len, 0 };
        if (r_arr(&c, 3) != 3) return SSC_FR_MALFORMED;
        r_uint(&c, 0);
        int more = r_bool(&c);
        ssc_slice part = r_str(&c, MAJOR_BYTES, SSC_MAX_FRAME_BYTES);
        if (c.bad || c.pos != c.end || part.len == 0) return SSC_FR_MALFORMED;
        if (more && part.len != SSC_CHUNK_PAYLOAD_BYTES) return SSC_FR_MALFORMED;
        if (part.len > SSC_CHUNK_PAYLOAD_BYTES) return SSC_FR_MALFORMED;
        if (r->chunk_len + part.len > r->max_message) return SSC_FR_LIMIT;
        if (r->chunk_len + part.len > r->chunk_cap) {
            size_t cap = r->chunk_cap ? r->chunk_cap * 2 : 2 * SSC_CHUNK_PAYLOAD_BYTES;
            if (cap < r->chunk_len + part.len) cap = r->chunk_len + part.len;
            if (cap > r->max_message) cap = r->max_message;
            uint8_t *nb = realloc(r->chunks, cap ? cap : 1);
            if (!nb) return SSC_FR_NOMEM;
            r->chunks = nb;
            r->chunk_cap = cap;
        }
        memcpy(r->chunks + r->chunk_len, part.ptr, part.len);
        r->chunk_len += part.len;
        if (more) return SSC_FR_OK;
        size_t whole = r->chunk_len;
        uint8_t *buf = r->chunks;
        r->chunks = NULL;
        r->chunk_len = r->chunk_cap = 0;
        if (whole <= SSC_MAX_FRAME_BYTES) { free(buf); return SSC_FR_MALFORMED; } /* needlessly chunked */
        ssc_frame f;
        int rc = ssc_frame_decode(buf, whole, r->peer, &r->limits, &f);
        free(buf);
        if (rc) return rc;
        cb(opaque, &f);
        return SSC_FR_OK;
    }
    if (r->chunks) return SSC_FR_MALFORMED; /* interleaved with a chunk sequence */
    ssc_frame f;
    int rc = ssc_frame_decode(body, len, r->peer, &r->limits, &f);
    if (rc) return rc;
    cb(opaque, &f);
    return SSC_FR_OK;
}

int ssc_reader_feed(ssc_reader *r, const uint8_t *data, size_t len, ssc_on_frame cb, void *opaque) {
    if (r->broken) return SSC_FR_BROKEN;
    if (r->pending_len + len > r->pending_cap) {
        size_t cap = r->pending_cap ? r->pending_cap : 4096;
        while (cap < r->pending_len + len) cap *= 2;
        uint8_t *nb = realloc(r->pending, cap);
        if (!nb) { r->broken = 1; return SSC_FR_NOMEM; }
        r->pending = nb;
        r->pending_cap = cap;
    }
    if (len) memcpy(r->pending + r->pending_len, data, len);
    r->pending_len += len;
    size_t pos = 0;
    int rc = SSC_FR_OK;
    while (r->pending_len - pos >= 4) {
        const uint8_t *h = r->pending + pos;
        uint32_t n = ((uint32_t)h[0] << 24) | ((uint32_t)h[1] << 16) | ((uint32_t)h[2] << 8) | h[3];
        if (n == 0 || n > SSC_MAX_FRAME_BYTES) { rc = SSC_FR_MALFORMED; break; }
        if (r->pending_len - pos - 4 < n) break;
        rc = on_body(r, h + 4, n, cb, opaque);
        pos += 4 + (size_t)n;
        if (rc) break;
    }
    if (rc) { r->broken = 1; return rc; }
    memmove(r->pending, r->pending + pos, r->pending_len - pos);
    r->pending_len -= pos;
    return SSC_FR_OK;
}
