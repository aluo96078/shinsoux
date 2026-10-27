/*
 * SandboxProtocol v1 codec tests. The golden vectors and malformed inputs are the same bytes as
 * composeApp/src/commonTest/kotlin/dev/shinsou/kmp/plugin/sandbox/SandboxProtocolTest.kt: both
 * implementations must accept and produce exactly these.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "ssc_frames.h"

static int failures;
#define CHECK(c, ...) do { if (!(c)) { failures++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

static size_t unhex(const char *hex, uint8_t *out) {
    size_t n = 0;
    for (const char *p = hex; *p;) {
        if (*p == ' ') { p++; continue; }
        unsigned v;
        sscanf(p, "%2x", &v);
        out[n++] = (uint8_t)v;
        p += 2;
    }
    return n;
}

static void tohex(const uint8_t *p, size_t n, char *out) {
    for (size_t i = 0; i < n; i++) sprintf(out + 2 * i, "%02x", p[i]);
    out[2 * n] = 0;
}

static ssc_slice S(const char *s) { return (ssc_slice){ (const uint8_t *)s, strlen(s) }; }

static void golden(const char *label, const ssc_frame *f, const char *want) {
    ssc_buf b = {0};
    CHECK(ssc_encode_body(f, &b) == SSC_FR_OK, "%s: encode", label);
    char hex[256];
    tohex(b.buf, b.len, hex);
    CHECK(strcmp(hex, want) == 0, "%s: got %s want %s", label, hex, want);
    ssc_buf_free(&b);
}

static int decode_hex(const char *hex, ssc_peer peer, ssc_frame *out) {
    uint8_t buf[256];
    size_t n = unhex(hex, buf);
    ssc_field_limits l;
    ssc_field_limits_default(&l);
    return ssc_frame_decode(buf, n, peer, &l, out);
}

/* ---- reader helpers ---- */
typedef struct { int frames; ssc_frame last; size_t last_len; } sink;
static void on_frame(void *o, ssc_frame *f) {
    sink *s = o;
    s->frames++;
    s->last_len = f->text.len;
    ssc_frame_free(&s->last);
    s->last = *f;
}

static void test_golden(void) {
    golden("invoke", &(ssc_frame){ .type = SSC_F_INVOKE, .call_id = 7, .text = S("ok"), .text2 = S("[]") }, "840207626f6b625b5d");
    golden("cancel", &(ssc_frame){ .type = SSC_F_CANCEL, .call_id = 24 }, "82031818");
    golden("ready", &(ssc_frame){ .type = SSC_F_READY, .call_id = 1, .version = 1 }, "83100101");
    golden("bridge result null", &(ssc_frame){ .type = SSC_F_BRIDGE_RESULT, .id = 1, .ok = 1, .has_value = 0 }, "840401f5f6");
    golden("bridge result error", &(ssc_frame){ .type = SSC_F_BRIDGE_RESULT, .id = 1, .ok = 0, .code = 33 }, "840401f41821");
    golden("failure", &(ssc_frame){ .type = SSC_F_FAILURE, .call_id = 1234, .code = 4 }, "84141904d20400");
    golden("failure with source marker", &(ssc_frame){ .type = SSC_F_FAILURE, .call_id = 1234, .code = 1, .marker = 5 }, "84141904d20105");
    golden("bridge call", &(ssc_frame){ .type = SSC_F_BRIDGE_CALL, .call_id = 1, .id = 2, .text = S("a"), .text2 = S("{}") }, "851101026161627b7d");

    ssc_buf env = {0};
    ssc_encode_envelopes(&(ssc_frame){ .type = SSC_F_CANCEL, .call_id = 24 }, &env);
    char hex[64];
    tohex(env.buf, env.len, hex);
    CHECK(strcmp(hex, "0000000482031818") == 0, "envelope: %s", hex);
    ssc_buf_free(&env);
}

static void test_round_trip(void) {
    /* Each side decodes what the other encodes, and re-encoding reproduces the bytes. */
    ssc_frame host_frames[] = {
        { .type = SSC_F_INIT, .call_id = 1, .version = 1, .api_level = 1, .limits = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10}, .text = S("var source={};") },
        { .type = SSC_F_INVOKE, .call_id = 2, .text = S("getPopularManga"), .text2 = S("[1]") },
        { .type = SSC_F_BRIDGE_RESULT, .id = 9, .ok = 1, .has_value = 1, .text = S("\"\xe6\xbc\xab\"") },
        { .type = SSC_F_ASYNC_BRIDGE_RESULT, .id = 3, .ok = 0, .code = 36 },
        { .type = SSC_F_ASYNC_CANCEL, .id = 3 },
    };
    for (size_t i = 0; i < sizeof host_frames / sizeof host_frames[0]; i++) {
        ssc_buf b = {0};
        ssc_encode_body(&host_frames[i], &b);
        ssc_frame d;
        ssc_field_limits l;
        ssc_field_limits_default(&l);
        CHECK(ssc_frame_decode(b.buf, b.len, SSC_PEER_SANDBOX, &l, &d) == SSC_FR_OK, "host frame %zu decodes", i);
        CHECK(ssc_frame_decode(b.buf, b.len, SSC_PEER_HOST, &l, &(ssc_frame){0}) == SSC_FR_MALFORMED, "host frame %zu refused by the host", i);
        ssc_buf again = {0};
        ssc_encode_body(&d, &again);
        CHECK(again.len == b.len && memcmp(again.buf, b.buf, b.len) == 0, "host frame %zu canonical", i);
        ssc_frame_free(&d);
        ssc_buf_free(&b);
        ssc_buf_free(&again);
    }
}

static void test_malformed(void) {
    const char *bad[] = {
        "", "80", "8163", "8118ff", "841401010001", "84140101", "83130163", "821301", "83130163ff5b5d",
        "8313180163", "9f1301", "831320605b5d", "8313a0", "83131901", "8414011b800000000000000000",
        "841401f97c0000", "841401c00100", "9b000000000000000213", "83167f", "841401186300", "831601ff",
        "830001410a",
        /* FAILURE: the v1 three-field form, a marker without SCRIPT_ERROR, an unknown marker */
        "83141904d204", "84141904d20405", "84141904d20106",
        /* UTF-8 the host also rejects: overlong, surrogate, above U+10FFFF */
        "83130162c0af", "83130163eda080", "83130164f4908080",
    };
    for (size_t i = 0; i < sizeof bad / sizeof bad[0]; i++) {
        ssc_frame f;
        CHECK(decode_hex(bad[i], SSC_PEER_HOST, &f) == SSC_FR_MALFORMED, "should reject %s", bad[i]);
    }
}

static int feed_hex(ssc_reader *r, const char *hex, sink *s) {
    uint8_t buf[256];
    size_t n = unhex(hex, buf);
    return ssc_reader_feed(r, buf, n, on_frame, s);
}

static void test_envelopes_and_chunks(void) {
    sink s = {0};
    ssc_reader *r = ssc_reader_new(SSC_PEER_HOST, 8u << 20, NULL);
    CHECK(feed_hex(r, "00000000", &s) != SSC_FR_OK, "zero-length envelope");
    CHECK(feed_hex(r, "0000000483100101", &s) == SSC_FR_BROKEN, "a broken reader stays broken");
    ssc_reader_free(r);

    r = ssc_reader_new(SSC_PEER_HOST, 8u << 20, NULL);
    CHECK(feed_hex(r, "00100001", &s) != SSC_FR_OK, "envelope above 1 MiB");
    ssc_reader_free(r);

    r = ssc_reader_new(SSC_PEER_HOST, 8u << 20, NULL);
    CHECK(feed_hex(r, "000000058300f54100", &s) != SSC_FR_OK, "tiny non-final chunk");
    ssc_reader_free(r);

    /* A large Result split into chunks, fed in odd slices, reassembles exactly once. */
    size_t big = 3u << 20;
    char *json = malloc(big + 3);
    json[0] = '"';
    memset(json + 1, 'x', big);
    json[big + 1] = '"';
    json[big + 2] = 0;
    ssc_frame f = { .type = SSC_F_RESULT, .call_id = 5, .text = { (const uint8_t *)json, big + 2 } };
    ssc_buf env = {0};
    CHECK(ssc_encode_envelopes(&f, &env) == SSC_FR_OK, "encode chunked");
    r = ssc_reader_new(SSC_PEER_HOST, 8u << 20, NULL);
    memset(&s, 0, sizeof s);
    for (size_t off = 0, step = 7; off < env.len; off += step, step = step * 3 % 100003 + 1) {
        size_t n = env.len - off < step ? env.len - off : step;
        CHECK(ssc_reader_feed(r, env.buf + off, n, on_frame, &s) == SSC_FR_OK, "feed at %zu", off);
    }
    CHECK(s.frames == 1 && s.last.type == SSC_F_RESULT && s.last_len == big + 2, "reassembled %d frames len %zu", s.frames, s.last_len);
    CHECK(ssc_reader_idle(r), "idle after the last chunk");
    ssc_frame_free(&s.last);
    ssc_reader_free(r);

    /* Reassembly is capped. */
    r = ssc_reader_new(SSC_PEER_HOST, 2u << 20, NULL);
    CHECK(ssc_reader_feed(r, env.buf, env.len, on_frame, &s) != SSC_FR_OK, "message above the cap");
    ssc_reader_free(r);
    ssc_buf_free(&env);
    free(json);
}

/* Mutation fuzz: decode either fails or yields a frame that re-encodes to the same bytes. */
static uint64_t rng = 88172645463325252ull;
static uint32_t rnd(void) { rng ^= rng << 13; rng ^= rng >> 7; rng ^= rng << 17; return (uint32_t)rng; }

static void test_fuzz(long iterations) {
    ssc_frame seeds[] = {
        { .type = SSC_F_READY, .call_id = 1, .version = 1 },
        { .type = SSC_F_BRIDGE_CALL, .call_id = 5, .id = 1ull << 40, .text = S("net.request"), .text2 = S("{\"url\":\"u\"}") },
        { .type = SSC_F_RESULT, .call_id = 5, .text = S("{\"mangas\":[]}") },
        { .type = SSC_F_LOG, .text = S("abc") },
        { .type = SSC_F_LIMIT_EXCEEDED, .call_id = 5, .code = 1 },
        { .type = SSC_F_FAILURE, .call_id = 5, .code = 37 },
    };
    ssc_buf enc[6];
    for (int i = 0; i < 6; i++) { memset(&enc[i], 0, sizeof enc[i]); ssc_encode_body(&seeds[i], &enc[i]); }
    ssc_field_limits l;
    ssc_field_limits_default(&l);
    uint8_t buf[512];
    long accepted = 0;
    for (long it = 0; it < iterations; it++) {
        ssc_buf *src = &enc[rnd() % 6];
        size_t n = src->len;
        memcpy(buf, src->buf, n);
        int muts = 1 + rnd() % 4;
        for (int m = 0; m < muts; m++) {
            switch (rnd() % 5) {
            case 0: if (n) buf[rnd() % n] = (uint8_t)rnd(); break;
            case 1: if (n) buf[rnd() % n] ^= (uint8_t)(1u << (rnd() % 8)); break;
            case 2: if (n) n = rnd() % n; break;
            case 3: if (n < sizeof buf - 4) { size_t at = rnd() % (n + 1); memmove(buf + at + 1, buf + at, n - at); buf[at] = (uint8_t)rnd(); n++; } break;
            default: if (n > 1) { size_t at = rnd() % (n - 1); memmove(buf + at, buf + at + 1, n - at - 1); n--; } break;
            }
        }
        ssc_frame d;
        if (ssc_frame_decode(buf, n, SSC_PEER_HOST, &l, &d) != SSC_FR_OK) continue;
        accepted++;
        ssc_buf again = {0};
        ssc_encode_body(&d, &again);
        if (again.len != n || memcmp(again.buf, buf, n) != 0) {
            char hex[1100];
            tohex(buf, n, hex);
            CHECK(0, "non-canonical accept: %s", hex);
        }
        ssc_buf_free(&again);
        ssc_frame_free(&d);
    }
    for (int i = 0; i < 6; i++) ssc_buf_free(&enc[i]);
    printf("  fuzz: %ld inputs, %ld accepted, all canonical\n", iterations, accepted);
}

int main(int argc, char **argv) {
    long iterations = argc > 1 ? atol(argv[1]) : 500000;
    printf("[frames]\n");
    test_golden();
    test_round_trip();
    test_malformed();
    test_envelopes_and_chunks();
    test_fuzz(iterations);
    printf(failures ? "\n%d FAILURE(S)\n" : "\nALL PASSED\n", failures);
    return failures ? 1 : 0;
}
