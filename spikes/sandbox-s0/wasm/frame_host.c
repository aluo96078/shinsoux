#include "frame_host.h"

#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "wasm_frames.h"

static uint64_t now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000 + (uint64_t)ts.tv_nsec / 1000000;
}

static uint32_t rd32(const uint8_t *p) { uint32_t v; memcpy(&v, p, 4); return v; }
static uint64_t rd64(const uint8_t *p) { uint64_t v; memcpy(&v, p, 8); return v; }
static void wr32(uint8_t *p, uint32_t v) { memcpy(p, &v, 4); }

/* Returns the reply length, or -1. Replies larger than out_cap are parked until FETCH_REPLY. */
static int32_t finish_reply(sw_frame_host *s, uint8_t *reply, size_t len, uint8_t *out, uint32_t cap) {
    if (len <= cap) {
        memcpy(out, reply, len);
        free(reply);
        return (int32_t)len;
    }
    free(s->pending);
    s->pending = reply;
    s->pending_len = len;
    return (int32_t)len;
}

int32_t sw_frame_dispatch(sw_frame_host *s, const uint8_t *frame, uint32_t frame_len, uint8_t *out, uint32_t out_cap) {
    if (frame_len < 1) return -1;
    switch (frame[0]) {
    case SW_FRAME_BRIDGE_CALL: {
        if (frame_len < 5) return -1;
        uint32_t mlen = rd32(frame + 1);
        if (mlen > frame_len - 5) return -1;
        const char *method = (const char *)frame + 5;
        const char *args = method + mlen;
        char *res = NULL;
        size_t res_len = 0;
        int rc = s->cb.bridge(s->cb.user, method, mlen, args, frame_len - 5 - mlen, &res, &res_len);
        uint8_t *reply = malloc(res_len + 1 + 1);
        if (!reply) { free(res); return -1; }
        reply[0] = rc == 0 && res ? 0 : 1;
        if (res) memcpy(reply + 1, res, res_len);
        free(res);
        return finish_reply(s, reply, rc == 0 ? res_len + 1 : 1, out, out_cap);
    }
    case SW_FRAME_ASYNC_START: {
        if (frame_len < 5) return -1;
        int rc = s->cb.async_start(s->cb.user, rd32(frame + 1), (const char *)frame + 5, frame_len - 5);
        if (out_cap < 1) return -1;
        out[0] = rc == 0 ? 0 : 1;
        return 1;
    }
    case SW_FRAME_ASYNC_CANCEL:
        if (frame_len >= 5) s->cb.async_cancel(s->cb.user, rd32(frame + 1));
        return 0;
    case SW_FRAME_ASYNC_WAIT: {
        if (frame_len < 9) return -1;
        sw_async_result res[32];
        uint64_t deadline = rd64(frame + 1);
        int n = 0;
        /* Wait in slices so a terminate (watchdog) is noticed while blocked in the import. */
        while (!atomic_load(s->dead)) {
            uint64_t now = now_ms();
            uint64_t slice = now + 25 < deadline ? now + 25 : deadline;
            n = s->cb.async_wait(s->cb.user, slice, res, 32);
            if (n > 0 || now >= deadline) break;
        }
        size_t total = 0;
        for (int i = 0; i < n; i++) total += 9 + res[i].len;
        uint8_t *reply = malloc(total ? total : 1);
        size_t off = 0;
        for (int i = 0; i < n; i++) {
            wr32(reply + off, res[i].op);
            reply[off + 4] = (uint8_t)res[i].ok;
            wr32(reply + off + 5, (uint32_t)res[i].len);
            memcpy(reply + off + 9, res[i].payload, res[i].len);
            off += 9 + res[i].len;
            free(res[i].payload);
        }
        return finish_reply(s, reply, total, out, out_cap);
    }
    case SW_FRAME_LOG:
        if (s->cb.log) s->cb.log(s->cb.user, (const char *)frame + 1, frame_len - 1);
        return 0;
    case SW_FRAME_FETCH_REPLY: {
        if (!s->pending || s->pending_len > out_cap) return -1;
        memcpy(out, s->pending, s->pending_len);
        int32_t n = (int32_t)s->pending_len;
        free(s->pending);
        s->pending = NULL;
        s->pending_len = 0;
        return n;
    }
    default:
        return -1;
    }
}

