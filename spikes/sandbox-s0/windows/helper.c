/*
 * ADR-0002 S0 Windows sandbox helper: the QuickJS core behind a pipe, driven by `launcher.exe
 * <mode> <mem> --bench helper.exe ...`. Same message shapes as the macOS helper, simplified:
 *
 *   frame = type(u8) len(u32 LE) payload
 *   host -> helper:  'I' u32 bootstrap_len, bootstrap, script     (init)
 *                    'V' method NUL args_json                       (invoke)
 *                    'B' reply_json                                 (answer to 'b'; empty = null)
 *                    'Q'                                            (quit)
 *   helper -> host:  'R' status(u8) json                            (init/invoke result)
 *                    'b' method NUL args_json                       (sync bridge call)
 *
 * The helper never opens files or sockets; stdin/stdout are its only channel.
 */
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "shinsou_sandbox_core.h"

static HANDLE in, out;

static int read_all(void *buf, DWORD n) {
    char *p = buf;
    while (n) {
        DWORD got = 0;
        if (!ReadFile(in, p, n, &got, NULL) || got == 0) return -1;
        p += got; n -= got;
    }
    return 0;
}

static int write_all(const void *buf, DWORD n) {
    const char *p = buf;
    while (n) {
        DWORD put = 0;
        if (!WriteFile(out, p, n, &put, NULL) || put == 0) return -1;
        p += put; n -= put;
    }
    return 0;
}

static int send_frame(char type, const void *a, uint32_t alen, const void *b, uint32_t blen) {
    uint32_t len = alen + blen;
    if (write_all(&type, 1) || write_all(&len, 4)) return -1;
    if (alen && write_all(a, alen)) return -1;
    if (blen && write_all(b, blen)) return -1;
    return 0;
}

/* Reads one frame into a malloc()ed buffer (NUL-terminated for convenience). */
static int recv_frame(char *type, char **payload, uint32_t *len) {
    if (read_all(type, 1) || read_all(len, 4)) return -1;
    if (*len > (64u << 20)) return -1;
    *payload = malloc(*len + 1);
    if (!*payload) return -1;
    if (*len && read_all(*payload, *len)) { free(*payload); return -1; }
    (*payload)[*len] = 0;
    return 0;
}

static uint64_t now_ms(void *o) { (void)o; return GetTickCount64(); }

static int bridge_call(void *o, const char *method, const char *args, size_t len, char **reply, size_t *reply_len) {
    (void)o;
    if (send_frame('b', method, (uint32_t)strlen(method) + 1, args, (uint32_t)len)) return -1;
    char type; char *p; uint32_t n;
    if (recv_frame(&type, &p, &n) || type != 'B') return -1;
    if (n == 0) { free(p); *reply = NULL; *reply_len = 0; return 0; }
    *reply = p; *reply_len = n;
    return 0;
}

static int async_start(void *o, uint32_t id, const char *req, size_t len) { (void)o; (void)id; (void)req; (void)len; return -1; }
static void async_cancel(void *o, uint32_t id) { (void)o; (void)id; }
static int async_wait(void *o, ssc_runtime *rt, uint64_t deadline) { (void)o; (void)rt; (void)deadline; return 0; }
static void log_msg(void *o, const char *m, size_t len) { (void)o; (void)m; (void)len; }

int main(void) {
    in = GetStdHandle(STD_INPUT_HANDLE);
    out = GetStdHandle(STD_OUTPUT_HANDLE);
    ssc_limits lim = { 64u << 20, 1u << 20, 5000, 10000, 4096, 6, 64, 4u << 20, 100000, 512 };
    ssc_host host = { NULL, now_ms, bridge_call, async_start, async_cancel, async_wait, log_msg };
    ssc_runtime *rt = ssc_new(&lim, &host);
    if (!rt) return 3;
    for (;;) {
        char type; char *p; uint32_t n;
        if (recv_frame(&type, &p, &n)) break;
        if (type == 'Q') { free(p); break; }
        uint8_t st = SSC_ERR_BAD_ARGUMENT;
        char *res = NULL; size_t res_len = 0;
        if (type == 'I' && n >= 4) {
            uint32_t blen; memcpy(&blen, p, 4);
            if (blen <= n - 4) st = (uint8_t)ssc_init(rt, p + 4, blen, p + 4 + blen, n - 4 - blen);
        } else if (type == 'V') {
            size_t mlen = strnlen(p, n);
            if (mlen < n) st = (uint8_t)ssc_invoke(rt, p, p + mlen + 1, &res, &res_len);
        }
        free(p);
        int bad = send_frame('R', &st, 1, res, (uint32_t)res_len);
        free(res);
        if (bad) break;
    }
    ssc_free(rt);
    return 0;
}
