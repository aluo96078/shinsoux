/*
 * libFuzzer target for SandboxProtocol v1 (ADR-0002 §13, S1 acceptance: 24 h codec fuzz).
 *
 * Byte 0: bits 0-1 pick the mode, bit 2 the reading peer (1 = host reads sandbox→host frames).
 *   body   (0) — ssc_frame_decode must reject, or accept a frame that re-encodes to the same bytes.
 *   stream (1) — byte 1 seeds the read boundaries; the rest is raw envelope bytes fed to
 *                ssc_reader. Delivered frames must re-encode; after a failure every feed is BROKEN.
 *   chunks     — expands a compact input into a large chunked frame (canonical full-size chunks,
 *                which raw inputs can never reach), applies the input's byte mutations, then feeds
 *                it. Without mutations exactly that frame must come out and the reader be idle.
 *                Megabytes per input, so it is its own target (chunks_fuzz, built with
 *                SSC_FUZZ_CHUNKS) instead of slowing the other modes down.
 * In frames_fuzz modes 2 and 3 are treated as body.
 */
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "ssc_frames.h"

static uint32_t next_rand(uint32_t *state) {
    uint32_t x = *state ? *state : 0x9e3779b9u;
    x ^= x << 13;
    x ^= x >> 17;
    x ^= x << 5;
    return *state = x;
}

static void check_canonical(const ssc_frame *f, const uint8_t *body, size_t len) {
    ssc_buf again = {0};
    if (ssc_encode_body(f, &again) != SSC_FR_OK || again.failed) abort();
    if (body && (again.len != len || memcmp(again.buf, body, len) != 0)) abort();
    ssc_frame d;
    ssc_field_limits l;
    ssc_field_limits_default(&l);
    /* A delivered frame is always one the codec accepts again. */
    ssc_peer reader = f->type < 16 ? SSC_PEER_SANDBOX : SSC_PEER_HOST;
    if (ssc_frame_decode(again.buf, again.len, reader, &l, &d) != SSC_FR_OK) abort();
    ssc_frame_free(&d);
    ssc_buf_free(&again);
}

typedef struct {
    int frames;
    const ssc_frame *expect; /* chunks mode without mutations */
} sink;

static void on_frame(void *opaque, ssc_frame *frame) {
    sink *s = opaque;
    check_canonical(frame, NULL, 0);
    if (s->expect) {
        const ssc_frame *e = s->expect;
        if (frame->type != e->type || frame->call_id != e->call_id || frame->id != e->id ||
            frame->text.len != e->text.len || memcmp(frame->text.ptr, e->text.ptr, e->text.len) != 0) abort();
    }
    s->frames++;
    ssc_frame_free(frame);
}

/* Feeds [data, size) in pieces of 1..max bytes; returns the number of frames delivered. */
static int feed(ssc_reader *r, const uint8_t *data, size_t size, uint32_t seed, size_t max, sink *s) {
    size_t off = 0;
    int broken = 0;
    while (off < size) {
        size_t piece = 1 + next_rand(&seed) % max;
        if (piece > size - off) piece = size - off;
        int rc = ssc_reader_feed(r, data + off, piece, on_frame, s);
        if (broken && rc != SSC_FR_BROKEN) abort();
        if (rc != SSC_FR_OK) broken = 1;
        off += piece;
    }
    return broken;
}

#ifdef SSC_FUZZ_CHUNKS
static void fuzz_chunks(ssc_peer peer, const uint8_t *data, size_t size) {
    if (size < 3) return;
    uint32_t seed = data[0] | (uint32_t)data[1] << 8;
    size_t whole_chunks = data[2] % 4;           /* 0..3 full-size chunks before the last one */
    size_t mutations = (data[2] >> 2) % 9;       /* 0..8 byte mutations of the envelope stream */
    data += 3;
    size -= 3;
    if (size < mutations * 5) return;
    const uint8_t *muts = data;
    data += mutations * 5;
    size -= mutations * 5;

    /* A JSON string payload: whole_chunks full chunks plus a tail, filled from the input. */
    size_t text_len = whole_chunks * SSC_CHUNK_PAYLOAD_BYTES + (size % 4096) + 2;
    if (text_len > (3u << 20)) text_len = 3u << 20;
    char *text = malloc(text_len);
    if (!text) return;
    text[0] = '"';
    for (size_t i = 1; i + 1 < text_len; i++) {
        uint8_t c = size ? data[i % size] : (uint8_t)i;
        text[i] = (char)(0x20 + c % 0x5f);
        if (text[i] == '"' || text[i] == '\\') text[i] = 'x';
    }
    text[text_len - 1] = '"';
    ssc_frame f = { .type = peer == SSC_PEER_HOST ? SSC_F_RESULT : SSC_F_BRIDGE_RESULT,
                    .call_id = peer == SSC_PEER_HOST ? 1 + seed : 0, .id = peer == SSC_PEER_HOST ? 0 : 1 + seed,
                    .ok = 1, .has_value = 1, .text = { (const uint8_t *)text, text_len } };
    ssc_buf env = {0};
    if (ssc_encode_envelopes(&f, &env) != SSC_FR_OK || env.failed) { free(text); ssc_buf_free(&env); return; }
    for (size_t m = 0; m < mutations; m++) {
        const uint8_t *p = muts + m * 5;
        uint32_t at = p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24;
        env.buf[at % env.len] ^= p[4];
    }
    ssc_field_limits l;
    ssc_field_limits_default(&l);
    ssc_reader *r = ssc_reader_new(peer, 8u << 20, &l);
    if (r) {
        sink s = { 0, mutations == 0 ? &f : NULL };
        int broken = feed(r, env.buf, env.len, seed, 256u << 10, &s);
        if (mutations == 0 && (broken || s.frames != 1 || !ssc_reader_idle(r))) abort();
        ssc_reader_free(r);
    }
    ssc_buf_free(&env);
    free(text);
}
#endif

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) {
    if (size < 1) return 0;
    int mode = data[0] & 3; /* chunks_fuzz ignores it */
    ssc_peer peer = (data[0] & 4) ? SSC_PEER_HOST : SSC_PEER_SANDBOX;
    ssc_field_limits l;
    ssc_field_limits_default(&l);
    data++;
    size--;
#ifdef SSC_FUZZ_CHUNKS
    (void)mode;
    fuzz_chunks(peer, data, size);
    return 0;
#endif

    if (mode == 1) {
        if (size < 1) return 0;
        /* A small message cap keeps the cap check reachable from short inputs. */
        ssc_reader *r = ssc_reader_new(peer, 64u << 10, &l);
        if (!r) return 0;
        sink s = { 0, NULL };
        feed(r, data + 1, size - 1, data[0], 64, &s);
        ssc_reader_free(r);
        return 0;
    }
    ssc_frame f;
    if (ssc_frame_decode(data, size, peer, &l, &f) == SSC_FR_OK) {
        check_canonical(&f, data, size);
        ssc_frame_free(&f);
    }
    return 0;
}
