package dev.shinsou.sandbox;

/**
 * Thin JNI wrapper over shinsou-sandbox-core. One instance owns one QuickJS runtime and must be
 * driven by a single thread; only {@link #cancel()} may be called from another thread.
 */
public final class NativeCore implements AutoCloseable {
    public static final int OK = 0;

    /** Order matches ssc_limits. */
    public static final class Limits {
        public long heapBytes = 16L << 20, stackBytes = 1L << 20;
        public long initTimeoutMs = 2000, invokeTimeoutMs = 10_000;
        public long maxBridgeCalls = 4096, maxAsyncInflight = 6, maxAsyncQueue = 64;
        public long maxResultBytes = 1L << 20, domMaxNodes = 100_000, domMaxDepth = 512;

        long[] toArray() {
            return new long[] {heapBytes, stackBytes, initTimeoutMs, invokeTimeoutMs, maxBridgeCalls,
                    maxAsyncInflight, maxAsyncQueue, maxResultBytes, domMaxNodes, domMaxDepth};
        }
    }

    public static final class Result {
        public final int status;
        public final byte[] json;

        Result(int status, byte[] json) {
            this.status = status;
            this.json = json;
        }
    }

    private long handle;

    public NativeCore(NativeHost host, Limits limits) {
        handle = nativeNew(host, limits.toArray());
        if (handle == 0) throw new IllegalStateException("ssc_new failed");
    }

    public int init(byte[] bootstrap, byte[] script) { return nativeInit(handle, bootstrap, script); }

    public Result invoke(String method, byte[] argsJson) {
        byte[] r = nativeInvoke(handle, method, argsJson);
        byte[] body = new byte[r.length - 1];
        System.arraycopy(r, 1, body, 0, body.length);
        return new Result(r[0], body);
    }

    /** Must be called on the runtime thread (typically from {@link NativeHost#asyncWait}). */
    public int deliverAsync(int opId, boolean ok, byte[] payloadJson) {
        return nativeDeliverAsync(handle, opId, ok, payloadJson);
    }

    public void cancel() { nativeCancel(handle); }

    public long memoryUsed() { return nativeMemoryUsed(handle); }

    @Override
    public void close() {
        if (handle != 0) { nativeFree(handle); handle = 0; }
    }

    public static void loadLibrary() { System.loadLibrary("shinsou_sandbox_core"); }

    /** Android only: forbids exec and process creation for this process (seccomp). 0 or -errno. */
    public static int lockdown() { return nativeLockdown(); }
    private static native int nativeLockdown();

    private static native long nativeNew(NativeHost host, long[] limits);
    private static native int nativeInit(long h, byte[] bootstrap, byte[] script);
    /** Returns [status, result bytes...]. */
    private static native byte[] nativeInvoke(long h, String method, byte[] argsJson);
    private static native int nativeDeliverAsync(long h, int opId, boolean ok, byte[] payload);
    private static native void nativeCancel(long h);
    private static native void nativeFree(long h);
    private static native long nativeMemoryUsed(long h);
}
