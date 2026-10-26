package dev.shinsou.sandbox;

import dev.shinsou.sandbox.Payload;

/**
 * Sandbox -> Host. One binder instance per package: holding it is the package's identity, so the
 * host never needs to trust a package name sent by the sandbox.
 */
interface IHostBridge {
    /** Synchronous bridge call (blocks a sandbox thread, a host binder thread). Null = JS gets null. */
    @nullable Payload bridgeCall(String method, in Payload argsJson);
    oneway void asyncStart(int opId, in Payload requestJson);
    oneway void asyncCancel(int opId);
    /** Terminal result of ISandbox.invoke(callId). status is an ssc_status. */
    oneway void onResult(long callId, int status, in Payload resultJson);
    oneway void onLog(in byte[] message);
}
