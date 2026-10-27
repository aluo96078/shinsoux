/* Writes the seed corpus for frames_fuzz: one file per frame type, as a body and as envelopes. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "ssc_frames.h"

#define S(x) ((ssc_slice){ (const uint8_t *)(x), sizeof(x) - 1 })

static void put(const char *dir, int n, uint8_t mode, const ssc_buf *b) {
    char path[1024];
    snprintf(path, sizeof path, "%s/seed-%02d-%u", dir, n, mode);
    FILE *f = fopen(path, "wb");
    if (!f) { perror(path); exit(1); }
    fputc(mode, f);
    fwrite(b->buf, 1, b->len, f);
    fclose(f);
}

int main(int argc, char **argv) {
    if (argc != 2) { fprintf(stderr, "usage: frames_seeds DIR\n"); return 2; }
    ssc_frame frames[] = {
        { .type = SSC_F_INIT, .call_id = 1, .version = 1, .api_level = 1, .limits = { 64u << 20, 1u << 20, 5000, 15000, 256, 4u << 20, 1u << 20, 6, 8u << 20, 65536 }, .text = S("var source={};") },
        { .type = SSC_F_INVOKE, .call_id = 2, .text = S("getPopularManga"), .text2 = S("[1]") },
        { .type = SSC_F_CANCEL, .call_id = 2 },
        { .type = SSC_F_BRIDGE_RESULT, .call_id = 2, .id = 7, .ok = 1, .has_value = 1, .text = S("\"<html></html>\"") },
        { .type = SSC_F_BRIDGE_RESULT, .call_id = 2, .id = 8, .ok = 0, .code = 33 },
        { .type = SSC_F_ASYNC_BRIDGE_RESULT, .id = 3, .ok = 1, .has_value = 1, .text = S("{\"status\":200,\"headers\":{},\"body\":\"x\"}") },
        { .type = SSC_F_ASYNC_CANCEL, .id = 3 },
        { .type = SSC_F_READY, .call_id = 1, .version = 1 },
        { .type = SSC_F_BRIDGE_CALL, .call_id = 2, .id = 7, .text = S("httpGet"), .text2 = S("[\"https://a.test/\"]") },
        { .type = SSC_F_ASYNC_BRIDGE_CALL, .call_id = 2, .id = 3, .text = S("net.fetch"), .text2 = S("[\"https://a.test/\",{\"method\":\"GET\",\"headers\":{},\"body\":null}]") },
        { .type = SSC_F_RESULT, .call_id = 2, .text = S("{\"mangas\":[],\"hasNextPage\":false}") },
        { .type = SSC_F_FAILURE, .call_id = 2, .code = 1 },
        { .type = SSC_F_LOG, .text = S("log line") },
        { .type = SSC_F_LIMIT_EXCEEDED, .call_id = 2, .code = 4 },
    };
    int n = (int)(sizeof frames / sizeof frames[0]);
    for (int i = 0; i < n; i++) {
        uint8_t peer = frames[i].type < 16 ? 0 : 1; /* bit 0: 1 = host reads (sandbox→host frames) */
        ssc_buf body = {0}, env = {0};
        if (ssc_encode_body(&frames[i], &body) != SSC_FR_OK || ssc_encode_envelopes(&frames[i], &env) != SSC_FR_OK) {
            fprintf(stderr, "encode failed for seed %d\n", i);
            return 1;
        }
        ssc_field_limits l;
        ssc_field_limits_default(&l);
        ssc_frame check;
        if (ssc_frame_decode(body.buf, body.len, peer ? SSC_PEER_HOST : SSC_PEER_SANDBOX, &l, &check) != SSC_FR_OK) {
            fprintf(stderr, "seed %d does not decode\n", i);
            return 1;
        }
        ssc_frame_free(&check);
        put(argv[1], i, peer, &body);
        put(argv[1], i, (uint8_t)(peer | 2), &env);
        ssc_buf_free(&body);
        ssc_buf_free(&env);
    }
    return 0;
}
