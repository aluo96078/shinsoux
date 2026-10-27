/*
 * libshinsou_sandbox.so: the sandbox entry point inside Android's isolated process (ADR-0002 §4.3,
 * S2). The service hands over two pipe ends and the driver speaks SandboxProtocol v1 on them,
 * exactly like the desktop helper on stdin/stdout. Exported: SandboxNative.nativeLockdown,
 * nativeRun and nativeProbe (dev.shinsou.kmp.plugin.sandbox).
 */
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <pthread.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdint.h>
#include <sys/socket.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#include "ssc_driver.h"

extern const char ssc_bootstrap_js[];
extern const size_t ssc_bootstrap_js_len;

/* ---- lockdown ---------------------------------------------------------------------------- */

#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <sched.h>
#include <stddef.h>
#include <sys/prctl.h>
#include <sys/syscall.h>

#if defined(__aarch64__)
#define SSC_AUDIT_ARCH AUDIT_ARCH_AARCH64
#elif defined(__x86_64__)
#define SSC_AUDIT_ARCH AUDIT_ARCH_X86_64
#elif defined(__arm__)
#define SSC_AUDIT_ARCH AUDIT_ARCH_ARM
#else
#error "unsupported Android ABI"
#endif

static int lockdown_status = 1; /* 1 = not attempted */

/*
 * Isolated processes may still fork/exec system binaries (S0 device finding). Before untrusted
 * code runs, forbid execve/execveat and every clone that is not a thread; threads (CLONE_THREAD)
 * stay allowed for ART and the runtime. Stacks on top of the platform app filter; TSYNC applies it
 * to every thread of the process, including binder threads. Returns 0, or a negative value on any
 * failure: the caller must then refuse to run untrusted code.
 */
static int lockdown(void) {
    if (lockdown_status <= 0) return lockdown_status;
    struct sock_filter f[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SSC_AUDIT_ARCH, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
#if defined(__x86_64__)
        /* x32 syscalls share AUDIT_ARCH_X86_64 but use other numbers: refuse the whole ABI. */
        BPF_JUMP(BPF_JMP | BPF_JGE | BPF_K, 0x40000000u, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
#endif
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_execve, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_execveat, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
#ifdef __NR_clone3
        /* clone3 hides its flags in memory; ENOSYS makes libc fall back to clone(2). */
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_clone3, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | ENOSYS),
#endif
#ifdef __NR_fork
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_fork, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
#endif
#ifdef __NR_vfork
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_vfork, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
#endif
#ifdef __NR_socketcall
        /* The multiplexed socket entry point hides its arguments in memory: refuse it outright. */
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_socketcall, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | ENOSYS),
#endif
        /*
         * Network, on top of the isolated uid lacking the inet group: only AF_UNIX sockets (logd,
         * socketpair) can be created; every other family (inet, netlink, packet, ...) fails with
         * EAFNOSUPPORT, an answer the kernel never gives for AF_INET, so the self-check can tell
         * this filter from SELinux (EACCES). The domain is argument 0 of socket and socketpair on
         * every supported ABI.
         */
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_socket, 1, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_socketpair, 0, 4),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[0])),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AF_UNIX, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EAFNOSUPPORT),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_clone, 0, 4),
        /* clone flags are argument 0 (low 32 bits) on aarch64, arm and x86_64. */
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[0])),
        BPF_JUMP(BPF_JMP | BPF_JSET | BPF_K, CLONE_THREAD, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog prog = { (unsigned short)(sizeof f / sizeof f[0]), f };
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) return lockdown_status = -errno;
    long r = syscall(__NR_seccomp, SECCOMP_SET_MODE_FILTER, SECCOMP_FILTER_FLAG_TSYNC, &prog);
    if (r == -1) return lockdown_status = -errno;
    /* With TSYNC a positive return is the TID of a thread that could not be synchronized. */
    if (r != 0) return lockdown_status = -ESRCH;
    return lockdown_status = 0;
}

JNIEXPORT jint JNICALL Java_dev_shinsou_kmp_plugin_sandbox_SandboxNative_nativeLockdown(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return lockdown();
}

/* ---- driver ------------------------------------------------------------------------------ */

typedef struct {
    int in, out;
    int status;
} pipes;

/*
 * Static, not on a stack: when the driver stops on a violation or host failure it may detach its
 * reader thread while that thread is still blocked in read(), and the reader keeps using the io
 * table and the input descriptor until the process exits (right after nativeRun returns). One
 * run per process (SandboxProcessService.start allows a single start).
 */
static pipes run_pipes;
static ssc_io run_io;
static atomic_flag run_started = ATOMIC_FLAG_INIT;

static long read_fd(void *o, uint8_t *buf, size_t len) {
    pipes *p = o;
    for (;;) {
        ssize_t n = read(p->in, buf, len);
        if (n < 0 && errno == EINTR) continue;
        return (long)n;
    }
}

static int write_fd(void *o, const uint8_t *buf, size_t len) {
    pipes *p = o;
    while (len) {
        ssize_t n = write(p->out, buf, len);
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

static void *run(void *o) {
    (void)o;
    run_io = (ssc_io){ &run_pipes, read_fd, write_fd, now_ms };
    run_pipes.status = ssc_driver_run(&run_io, ssc_bootstrap_js, ssc_bootstrap_js_len);
    return NULL;
}

/*
 * Runs the driver until the host closes its end; takes ownership of both descriptors. Refuses
 * (without reading a byte, so no script is ever evaluated) unless the lockdown is in place and
 * this is the process's first run. The caller exits the process when it returns.
 */
JNIEXPORT jint JNICALL Java_dev_shinsou_kmp_plugin_sandbox_SandboxNative_nativeRun(
    JNIEnv *env, jclass cls, jint in_fd, jint out_fd) {
    (void)env; (void)cls;
    if (lockdown_status != 0 || atomic_flag_test_and_set(&run_started)) {
        close(in_fd);
        close(out_fd);
        return -1;
    }
    run_pipes = (pipes){ in_fd, out_fd, SSC_DRIVER_IO_ERROR };
    signal(SIGPIPE, SIG_IGN); /* a vanished host shows up as a failed write */
    /* A dedicated thread with a known stack: the core's stack limit must fit inside it. */
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setstacksize(&attr, SSC_DRIVER_THREAD_STACK);
    pthread_t t;
    if (pthread_create(&t, &attr, run, NULL) != 0) {
        run_pipes.status = SSC_DRIVER_NOMEM;
    } else {
        pthread_join(t, NULL);
    }
    pthread_attr_destroy(&attr);
    /*
     * The output end closes now, so the host sees end of stream at once. The input end stays open:
     * a detached reader may still be blocked on it, and closing it could hand its number to a new
     * descriptor under that read. It goes when the process exits.
     */
    close(out_fd);
    return run_pipes.status;
}

/* ---- isolation self-check ---------------------------------------------------------------- */

enum {
    PROBE_INET_SOCKET = 1 << 0,
    PROBE_INET6_SOCKET = 1 << 1,
    PROBE_OPEN_PATH = 1 << 2,
    PROBE_FORK = 1 << 3,
    PROBE_EXEC = 1 << 4,
    PROBE_OTHER_SOCKET = 1 << 5,
    PROBE_UNIX_SOCKET_BROKEN = 1 << 6,
    PROBE_FILTER_SILENT = 1 << 7,
};

/*
 * Tries what the isolated process must not be able to do and returns a bit per operation that
 * unexpectedly succeeded (0 = isolated). `path` is a host file the sandbox must not open. exec is
 * probed with a missing binary: the filter answers EPERM before the kernel would say ENOENT, so
 * nothing ever replaces this process.
 */
JNIEXPORT jint JNICALL Java_dev_shinsou_kmp_plugin_sandbox_SandboxNative_nativeProbe(JNIEnv *env, jclass cls, jstring path) {
    (void)cls;
    int leaks = 0;
    /*
     * Raw syscalls, not libc: bionic's socket() asks netd's client library first and fails with
     * EPERM without entering the kernel, which native code can simply skip. EAFNOSUPPORT shows the
     * seccomp filter answered (SELinux alone says EACCES, the inet-group check EPERM).
     */
    long s = syscall(__NR_socket, AF_INET, SOCK_STREAM, 0);
    if (s >= 0) { leaks |= PROBE_INET_SOCKET; close((int)s); }
    else if (errno != EAFNOSUPPORT) leaks |= PROBE_FILTER_SILENT;
    s = syscall(__NR_socket, AF_INET6, SOCK_DGRAM, 0);
    if (s >= 0) { leaks |= PROBE_INET6_SOCKET; close((int)s); }
    static const int other[] = { AF_NETLINK, AF_PACKET, AF_BLUETOOTH, AF_VSOCK };
    for (size_t i = 0; i < sizeof other / sizeof other[0]; i++) {
        s = syscall(__NR_socket, other[i], SOCK_DGRAM, 0);
        if (s >= 0) { leaks |= PROBE_OTHER_SOCKET; close((int)s); }
    }
    /* The filter must leave AF_UNIX alone (logging, socketpair). */
    int pair[2];
    if (syscall(__NR_socketpair, AF_UNIX, SOCK_STREAM, 0, pair) == 0) { close(pair[0]); close(pair[1]); } else leaks |= PROBE_UNIX_SOCKET_BROKEN;
    const char *p = path ? (*env)->GetStringUTFChars(env, path, NULL) : NULL;
    if (p) {
        int fd = open(p, O_RDONLY | O_CLOEXEC);
        if (fd >= 0) { leaks |= PROBE_OPEN_PATH; close(fd); }
        (*env)->ReleaseStringUTFChars(env, path, p);
    }
    pid_t child = fork();
    if (child == 0) _exit(0);
    if (child > 0) { leaks |= PROBE_FORK; waitpid(child, NULL, 0); }
    char *argv[] = { "ssc-probe", NULL };
    char *envp[] = { NULL };
    execve("/ssc-probe-missing", argv, envp);
    if (errno != EPERM) leaks |= PROBE_EXEC;
    return leaks;
}
