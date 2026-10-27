/*
 * shinsou-sandbox: the desktop sandbox helper (ADR-0002 §4.4). Speaks SandboxProtocol v1 on
 * stdin/stdout and nothing else: QuickJS is built without quickjs-libc, so scripts have no file,
 * process, socket or environment access. The OS sandbox (macOS sandbox_init, Windows LPAC + Job)
 * is applied by the platform launcher around this process (S3).
 */
#include <errno.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <time.h>
#include <unistd.h>

#include "ssc_driver.h"

extern const char ssc_bootstrap_js[];
extern const size_t ssc_bootstrap_js_len;

static long read_fd(void *o, uint8_t *buf, size_t len) {
    (void)o;
    for (;;) {
        ssize_t n = read(STDIN_FILENO, buf, len);
        if (n < 0 && errno == EINTR) continue;
        return (long)n;
    }
}

static int write_fd(void *o, const uint8_t *buf, size_t len) {
    (void)o;
    while (len) {
        ssize_t n = write(STDOUT_FILENO, buf, len);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return -1;
        buf += n;
        len -= (size_t)n;
    }
    return 0;
}

static uint64_t now_ms(void *o) {
    (void)o;
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000u + (uint64_t)ts.tv_nsec / 1000000u;
}

static int status;

static void *run(void *o) {
    (void)o;
    ssc_io io = { NULL, read_fd, write_fd, now_ms };
    status = ssc_driver_run(&io, ssc_bootstrap_js, ssc_bootstrap_js_len);
    return NULL;
}

int main(void) {
    signal(SIGPIPE, SIG_IGN); /* a vanished host shows up as a failed write, handled by the driver */
    /* A dedicated thread with a known stack: the core's stack limit must fit inside it. */
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setstacksize(&attr, SSC_DRIVER_THREAD_STACK);
    pthread_t t;
    if (pthread_create(&t, &attr, run, NULL) != 0) return SSC_DRIVER_NOMEM;
    pthread_join(t, NULL);
    return status;
}
