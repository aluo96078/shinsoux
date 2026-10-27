#define _GNU_SOURCE
/*
 * Frame driver end to end in C: the test plays the host over a socket pair, exactly as the Kotlin
 * SandboxSession does (Init -> Ready -> Result, Invoke with bridge round trips, Cancel, violations).
 */
#include <errno.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

#include "ssc_driver.h"
#include "ssc_frames.h"
#ifdef SSC_DRIVER_TEST_WASM
#include "ssc_wasm_host.h"
#endif

extern const char ssc_bootstrap_js[];
extern const size_t ssc_bootstrap_js_len;

static int failures;
#define CHECK(c, ...) do { if (!(c)) { failures++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

static uint64_t now_ms(void *o) {
    (void)o;
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000u + (uint64_t)ts.tv_nsec / 1000000u;
}

static long sock_read(void *o, uint8_t *buf, size_t len) {
    for (;;) {
        ssize_t n = read(*(int *)o, buf, len);
        if (n < 0 && errno == EINTR) continue;
        return (long)n;
    }
}

/* The single-threaded driver's pull: what the WASM host's import does, here over the socket. */
static long sock_read_wait(void *o, uint8_t *buf, size_t len, uint64_t timeout_ms) {
    struct pollfd p = { *(int *)o, POLLIN, 0 };
    for (;;) {
        int r = poll(&p, 1, (int)timeout_ms);
        if (r < 0 && errno == EINTR) continue;
        if (r < 0) return -1;
        if (r == 0) return SSC_IO_TIMEOUT;
        return sock_read(o, buf, len);
    }
}

static int sock_write(void *o, const uint8_t *buf, size_t len) {
    while (len) {
        ssize_t n = write(*(int *)o, buf, len);
        if (n <= 0) return -1;
        buf += n;
        len -= (size_t)n;
    }
    return 0;
}

typedef struct {
    int fd;
    ssc_io io;
    int status;
    pthread_t thread;
#ifdef SSC_DRIVER_TEST_WASM
    ssc_wasm_session *ws;
#endif
} sandbox;

#ifdef SSC_DRIVER_TEST_WASM
/* The iOS sandbox: the guest runs in WAMR; this thread and the next relay the socket to it. */
static void *wasm_input(void *o) {
    sandbox *s = o;
    uint8_t buf[65536];
    for (;;) {
        ssize_t n = read(s->fd, buf, sizeof buf);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0 || ssc_wasm_session_send(s->ws, buf, (size_t)n) != 0) break;
    }
    ssc_wasm_session_close_input(s->ws); /* the host went away: end of stream for the guest */
    return NULL;
}

static void *sandbox_main(void *o) {
    sandbox *s = o;
    char err[160];
    s->ws = ssc_wasm_session_open(0, err, sizeof err);
    if (!s->ws) {
        fprintf(stderr, "wasm sandbox: %s\n", err);
        s->status = -1;
        shutdown(s->fd, SHUT_RDWR);
        close(s->fd);
        return NULL;
    }
    pthread_t input;
    pthread_create(&input, NULL, wasm_input, s);
    uint8_t buf[65536];
    int status = -1; /* the host stopped reading: the guest is terminated, not ended */
    for (;;) {
        long n = ssc_wasm_session_receive(s->ws, buf, sizeof buf, 1000);
        if (n == SSC_WASM_TIMEOUT) continue;
        if (n == 0) { status = ssc_wasm_session_status(s->ws); break; }
        if (n < 0 || sock_write(&s->fd, buf, (size_t)n) != 0) break;
    }
    /* Stop the relay before freeing the session it writes to. */
    shutdown(s->fd, SHUT_RDWR);
    pthread_join(input, NULL);
    ssc_wasm_session_free(s->ws);
    s->status = status;
    close(s->fd);
    return NULL;
}
#else
static void *sandbox_main(void *o) {
    sandbox *s = o;
    s->status = ssc_driver_run(&s->io, ssc_bootstrap_js, ssc_bootstrap_js_len);
    shutdown(s->fd, SHUT_RDWR); /* wakes a reader still blocked in read() */
    close(s->fd);
    return NULL;
}
#endif

typedef struct {
    int fd;
    ssc_reader *reader;
    ssc_frame queue[64];
    int head, tail;
    sandbox sb;
} host;

static void on_frame(void *o, ssc_frame *f) {
    host *h = o;
    h->queue[h->tail++ % 64] = *f;
}

static void start(host *h) {
    memset(h, 0, sizeof *h);
    int fds[2];
    socketpair(AF_UNIX, SOCK_STREAM, 0, fds);
    h->fd = fds[0];
    h->sb.fd = fds[1];
    /* The driver may leave its reader thread blocked in read() (detached) when it stops on a
     * violation; that thread still reads its descriptor after `h` is reused, so each sandbox gets
     * its own never-reused slot instead of a pointer into `h`. */
    static int fd_slots[256];
    static int next_slot;
    int *slot = &fd_slots[next_slot++ % 256];
    *slot = fds[1];
    h->sb.io = (ssc_io){ slot, sock_read, sock_write, now_ms, sock_read_wait };
    h->reader = ssc_reader_new(SSC_PEER_HOST, 8u << 20, NULL);
    /* The core trusts the stack limit it is given (1 MiB here): the thread that runs it must
     * really have that much and more. Secondary threads get 512 KiB by default on macOS. */
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setstacksize(&attr, 16u << 20);
    pthread_create(&h->sb.thread, &attr, sandbox_main, &h->sb);
    pthread_attr_destroy(&attr);
}

static int stop(host *h) {
    shutdown(h->fd, SHUT_WR);
    pthread_join(h->sb.thread, NULL);
    close(h->fd);
    while (h->head != h->tail) ssc_frame_free(&h->queue[h->head++ % 64]);
    ssc_reader_free(h->reader);
    return h->sb.status;
}

static void send_frame(host *h, const ssc_frame *f) {
    ssc_buf b = {0};
    ssc_encode_envelopes(f, &b);
    sock_write(&h->fd, b.buf, b.len);
    ssc_buf_free(&b);
}

/* Next frame from the sandbox; type 0 (CHUNK, never delivered) means end of stream. */
static ssc_frame recv_frame(host *h) {
    while (h->head == h->tail) {
        uint8_t buf[65536];
        ssize_t n = read(h->fd, buf, sizeof buf);
        if (n <= 0) return (ssc_frame){ .type = SSC_F_CHUNK };
        if (ssc_reader_feed(h->reader, buf, (size_t)n, on_frame, h) != SSC_FR_OK) return (ssc_frame){ .type = SSC_F_CHUNK };
    }
    return h->queue[h->head++ % 64];
}

static ssc_slice S(const char *s) { return (ssc_slice){ (const uint8_t *)s, strlen(s) }; }

static int text_is(const ssc_frame *f, const char *want) {
    return f->text.len == strlen(want) && memcmp(f->text.ptr, want, f->text.len) == 0;
}

static void init(host *h, const char *script) {
    ssc_frame f = { .type = SSC_F_INIT, .call_id = 1, .version = 1, .api_level = 1,
                    .limits = { 16u << 20, 1u << 20, 2000, 1500, 64, 6, 64, 1u << 20, 100000, 512 }, .text = S(script) };
    send_frame(h, &f);
    ssc_frame ready = recv_frame(h);
    CHECK(ready.type == SSC_F_READY && ready.call_id == 1 && ready.version == 1, "Ready");
    ssc_frame_free(&ready);
    ssc_frame done = recv_frame(h);
    CHECK(done.type == SSC_F_RESULT && done.call_id == 1, "init Result (type %d code %u)", done.type, done.code);
    ssc_frame_free(&done);
}

static const char SCRIPT[] =
    "var source={name:'t',supportsLatest:true,"
    " ok:function(){return 'ok';},"
    " fetchTitle:function(p){var body=bridge.httpGet('https://x.test/'+p);return {title:body};},"
    " denied:function(){var r=bridge.getCredentialPassword();return r;},"
    " async:async function(){var r=await bridge.net.fetch('https://x.test/a',{method:'POST',body:'q'});return r.text()+':'+r.status;},"
    " loop:function(){for(;;){}},"
    " log:function(){console.log('hello');return 1;}"
    "};";

static void test_init_metadata_and_bridge(void) {
    host h;
    start(&h);
    init(&h, SCRIPT);

    /* Reserved entry points */
    send_frame(&h, &(ssc_frame){ .type = SSC_F_INVOKE, .call_id = 2, .text = S("__shinsouHasFunction"), .text2 = S("[\"fetchTitle\"]") });
    ssc_frame r = recv_frame(&h);
    CHECK(r.type == SSC_F_RESULT && text_is(&r, "true"), "__shinsouHasFunction -> %.*s", (int)r.text.len, r.text.ptr);
    ssc_frame_free(&r);
    send_frame(&h, &(ssc_frame){ .type = SSC_F_INVOKE, .call_id = 3, .text = S("__shinsouMetadata"), .text2 = S("[]") });
    r = recv_frame(&h);
    CHECK(r.type == SSC_F_RESULT && r.text.len > 2 && memmem(r.text.ptr, r.text.len, "supportsLatest", 14), "__shinsouMetadata -> %.*s", (int)r.text.len, r.text.ptr);
    ssc_frame_free(&r);

    /* Sync bridge round trip */
    send_frame(&h, &(ssc_frame){ .type = SSC_F_INVOKE, .call_id = 4, .text = S("fetchTitle"), .text2 = S("[\"p1\"]") });
    ssc_frame call = recv_frame(&h);
    CHECK(call.type == SSC_F_BRIDGE_CALL && call.call_id == 4 && call.id == 1 && text_is(&call, "httpGet"), "BridgeCall");
    CHECK(call.text2.len && memmem(call.text2.ptr, call.text2.len, "https://x.test/p1", 17), "bridge args");
    send_frame(&h, &(ssc_frame){ .type = SSC_F_BRIDGE_RESULT, .id = call.id, .ok = 1, .has_value = 1, .text = S("\"Title One\"") });
    ssc_frame_free(&call);
    r = recv_frame(&h);
    CHECK(r.type == SSC_F_RESULT && r.call_id == 4 && text_is(&r, "{\"title\":\"Title One\"}"), "fetchTitle -> %.*s", (int)r.text.len, r.text.ptr);
    ssc_frame_free(&r);

    /* A refused bridge call becomes the reviewed engines' error object. */
    send_frame(&h, &(ssc_frame){ .type = SSC_F_INVOKE, .call_id = 5, .text = S("denied"), .text2 = S("[]") });
    call = recv_frame(&h);
    send_frame(&h, &(ssc_frame){ .type = SSC_F_BRIDGE_RESULT, .id = call.id, .ok = 0, .code = 33 });
    ssc_frame_free(&call);
    r = recv_frame(&h);
    CHECK(r.type == SSC_F_RESULT && text_is(&r, "{\"error\":\"PERMISSION_DENIED\"}"), "denied -> %.*s", (int)r.text.len, r.text.ptr);
    ssc_frame_free(&r);

    /* net.fetch: AsyncBridgeCall with the bridge-style argument array */
    send_frame(&h, &(ssc_frame){ .type = SSC_F_INVOKE, .call_id = 6, .text = S("async"), .text2 = S("[]") });
    call = recv_frame(&h);
    CHECK(call.type == SSC_F_ASYNC_BRIDGE_CALL && call.call_id == 6 && text_is(&call, "net.fetch"), "AsyncBridgeCall");
    CHECK(call.text2.len && call.text2.ptr[0] == '[' && memmem(call.text2.ptr, call.text2.len, "\"method\":\"POST\"", 15), "async args %.*s", (int)call.text2.len, call.text2.ptr);
    send_frame(&h, &(ssc_frame){ .type = SSC_F_ASYNC_BRIDGE_RESULT, .id = call.id, .ok = 1, .has_value = 1, .text = S("{\"status\":201,\"headers\":{},\"body\":\"done\"}") });
    ssc_frame_free(&call);
    r = recv_frame(&h);
    CHECK(r.type == SSC_F_RESULT && text_is(&r, "\"done:201\""), "async -> %.*s", (int)r.text.len, r.text.ptr);
    ssc_frame_free(&r);

    /* console.log is a local Log frame, not a bridge call */
    send_frame(&h, &(ssc_frame){ .type = SSC_F_INVOKE, .call_id = 7, .text = S("log"), .text2 = S("[]") });
    r = recv_frame(&h);
    CHECK(r.type == SSC_F_LOG, "Log frame (type %d)", r.type);
    ssc_frame_free(&r);
    r = recv_frame(&h);
    CHECK(r.type == SSC_F_RESULT && r.call_id == 7, "log result");
    ssc_frame_free(&r);

    /* Cancel stops a CPU-bound hook through the reader thread. */
    send_frame(&h, &(ssc_frame){ .type = SSC_F_INVOKE, .call_id = 8, .text = S("loop"), .text2 = S("[]") });
    usleep(100 * 1000);
    uint64_t t0 = now_ms(NULL);
    send_frame(&h, &(ssc_frame){ .type = SSC_F_CANCEL, .call_id = 8 });
    r = recv_frame(&h);
    uint64_t dt = now_ms(NULL) - t0;
    CHECK(r.type == SSC_F_FAILURE && r.call_id == 8 && r.code == 7, "cancel -> type %d code %u", r.type, r.code);
    CHECK(dt < 500, "cancel took %llu ms", (unsigned long long)dt);
    printf("  cancel of a busy loop          -> CANCELLED in %llu ms\n", (unsigned long long)dt);
    ssc_frame_free(&r);

    /* A stale cancel for call 8 does not touch call 9. */
    send_frame(&h, &(ssc_frame){ .type = SSC_F_CANCEL, .call_id = 8 });
    send_frame(&h, &(ssc_frame){ .type = SSC_F_INVOKE, .call_id = 9, .text = S("ok"), .text2 = S("[]") });
    r = recv_frame(&h);
    CHECK(r.type == SSC_F_RESULT && r.call_id == 9, "after stale cancel");
    ssc_frame_free(&r);

    /* The sandbox's own wall-time limit. */
    send_frame(&h, &(ssc_frame){ .type = SSC_F_INVOKE, .call_id = 10, .text = S("loop"), .text2 = S("[]") });
    r = recv_frame(&h);
    CHECK(r.type == SSC_F_FAILURE && r.code == 4, "time limit -> %u", r.code);
    ssc_frame_free(&r);

    int st = stop(&h);
    CHECK(st == SSC_DRIVER_EOF, "clean exit %d", st);
    printf("  init, entry points, bridge, async, log, cancel, limits: done\n");
}

/* Codex review regressions: bridge waits are bounded, stale answers ignored, floods refused. */
static void test_bridge_waits_and_floods(void) {
    host h;
    start(&h);
    init(&h, SCRIPT);

    /* Cancel while the hook waits for a bridge answer that never comes. */
    send_frame(&h, &(ssc_frame){ .type = SSC_F_INVOKE, .call_id = 2, .text = S("fetchTitle"), .text2 = S("[\"p\"]") });
    ssc_frame call = recv_frame(&h);
    uint64_t stale = call.id;
    ssc_frame_free(&call);
    uint64_t t0 = now_ms(NULL);
    send_frame(&h, &(ssc_frame){ .type = SSC_F_CANCEL, .call_id = 2 });
    ssc_frame r = recv_frame(&h);
    CHECK(r.type == SSC_F_FAILURE && r.code == 7, "cancel during bridge wait -> type %d code %u", r.type, r.code);
    CHECK(now_ms(NULL) - t0 < 500, "cancel during bridge wait was slow");
    ssc_frame_free(&r);

    /* The late answer to that call is ignored; the next call (a higher seq) still works. */
    send_frame(&h, &(ssc_frame){ .type = SSC_F_BRIDGE_RESULT, .id = stale, .ok = 1, .has_value = 1, .text = S("\"late\"") });
    send_frame(&h, &(ssc_frame){ .type = SSC_F_INVOKE, .call_id = 3, .text = S("fetchTitle"), .text2 = S("[\"q\"]") });
    call = recv_frame(&h);
    CHECK(call.type == SSC_F_BRIDGE_CALL && call.id > stale, "seq keeps increasing across invocations (%llu > %llu)", (unsigned long long)call.id, (unsigned long long)stale);
    send_frame(&h, &(ssc_frame){ .type = SSC_F_BRIDGE_RESULT, .id = call.id, .ok = 1, .has_value = 1, .text = S("\"fresh\"") });
    ssc_frame_free(&call);
    r = recv_frame(&h);
    CHECK(r.type == SSC_F_RESULT && text_is(&r, "{\"title\":\"fresh\"}"), "after a stale answer -> %.*s", (int)r.text.len, r.text.ptr);
    ssc_frame_free(&r);

    /* No answer at all: the invocation's own deadline (1.5 s) ends the wait. */
    send_frame(&h, &(ssc_frame){ .type = SSC_F_INVOKE, .call_id = 4, .text = S("fetchTitle"), .text2 = S("[\"z\"]") });
    call = recv_frame(&h);
    ssc_frame_free(&call);
    t0 = now_ms(NULL);
    r = recv_frame(&h);
    uint64_t waited = now_ms(NULL) - t0;
    CHECK(r.type == SSC_F_FAILURE && r.code == 4, "unanswered bridge call -> type %d code %u", r.type, r.code);
    CHECK(waited >= 1200 && waited < 3000, "unanswered bridge call ended after %llu ms", (unsigned long long)waited);
    printf("  unanswered bridge call          -> LIMIT_TIME after %llu ms\n", (unsigned long long)waited);
    ssc_frame_free(&r);
    CHECK(stop(&h) == SSC_DRIVER_EOF, "clean exit");

    /* A host flooding frames while a hook runs is refused instead of growing memory. */
    start(&h);
    init(&h, SCRIPT);
    send_frame(&h, &(ssc_frame){ .type = SSC_F_INVOKE, .call_id = 2, .text = S("loop"), .text2 = S("[]") });
    ssc_buf flood = {0};
    for (int i = 0; i < 5000; i++) ssc_encode_envelopes(&(ssc_frame){ .type = SSC_F_ASYNC_CANCEL, .id = (uint64_t)i + 1 }, &flood);
    sock_write(&h.fd, flood.buf, flood.len);
    ssc_buf_free(&flood);
    for (;;) { r = recv_frame(&h); int t = r.type; ssc_frame_free(&r); if (t == SSC_F_CHUNK) break; }
    CHECK(stop(&h) == SSC_DRIVER_VIOLATION, "flood is a violation");
    printf("  bridge waits bounded, stale answers ignored, floods refused: done\n");
}

static void test_version_mismatch(void) {
    host h;
    start(&h);
    send_frame(&h, &(ssc_frame){ .type = SSC_F_INIT, .call_id = 1, .version = 2, .api_level = 1,
                                 .limits = {1, 1, 1, 1, 1, 1, 1, 1, 1, 1}, .text = S("x") });
    ssc_frame r = recv_frame(&h);
    CHECK(r.type == SSC_F_READY && r.version == 1, "Ready carries the sandbox's version (type %d version %u)", r.type, (unsigned)r.version);
    ssc_frame_free(&r);
    int st = stop(&h);
    CHECK(st == SSC_DRIVER_EOF, "exit %d", st);
}

static void test_violations(void) {
    /* Garbage on the wire */
    host h;
    int st;
    start(&h);
    sock_write(&h.fd, (const uint8_t *)"\x00\x00\x00\x02\x13\x00", 6);
    ssc_frame r = recv_frame(&h);
    CHECK(r.type == SSC_F_CHUNK, "the driver exits without answering");
    CHECK(stop(&h) == SSC_DRIVER_VIOLATION, "garbage is a violation");

    /* Invoke before Init */
    start(&h);
    send_frame(&h, &(ssc_frame){ .type = SSC_F_INVOKE, .call_id = 2, .text = S("ok"), .text2 = S("[]") });
    r = recv_frame(&h);
    CHECK(r.type == SSC_F_CHUNK, "no answer to Invoke before Init");
    st = stop(&h);
    CHECK(st == SSC_DRIVER_VIOLATION, "Invoke before Init is a violation (exit %d)", st);

    /* A BridgeResult for the wrong seq */
    start(&h);
    init(&h, SCRIPT);
    send_frame(&h, &(ssc_frame){ .type = SSC_F_INVOKE, .call_id = 2, .text = S("fetchTitle"), .text2 = S("[\"p\"]") });
    ssc_frame call = recv_frame(&h);
    send_frame(&h, &(ssc_frame){ .type = SSC_F_BRIDGE_RESULT, .id = call.id + 5, .ok = 1, .has_value = 0 });
    ssc_frame_free(&call);
    for (;;) { r = recv_frame(&h); int t = r.type; ssc_frame_free(&r); if (t == SSC_F_CHUNK) break; }
    CHECK(stop(&h) == SSC_DRIVER_VIOLATION, "wrong seq is a violation");
    printf("  version mismatch and violations: done\n");
}

static uint64_t mono_ms(void) { return now_ms(NULL); }

/* The host closes its end (or dies) while a hook spins: the driver must stop it right away, not at
 * its 30-second deadline, so a sandbox never outlives its host for long (ADR-0002 S3). */
static void test_host_gone_mid_hook(void) {
    host h;
    start(&h);
    ssc_frame f = { .type = SSC_F_INIT, .call_id = 1, .version = 1, .api_level = 1,
                    .limits = { 16u << 20, 1u << 20, 2000, 30000, 64, 6, 64, 1u << 20, 100000, 512 }, .text = S(SCRIPT) };
    send_frame(&h, &f);
    for (int i = 0; i < 2; i++) { ssc_frame r = recv_frame(&h); ssc_frame_free(&r); } /* Ready, Result */
    send_frame(&h, &(ssc_frame){ .type = SSC_F_INVOKE, .call_id = 2, .text = S("loop"), .text2 = S("[]") });
    usleep(200 * 1000);
    uint64_t t0 = mono_ms();
    int st = stop(&h);
    uint64_t ms = mono_ms() - t0;
    CHECK(ms < 2000, "the driver outlived its host by %llu ms", (unsigned long long)ms);
    CHECK(st == SSC_DRIVER_EOF, "exit %d", st);
    printf("  host gone mid-hook: driver exited %llu ms after end of stream\n", (unsigned long long)ms);
}

int main(void) {
    signal(SIGPIPE, SIG_IGN); /* the sandbox may close first; writes then fail with EPIPE */
    setvbuf(stdout, NULL, _IONBF, 0);
    printf("[driver]\n");
    test_init_metadata_and_bridge();
    test_bridge_waits_and_floods();
    test_version_mismatch();
    test_violations();
    test_host_gone_mid_hook();
    printf(failures ? "\n%d FAILURE(S)\n" : "\nALL PASSED\n", failures);
    return failures ? 1 : 0;
}
