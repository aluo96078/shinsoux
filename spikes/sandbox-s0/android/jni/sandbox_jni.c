/*
 * JNI binding for shinsou-sandbox-core (ADR-0002 S0, Android). The same file builds on macOS
 * against the JDK's jni.h so the callback plumbing can be tested without a device.
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include "shinsou_sandbox_core.h"

typedef struct {
    JavaVM *vm;
    jobject host; /* global ref */
    jmethodID now_ms, bridge_call, async_start, async_cancel, async_wait, log;
    ssc_runtime *rt;
} jctx;

static JNIEnv *env_of(jctx *c) {
    JNIEnv *env = NULL;
    /* Callbacks only run on the thread driving the runtime, which is already attached. */
    if ((*c->vm)->GetEnv(c->vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) return NULL;
    return env;
}

static jbyteArray to_bytes(JNIEnv *env, const char *p, size_t n) {
    jbyteArray a = (*env)->NewByteArray(env, (jsize)n);
    if (a && n) (*env)->SetByteArrayRegion(env, a, 0, (jsize)n, (const jbyte *)p);
    return a;
}

static int swallow_exception(JNIEnv *env) {
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return 1; }
    return 0;
}

static uint64_t cb_now_ms(void *o) {
    jctx *c = o; JNIEnv *env = env_of(c);
    if (!env) return 0;
    jlong v = (*env)->CallLongMethod(env, c->host, c->now_ms);
    return swallow_exception(env) ? 0 : (uint64_t)v;
}

static int cb_bridge_call(void *o, const char *method, const char *args, size_t len, char **out, size_t *out_len) {
    jctx *c = o; JNIEnv *env = env_of(c);
    if (!env) return -1;
    int rc = -1;
    jstring jm = (*env)->NewStringUTF(env, method);
    jbyteArray ja = to_bytes(env, args, len);
    if (jm && ja) {
        jbyteArray r = (jbyteArray)(*env)->CallObjectMethod(env, c->host, c->bridge_call, jm, ja);
        if (!swallow_exception(env) && r) {
            jsize n = (*env)->GetArrayLength(env, r);
            char *buf = malloc((size_t)n + 1); /* ownership passes to the core */
            if (buf) {
                (*env)->GetByteArrayRegion(env, r, 0, n, (jbyte *)buf);
                buf[n] = 0;
                *out = buf; *out_len = (size_t)n; rc = 0;
            }
        }
        if (r) (*env)->DeleteLocalRef(env, r);
    }
    if (jm) (*env)->DeleteLocalRef(env, jm);
    if (ja) (*env)->DeleteLocalRef(env, ja);
    swallow_exception(env);
    return rc;
}

static int cb_async_start(void *o, uint32_t op, const char *req, size_t len) {
    jctx *c = o; JNIEnv *env = env_of(c);
    if (!env) return -1;
    jbyteArray ja = to_bytes(env, req, len);
    if (!ja) { swallow_exception(env); return -1; }
    jint r = (*env)->CallIntMethod(env, c->host, c->async_start, (jint)op, ja);
    (*env)->DeleteLocalRef(env, ja);
    return swallow_exception(env) ? -1 : (int)r;
}

static void cb_async_cancel(void *o, uint32_t op) {
    jctx *c = o; JNIEnv *env = env_of(c);
    if (!env) return;
    (*env)->CallVoidMethod(env, c->host, c->async_cancel, (jint)op);
    swallow_exception(env);
}

static int cb_async_wait(void *o, ssc_runtime *rt, uint64_t deadline) {
    (void)rt; /* Java re-enters through nativeDeliverAsync, which carries the handle. */
    jctx *c = o; JNIEnv *env = env_of(c);
    if (!env) return 0;
    jint r = (*env)->CallIntMethod(env, c->host, c->async_wait, (jlong)deadline);
    return swallow_exception(env) ? 0 : (int)r;
}

static void cb_log(void *o, const char *m, size_t len) {
    jctx *c = o; JNIEnv *env = env_of(c);
    if (!env) return;
    jbyteArray ja = to_bytes(env, m, len);
    if (ja) {
        (*env)->CallVoidMethod(env, c->host, c->log, ja);
        (*env)->DeleteLocalRef(env, ja);
    }
    swallow_exception(env);
}

JNIEXPORT jlong JNICALL Java_dev_shinsou_sandbox_NativeCore_nativeNew(JNIEnv *env, jclass cls, jobject host, jlongArray limits) {
    (void)cls;
    jlong v[10];
    if ((*env)->GetArrayLength(env, limits) != 10) return 0;
    (*env)->GetLongArrayRegion(env, limits, 0, 10, v);
    jctx *c = calloc(1, sizeof *c);
    if (!c) return 0;
    (*env)->GetJavaVM(env, &c->vm);
    c->host = (*env)->NewGlobalRef(env, host);
    jclass hc = (*env)->GetObjectClass(env, host);
    c->now_ms = (*env)->GetMethodID(env, hc, "nowMs", "()J");
    c->bridge_call = (*env)->GetMethodID(env, hc, "bridgeCall", "(Ljava/lang/String;[B)[B");
    c->async_start = (*env)->GetMethodID(env, hc, "asyncStart", "(I[B)I");
    c->async_cancel = (*env)->GetMethodID(env, hc, "asyncCancel", "(I)V");
    c->async_wait = (*env)->GetMethodID(env, hc, "asyncWait", "(J)I");
    c->log = (*env)->GetMethodID(env, hc, "log", "([B)V");
    if (!c->now_ms || !c->bridge_call || !c->async_start || !c->async_cancel || !c->async_wait || !c->log) {
        (*env)->ExceptionClear(env);
        (*env)->DeleteGlobalRef(env, c->host); free(c);
        return 0;
    }
    ssc_limits l = {
        .heap_limit_bytes = (size_t)v[0], .stack_limit_bytes = (size_t)v[1],
        .init_timeout_ms = (uint64_t)v[2], .invoke_timeout_ms = (uint64_t)v[3],
        .max_bridge_calls = (uint32_t)v[4], .max_async_inflight = (uint32_t)v[5],
        .max_async_queue = (uint32_t)v[6], .max_result_bytes = (size_t)v[7],
        .dom_max_nodes = (uint32_t)v[8], .dom_max_depth = (uint32_t)v[9],
    };
    ssc_host h = { c, cb_now_ms, cb_bridge_call, cb_async_start, cb_async_cancel, cb_async_wait, cb_log };
    c->rt = ssc_new(&l, &h);
    if (!c->rt) { (*env)->DeleteGlobalRef(env, c->host); free(c); return 0; }
    return (jlong)(intptr_t)c;
}

static jbyte *pin(JNIEnv *env, jbyteArray a, jsize *n) {
    *n = (*env)->GetArrayLength(env, a);
    return (*env)->GetByteArrayElements(env, a, NULL);
}

JNIEXPORT jint JNICALL Java_dev_shinsou_sandbox_NativeCore_nativeInit(JNIEnv *env, jclass cls, jlong h, jbyteArray boot, jbyteArray script) {
    (void)cls;
    jctx *c = (jctx *)(intptr_t)h;
    jsize bn, sn;
    jbyte *b = pin(env, boot, &bn), *s = pin(env, script, &sn);
    ssc_status st = (b && s) ? ssc_init(c->rt, (const char *)b, (size_t)bn, (const char *)s, (size_t)sn) : SSC_ERR_INTERNAL;
    if (b) (*env)->ReleaseByteArrayElements(env, boot, b, JNI_ABORT);
    if (s) (*env)->ReleaseByteArrayElements(env, script, s, JNI_ABORT);
    return (jint)st;
}

JNIEXPORT jbyteArray JNICALL Java_dev_shinsou_sandbox_NativeCore_nativeInvoke(JNIEnv *env, jclass cls, jlong h, jstring method, jbyteArray args) {
    (void)cls;
    jctx *c = (jctx *)(intptr_t)h;
    const char *m = (*env)->GetStringUTFChars(env, method, NULL);
    jsize an;
    jbyte *a = pin(env, args, &an);
    char *json = NULL; size_t jn = 0;
    ssc_status st = SSC_ERR_INTERNAL;
    char *args_z = a ? malloc((size_t)an + 1) : NULL; /* core expects NUL-terminated JSON */
    if (m && args_z) {
        memcpy(args_z, a, (size_t)an); args_z[an] = 0;
        st = ssc_invoke(c->rt, m, args_z, &json, &jn);
    }
    free(args_z);
    if (a) (*env)->ReleaseByteArrayElements(env, args, a, JNI_ABORT);
    if (m) (*env)->ReleaseStringUTFChars(env, method, m);
    size_t body = (st == SSC_OK && json) ? jn : 0;
    jbyteArray out = (*env)->NewByteArray(env, (jsize)(body + 1));
    if (out) {
        jbyte s = (jbyte)st;
        (*env)->SetByteArrayRegion(env, out, 0, 1, &s);
        if (body) (*env)->SetByteArrayRegion(env, out, 1, (jsize)body, (const jbyte *)json);
    }
    free(json);
    return out;
}

JNIEXPORT jint JNICALL Java_dev_shinsou_sandbox_NativeCore_nativeDeliverAsync(JNIEnv *env, jclass cls, jlong h, jint op, jboolean ok, jbyteArray payload) {
    (void)cls;
    jctx *c = (jctx *)(intptr_t)h;
    jsize n;
    jbyte *p = pin(env, payload, &n);
    int r = p ? ssc_deliver_async(c->rt, (uint32_t)op, ok ? 1 : 0, (const char *)p, (size_t)n) : -1;
    if (p) (*env)->ReleaseByteArrayElements(env, payload, p, JNI_ABORT);
    return r;
}

JNIEXPORT void JNICALL Java_dev_shinsou_sandbox_NativeCore_nativeCancel(JNIEnv *env, jclass cls, jlong h) {
    (void)env; (void)cls;
    ssc_cancel(((jctx *)(intptr_t)h)->rt);
}

JNIEXPORT void JNICALL Java_dev_shinsou_sandbox_NativeCore_nativeFree(JNIEnv *env, jclass cls, jlong h) {
    (void)cls;
    jctx *c = (jctx *)(intptr_t)h;
    ssc_free(c->rt); /* may call async_cancel back into Java, so release the ref afterwards */
    (*env)->DeleteGlobalRef(env, c->host);
    free(c);
}

JNIEXPORT jlong JNICALL Java_dev_shinsou_sandbox_NativeCore_nativeMemoryUsed(JNIEnv *env, jclass cls, jlong h) {
    (void)env; (void)cls;
    return (jlong)ssc_memory_used(((jctx *)(intptr_t)h)->rt);
}

#ifdef __ANDROID__
#include <errno.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <sched.h>
#include <stddef.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <unistd.h>

/*
 * Isolated processes may still fork/exec system binaries (S0 device finding). Before untrusted
 * code runs, forbid execve/execveat and every clone that is not a thread; threads (CLONE_THREAD)
 * stay allowed for ART and the runtime. Stacks on top of the platform app filter; TSYNC applies it
 * to every thread of the process, including binder threads. Returns 0 or -errno.
 */
#if defined(__aarch64__)
#define SSC_AUDIT_ARCH AUDIT_ARCH_AARCH64
#elif defined(__x86_64__)
#define SSC_AUDIT_ARCH AUDIT_ARCH_X86_64
#endif

JNIEXPORT jint JNICALL Java_dev_shinsou_sandbox_NativeCore_nativeLockdown(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    static int installed;
    if (installed) return 0;
    struct sock_filter f[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SSC_AUDIT_ARCH, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
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
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_clone, 0, 4),
        /* clone flags are argument 0 (low 32 bits) on both aarch64 and x86_64. */
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[0])),
        BPF_JUMP(BPF_JMP | BPF_JSET | BPF_K, CLONE_THREAD, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog prog = { (unsigned short)(sizeof f / sizeof f[0]), f };
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) return -errno;
    if (syscall(__NR_seccomp, SECCOMP_SET_MODE_FILTER, SECCOMP_FILTER_FLAG_TSYNC, &prog) != 0) return -errno;
    installed = 1;
    return 0;
}
#endif

