/*
 * libFuzzer target for SandboxProtocol v1 (ADR-0002 §13, S1 acceptance: 24 h codec fuzz).
 *
 * Byte 0 picks the reading peer and the mode:
 *   body   — ssc_frame_decode must reject or accept a frame that re-encodes to the same bytes.
 *   stream — the rest is fed to ssc_reader in pieces whose sizes come from the input; every
 *            delivered frame must re-encode, and after a failure every feed reports BROKEN.
 */
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "ssc_frames.h"

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

static void on_frame(void *opaque, ssc_frame *frame) {
    (void)opaque;
    check_canonical(frame, NULL, 0);
    ssc_frame_free(frame);
}

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) {
    if (size < 1) return 0;
    ssc_peer peer = (data[0] & 1) ? SSC_PEER_HOST : SSC_PEER_SANDBOX;
    int stream = (data[0] & 2) != 0;
    ssc_field_limits l;
    ssc_field_limits_default(&l);
    data++;
    size--;

    if (!stream) {
        ssc_frame f;
        if (ssc_frame_decode(data, size, peer, &l, &f) == SSC_FR_OK) {
            check_canonical(&f, data, size);
            ssc_frame_free(&f);
        }
        return 0;
    }

    /* A small message cap keeps chunk reassembly and the cap check reachable. */
    ssc_reader *r = ssc_reader_new(peer, 64u << 10, &l);
    if (!r) return 0;
    size_t off = 0;
    int broken = 0;
    while (off < size) {
        size_t piece = 1 + (data[off] % 64);
        off++;
        if (piece > size - off) piece = size - off;
        int rc = ssc_reader_feed(r, data + off, piece, on_frame, NULL);
        if (broken && rc != SSC_FR_BROKEN) abort();
        if (rc != SSC_FR_OK) broken = 1;
        off += piece;
    }
    ssc_reader_free(r);
    return 0;
}
