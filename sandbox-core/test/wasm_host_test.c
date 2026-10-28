/*
 * The iOS sandbox host's own bounds (ADR-0002 §4.5): input the guest has not taken yet lives in the
 * app's memory, outside the guest's linear memory, so it must be capped. With the guest busy in a
 * hook (taking 64 KiB per poll), a host pushing faster gets a refused send at 48 MiB.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "ssc_frames.h"
#include "ssc_wasm_host.h"

static int failures;
#define CHECK(c, ...) do { if (!(c)) { failures++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)
#define S(x) ((ssc_slice){ (const uint8_t *)(x), sizeof(x) - 1 })

static void send_frame(ssc_wasm_session *s, const ssc_frame *f) {
    ssc_buf b = {0};
    ssc_encode_envelopes(f, &b);
    ssc_wasm_session_send(s, b.buf, b.len);
    ssc_buf_free(&b);
}

int main(void) {
    char err[160];
    ssc_wasm_session *s = ssc_wasm_session_open(0, err, sizeof err);
    if (!s) { printf("FAIL open: %s\n", err); return 1; }
    send_frame(s, &(ssc_frame){ .type = SSC_F_INIT, .call_id = 1, .version = 1, .api_level = 1,
                                .limits = { 16u << 20, 1u << 20, 5000, 60000, 64, 6, 64, 1u << 20, 100000, 512 },
                                .text = S("var source = { loop: function () { for (;;) {} } };") });
    send_frame(s, &(ssc_frame){ .type = SSC_F_INVOKE, .call_id = 2, .text = S("loop"), .text2 = S("[]") });

    /* Large frames the guest only takes 64 KiB at a time while the hook spins. */
    size_t big = 15u << 20;
    char *text = malloc(big);
    memset(text, 'x', big);
    ssc_buf frame = {0};
    ssc_encode_envelopes(&(ssc_frame){ .type = SSC_F_LOG, .text = { (const uint8_t *)text, big } }, &frame);
    size_t sent = 0, chunk = 1u << 20;
    int refused = 0;
    for (int i = 0; i < 200 && !refused; i++) {
        size_t off = (sent % frame.len);
        size_t n = frame.len - off < chunk ? frame.len - off : chunk;
        if (ssc_wasm_session_send(s, frame.buf + off, n) != 0) refused = 1;
        else sent += n;
    }
    CHECK(refused, "200 MiB pushed into a busy sandbox were all accepted");
    CHECK(sent <= (50u << 20), "accepted %zu MiB before refusing", sent >> 20);
    printf("  input refused after %zu MiB\n", sent >> 20);
    /* The refusal failed the session: nothing more is accepted and the spinning guest stops. */
    CHECK(ssc_wasm_session_send(s, (const uint8_t *)"x", 1) != 0, "a send after the refusal was accepted");
    struct timespec t0, t1;
    clock_gettime(CLOCK_MONOTONIC, &t0);
    uint8_t out[65536];
    long n;
    while ((n = ssc_wasm_session_receive(s, out, sizeof out, 1000)) != 0) {
        clock_gettime(CLOCK_MONOTONIC, &t1);
        if (t1.tv_sec - t0.tv_sec > 5) break;
    }
    clock_gettime(CLOCK_MONOTONIC, &t1);
    long ms = (long)((t1.tv_sec - t0.tv_sec) * 1000 + (t1.tv_nsec - t0.tv_nsec) / 1000000);
    CHECK(n == 0, "the guest still runs %ld ms after its session failed", ms);
    printf("  guest ended %ld ms after the refusal (status %d)\n", ms, ssc_wasm_session_status(s));
    ssc_buf_free(&frame);
    free(text);
    ssc_wasm_session_free(s); /* still spinning: the guest is stopped and freed */
    printf(failures ? "\n%d FAILURE(S)\n" : "\nALL PASSED\n", failures);
    return failures ? 1 : 0;
}
