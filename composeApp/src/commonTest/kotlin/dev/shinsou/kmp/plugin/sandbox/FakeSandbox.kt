package dev.shinsou.kmp.plugin.sandbox

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray

/**
 * A scripted sandbox peer for host-side tests: it speaks SandboxProtocol v1 from the sandbox side
 * like the real core would, with hook behavior supplied per method. Runs in the test's scope, so
 * virtual time drives host timeouts.
 */
internal class FakeSandbox(
    scope: CoroutineScope,
    private val readyVersion: Int = SANDBOX_PROTOCOL_VERSION,
    private val initFailure: SandboxErrorCode? = null,
    private val hooks: Map<String, suspend Hook.(JsonArray) -> HookOutcome>,
) : SandboxTransport {
    sealed interface HookOutcome {
        data class Ok(val json: String) : HookOutcome
        data class Fail(val code: SandboxErrorCode) : HookOutcome
        /** Sends nothing: the hook never answers (timeouts, cancellation). */
        data object Hang : HookOutcome
        /** The hook already wrote its own (possibly malicious) frames. */
        data object Custom : HookOutcome
    }

    private val toHost = Channel<ByteArray>(Channel.UNLIMITED)
    private val toSandbox = Channel<ByteArray>(Channel.UNLIMITED)
    private val incoming = Channel<SandboxFrame>(Channel.UNLIMITED)
    private val reader = SandboxFrameReader(SandboxPeer.SANDBOX)

    /** Every frame the host sent, in order. */
    val received = ArrayList<SandboxFrame>()
    var closed = false
        private set

    /** When set, host writes block forever (a sandbox that stopped reading its input). */
    @kotlin.concurrent.Volatile
    var stallHostWrites = false
    var initScript: String? = null
        private set
    var initLimits: SandboxLimits? = null
        private set

    private val job: Job = scope.launch {
        launch {
            for (bytes in toSandbox) reader.feed(bytes).forEach { received += it; incoming.send(it) }
        }
        val buffered = ArrayDeque<SandboxFrame>()
        suspend fun next(): SandboxFrame = buffered.removeFirstOrNull() ?: incoming.receive()
        while (true) {
            when (val f = next()) {
                is SandboxFrame.Init -> {
                    initScript = f.script
                    initLimits = f.limits
                    emit(SandboxFrame.Ready(f.callId, readyVersion))
                    if (initFailure != null) emit(SandboxFrame.Failure(f.callId, initFailure)) else emit(SandboxFrame.Result(f.callId, "null"))
                }
                is SandboxFrame.Invoke -> {
                    val hook = hooks[f.method]
                    val args = Json.parseToJsonElement(f.argsJson).jsonArray
                    val h = Hook(f.callId, buffered, ::next)
                    when (val outcome = if (hook == null) HookOutcome.Fail(SandboxErrorCode.NO_SUCH_HOOK) else h.hook(args)) {
                        is HookOutcome.Ok -> emit(SandboxFrame.Result(f.callId, outcome.json))
                        is HookOutcome.Fail -> emit(SandboxFrame.Failure(f.callId, outcome.code))
                        HookOutcome.Hang -> awaitCancellation()
                        HookOutcome.Custom -> Unit
                    }
                }
                is SandboxFrame.Cancel -> emit(SandboxFrame.Failure(f.callId, SandboxErrorCode.CANCELLED))
                else -> Unit // stray async results/cancels after an invocation ended
            }
        }
    }

    /** Takes the first frame matching [match], from the buffer or new arrivals; others stay buffered in order. */
    private suspend fun take(buffered: ArrayDeque<SandboxFrame>, match: (SandboxFrame) -> Boolean): SandboxFrame {
        buffered.firstOrNull(match)?.let { buffered.remove(it); return it }
        while (true) {
            val f = incoming.receive()
            if (match(f)) return f
            buffered.addLast(f)
        }
    }

    inner class Hook(val callId: Long, private val buffered: ArrayDeque<SandboxFrame>, @Suppress("unused") private val next: suspend () -> SandboxFrame) {
        private var seq = 0L

        suspend fun bridge(op: String, argsJson: String): BridgeOutcome {
            emit(SandboxFrame.BridgeCall(callId, ++seq, op, argsJson))
            return (take(buffered) { it is SandboxFrame.BridgeResult } as SandboxFrame.BridgeResult).outcome
        }

        suspend fun startAsync(opId: Long, op: String, argsJson: String) = emit(SandboxFrame.AsyncBridgeCall(callId, opId, op, argsJson))

        suspend fun awaitAsync(opId: Long): BridgeOutcome =
            (take(buffered) { it is SandboxFrame.AsyncBridgeResult && it.opId == opId } as SandboxFrame.AsyncBridgeResult).outcome

        /** Waits until the host cancels this call (a hook that cooperates with cancellation). */
        suspend fun awaitCancel() {
            take(buffered) { it is SandboxFrame.Cancel && it.callId == callId }
        }

        suspend fun send(frame: SandboxFrame) = emit(frame)

        suspend fun sendRaw(bytes: ByteArray) = toHost.send(bytes)

        fun stopReading() { stallHostWrites = true }

        /** Ends the stream as if the sandbox process died. */
        fun die() = toHost.close()
    }

    suspend fun emit(frame: SandboxFrame) = SandboxFrameCodec.encode(frame).forEach { toHost.send(it) }

    override suspend fun send(bytes: ByteArray) {
        check(!closed) { "transport closed" }
        if (stallHostWrites) awaitCancellation()
        toSandbox.send(bytes)
    }

    override suspend fun receive(): ByteArray? = toHost.receiveCatching().getOrNull()

    override fun close() {
        closed = true
        toHost.close()
        toSandbox.close()
        job.cancel()
    }
}
