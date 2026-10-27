/*
 * Writes the seed corpus for frames_fuzz in its input format (see frames_fuzz.c): every frame type
 * as a body and as a stream, plus chunk-mode seeds. Each seed is checked to decode first.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "ssc_frames.h"

#define S(x) ((ssc_slice){ (const uint8_t *)(x), sizeof(x) - 1 })

static void put(const char *dir, const char *name, const uint8_t *head, size_t head_len, const uint8_t *data, size_t len) {
    char path[1024];
    snprintf(path, sizeof path, "%s/%s", dir, name);
    FILE *f = fopen(path, "wb");
    if (!f) { perror(path); exit(1); }
    fwrite(head, 1, head_len, f);
    fwrite(data, 1, len, f);
    fclose(f);
}

static int frames_seen;
static void count(void *opaque, ssc_frame *frame) { (void)opaque; frames_seen++; ssc_frame_free(frame); }

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
        uint8_t peer = frames[i].type < 16 ? 0 : 4; /* bit 2: the host reads sandbox→host frames */
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
        ssc_reader *r = ssc_reader_new(peer ? SSC_PEER_HOST : SSC_PEER_SANDBOX, 64u << 10, &l);
        frames_seen = 0;
        if (ssc_reader_feed(r, env.buf, env.len, count, NULL) != SSC_FR_OK || frames_seen != 1 || !ssc_reader_idle(r)) {
            fprintf(stderr, "stream seed %d does not decode\n", i);
            return 1;
        }
        ssc_reader_free(r);
        char name[64];
        uint8_t body_head[1] = { peer };
        uint8_t stream_head[2] = { (uint8_t)(peer | 1), (uint8_t)(17 * i + 3) };
        snprintf(name, sizeof name, "body-%02d", i);
        put(argv[1], name, body_head, 1, body.buf, body.len);
        snprintf(name, sizeof name, "stream-%02d", i);
        put(argv[1], name, stream_head, 2, env.buf, env.len);
        ssc_buf_free(&body);
        ssc_buf_free(&env);
    }
    /* chunks_fuzz: 0..3 full-size chunks for both peers, no mutations (frames_fuzz reads them as bodies). */
    for (int k = 0; k < 4; k++) {
        for (int peer = 0; peer < 2; peer++) {
            uint8_t head[4] = { (uint8_t)(2 | (peer ? 4 : 0)), (uint8_t)(k * 31 + 1), 0, (uint8_t)k };
            char name[64];
            snprintf(name, sizeof name, "chunks-%d-%d", k, peer);
            put(argv[1], name, head, 4, (const uint8_t *)"{\"mangas\":[]}", 13);
        }
    }
    return 0;
}
