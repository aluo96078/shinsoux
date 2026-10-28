/*
 * shinsou-sandbox: the desktop sandbox helper (ADR-0002 §4.4). Speaks SandboxProtocol v1 on
 * stdin/stdout and nothing else: QuickJS is built without quickjs-libc, so scripts have no file,
 * process, socket or environment access. Before reading anything it confines itself
 * (ssc_os_lockdown: macOS sandbox_init deny-all; Windows relaunches itself in an LPAC and a Job
 * object, and this process then only waits for that confined copy).
 */
#include <errno.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <time.h>
#include <unistd.h>

#ifdef _WIN32
#include <fcntl.h>
#include <io.h>
#endif

#include "ssc_driver.h"
#include "ssc_os_sandbox.h"

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
    ssc_io io = { NULL, read_fd, write_fd, now_ms, NULL };
    status = ssc_driver_run(&io, ssc_bootstrap_js, ssc_bootstrap_js_len);
    return NULL;
}

int main(int argc, char **argv) {
    char err[96];
    int launched_exit = 0;
    int lockdown = ssc_os_lockdown(&argc, &argv, &launched_exit, err, sizeof err);
    if (lockdown == SSC_OS_LAUNCHED) return launched_exit;
    if (lockdown != SSC_OS_CONFINED) {
        /* Fail closed: not one frame is read, so no script is ever evaluated unconfined. */
        static const char msg[] = "shinsou-sandbox: lockdown failed\n";
        (void)!write(STDERR_FILENO, msg, sizeof msg - 1);
        return SSC_DRIVER_LOCKDOWN;
    }
#ifdef _WIN32
    /* Frames are bytes: no CRLF translation, no ^Z as end of file. */
    if (_setmode(STDIN_FILENO, _O_BINARY) == -1 || _setmode(STDOUT_FILENO, _O_BINARY) == -1) return SSC_DRIVER_LOCKDOWN;
#else
    signal(SIGPIPE, SIG_IGN); /* a vanished host shows up as a failed write, handled by the driver */
#endif
    /* A dedicated thread with a known stack: the core's stack limit must fit inside it. */
    /* Fail closed if the stack cannot be had: QuickJS would trust a limit the thread lacks. */
    pthread_attr_t attr;
    pthread_t t;
    if (pthread_attr_init(&attr) != 0) return SSC_DRIVER_NOMEM;
    int rc = pthread_attr_setstacksize(&attr, SSC_DRIVER_THREAD_STACK);
    if (rc == 0) rc = pthread_create(&t, &attr, run, NULL);
    pthread_attr_destroy(&attr);
    if (rc != 0) return SSC_DRIVER_NOMEM; /* before a single byte of input is read */
    pthread_join(t, NULL);
    return status;
}
