package dev.shinsou.sandbox;

import dev.shinsou.sandbox.IHostBridge;
import dev.shinsou.sandbox.Payload;

/** Host -> sandbox. Every call except load() returns immediately; results come back via IHostBridge. */
interface ISandbox {
    /** Creates the runtime and evaluates bootstrap + script. Returns an ssc_status (0 = OK). */
    int load(in Payload bootstrap, in Payload script, in long[] limits, IHostBridge host);
    oneway void invoke(long callId, String method, in Payload argsJson);
    oneway void cancel(long callId);
    /** Result of IHostBridge.asyncStart(opId, ...). */
    oneway void asyncResult(int opId, boolean ok, in Payload payload);
    /** Measurement hook for S0: returns {pid, uid, heapBytes}. */
    long[] stats();
    /** Isolation probes for the on-device plan (socket(), open(), ...). Returns "name=errno" lines. */
    String probe();
    /** Test only: terminate the process, to exercise linkToDeath. */
    oneway void crash();
}
