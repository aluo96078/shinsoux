package dev.shinsou.sandbox;

/** Host callbacks invoked by libshinsou_sandbox_core on the runtime's own thread. */
public interface NativeHost {
    long nowMs();

    /** Synchronous bridge call; null (or an exception) makes the JS call yield null. */
    byte[] bridgeCall(String method, byte[] argsJson);

    /** Starts async op {@code opId}; the result must come back through {@link NativeCore#deliverAsync}. */
    int asyncStart(int opId, byte[] requestJson);

    void asyncCancel(int opId);

    /** Blocks until at least one result was delivered (on this thread) or the deadline passes. */
    int asyncWait(long deadlineMs);

    void log(byte[] message);
}
