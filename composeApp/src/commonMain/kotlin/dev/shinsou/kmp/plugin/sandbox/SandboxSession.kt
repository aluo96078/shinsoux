package dev.shinsou.kmp.plugin.sandbox

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

private const val BRIDGE_FRAME_SLACK = 16
private const val MAX_OUTGOING_BYTES = 32L shl 20
private const val MAX_LOG_FRAMES_PER_INVOCATION = 65_536
private const val YIELD_EVERY_FRAMES = 64

/**
 * Byte stream to one sandbox (ADR-0002 §4.1). Platforms implement it over an isolated process,
 * a helper's pipes or a WASM instance. [close] must terminate the sandbox: after a protocol
 * violation, a timeout or [SandboxSession.close], nothing it sends is trusted again.
 */
internal interface SandboxTransport {
    suspend fun send(bytes: ByteArray)

    /**
     * Next bytes from the sandbox, or null at end of stream (the sandbox exited). May block
     * without honouring cancellation (a pipe read); [SandboxSession] only calls it from its own
     * pump coroutine, and [close] must make a pending call return.
     */
    suspend fun receive(): ByteArray?

    fun close()
}

/** A sandbox-side failure surfaced to the caller with a stable code only. */
internal class SandboxInvocationException(val code: SandboxErrorCode) :
    IllegalStateException("Sandboxed plugin failed: ${code.name}")

/** The sandbox broke the protocol, went silent or died; the session is unusable. */
internal class SandboxBrokenException(reason: String) : IllegalStateException("Sandbox terminated: $reason")

/**
 * Drives SandboxProtocol v1 over one [SandboxTransport] (ADR-0002 §4.2, §5.3).
 *
 * One invocation at a time (the sandbox runtime is single-threaded). While a hook runs, the
 * session answers the sandbox's synchronous bridge calls in order, runs `net.fetch` operations
 * concurrently (bounded by [SandboxLimits.maxAsyncInflight]) and returns when the matching
 * Result/Failure arrives. Cancellation of the caller sends `Cancel` and waits briefly; any protocol
 * violation, timeout or end of stream kills the sandbox and poisons the session.
 */
internal class SandboxSession(
    private val transport: SandboxTransport,
    private val dispatcher: SandboxBridgeDispatcher,
    private val limits: SandboxLimits,
    private val generation: () -> Long,
    private val onLog: (ByteArray) -> Unit = {},
    /** Extra wall time beyond the sandbox's own limit before the host stops waiting and kills it. */
    private val hostGraceMs: Long = 2_000,
    /** How long a cancelled invocation may take to acknowledge before the sandbox is killed. */
    private val cancelGraceMs: Long = 1_000,
    /**
     * Runs the pump that moves transport bytes into [incoming]. Tests pass their own scope so the
     * pump shares virtual time; production uses a dedicated one, cancelled by [close].
     */
    pumpScope: CoroutineScope? = null,
) {
    private val ownsPumpScope = pumpScope == null
    private val pumpScope: CoroutineScope = pumpScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Transport reads happen only in the pump, never in an invocation: a blocking read cannot be
     * cancelled, so reading inside an invocation would make cancellation and timeouts wait for the
     * sandbox and drop whatever that read returned. Invocations wait on this channel instead,
     * which is cancellable and loses nothing. Bounded: the pump stops reading when it is full.
     */
    private val incoming = Channel<ByteArray>(capacity = 64)

    /**
     * Wake-ups only. Bytes are taken with the non-suspending tryReceive, so their ownership never
     * crosses a cancellation point (a suspending receive may drop an element it already took when
     * the receiver is cancelled at that moment); losing a Unit here is harmless.
     */
    private val available = Channel<Unit>(Channel.CONFLATED)
    private var pump: Job? = null

    /**
     * Writes, symmetrically: only the writer coroutine calls [SandboxTransport.send], which may
     * block uncancellably (a full pipe to a sandbox that stopped reading). Invocations enqueue
     * whole frames without suspending, so a frame is never half-sent and cancellation or a
     * timeout is never stuck behind a write; [outgoingBytes] (guarded by [writeLock]) bounds the
     * backlog, and exceeding it means the sandbox stopped reading: it is killed.
     */
    private val outgoing = Channel<ByteArray>(Channel.UNLIMITED)
    private var outgoingBytes = 0L
    private var writer: Job? = null
    private val reader = SandboxFrameReader(SandboxPeer.HOST)
    private val queued = ArrayDeque<SandboxFrame>()
    private val invocationLock = Mutex()
    private val writeLock = Mutex()
    private var nextCallId = 1L
    private var broken: SandboxBrokenException? = null
    private var initialized = false
    private var framesSinceYield = 0

    val isBroken: Boolean get() = broken != null

    /** Sends Init, checks the sandbox's protocol version and waits for the script to load. */
    suspend fun initialize(script: String, apiLevel: Int = 1) {
        check(!initialized) { "sandbox already initialized" }
        initialized = true
        run(timeoutMs = limits.initTimeoutMs, loginInvocation = false) { callId ->
            send(SandboxFrame.Init(callId, SANDBOX_PROTOCOL_VERSION, apiLevel, limits, script))
            val ready = nextFrame()
            if (ready !is SandboxFrame.Ready || ready.callId != callId) violation("expected Ready, got ${ready.type}")
            if (ready.protocolVersion != SANDBOX_PROTOCOL_VERSION) {
                fail("unsupported protocol version ${ready.protocolVersion}")
            }
        }
    }

    /** Invokes [method] with a JSON argument array; returns the hook's JSON result. */
    suspend fun invoke(method: String, argsJson: String, loginInvocation: Boolean = false): String {
        check(initialized) { "sandbox not initialized" }
        return run(timeoutMs = limits.invokeTimeoutMs, loginInvocation = loginInvocation) { callId ->
            send(SandboxFrame.Invoke(callId, method, argsJson))
            null
        }!!
    }

    /** Kills the sandbox. Idempotent. */
    fun close() {
        if (broken == null) broken = SandboxBrokenException("closed")
        transport.close()
        outgoing.close()
        if (ownsPumpScope) pumpScope.cancel() else { pump?.cancel(); writer?.cancel() }
    }

    private fun ensureWriter() {
        if (writer != null) return
        writer = pumpScope.launch {
            try {
                for (bytes in outgoing) {
                    transport.send(bytes)
                    writeLock.withLock { outgoingBytes -= bytes.size }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // The sandbox side is gone; the reader sees end of stream and the session breaks.
                transport.close()
            }
        }
    }

    /** Enqueues every envelope of [frame]; caller holds [writeLock]. Never suspends. */
    private fun enqueueLocked(frame: SandboxFrame) {
        val envelopes = SandboxFrameCodec.encode(frame)
        val size = envelopes.sumOf { it.size.toLong() }
        if (outgoingBytes + size > MAX_OUTGOING_BYTES) {
            kill("sandbox stopped reading ($outgoingBytes bytes queued)")
            throw broken!!
        }
        ensureWriter()
        for (e in envelopes) if (outgoing.trySend(e).isFailure) { kill("writer closed"); throw broken!! }
        outgoingBytes += size
    }

    private fun ensurePump() {
        if (pump != null) return
        pump = pumpScope.launch {
            try {
                while (true) {
                    val bytes = transport.receive() ?: break
                    incoming.send(bytes)
                    available.trySend(Unit)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // A failing transport is the same as end of stream for the session.
            } finally {
                incoming.close()
                available.trySend(Unit)
            }
        }
    }

    /**
     * Runs one invocation: [start] sends the opening frame(s) and may itself consume frames (Init's
     * Ready). Returns the Result JSON, or null for Init's empty result.
     */
    private suspend fun run(timeoutMs: Long, loginInvocation: Boolean, start: suspend Session.(Long) -> Unit): String? =
        invocationLock.withLock {
            broken?.let { throw it }
            val callId = nextCallId++
            val invocation = SandboxInvocation(callId, generation(), loginInvocation)
            // Async ops are children of the caller: they inherit its dispatcher and die with it.
            val callerContext = currentCoroutineContext()
            val scope = CoroutineScope(callerContext + SupervisorJob(callerContext[Job]))
            val asyncOps = HashMap<Long, Job>()
            val session = Session(callId, invocation, scope, asyncOps)
            try {
                withTimeout(timeoutMs + hostGraceMs) {
                    session.start(callId)
                    session.loop()
                }
            } catch (e: TimeoutCancellationException) {
                kill("invocation exceeded ${timeoutMs + hostGraceMs} ms")
                throw SandboxInvocationException(SandboxErrorCode.LIMIT_TIME)
            } catch (e: CancellationException) {
                withContext(NonCancellable) { session.cancelAndDrain() }
                throw e
            } catch (e: SandboxBrokenException) {
                kill(e.message ?: "broken")
                throw e
            } finally {
                withContext(NonCancellable) { teardown(invocation, scope, asyncOps) }
            }
        }

    /**
     * Ends an invocation. Every step is bounded: a sandbox that stops reading cannot hold the host
     * (or [invocationLock]) through a blocked write; on expiry it is killed instead.
     *
     * Ordering: `closed` is set, then the write lock is taken once as a fence. An async result is
     * only emitted while holding that lock and after re-checking `closed` ([sendAsyncResult]), so
     * every result either precedes this point or is never sent; AsyncCancel and the next Invoke
     * come strictly after.
     */
    private suspend fun teardown(invocation: SandboxInvocation, scope: CoroutineScope, asyncOps: Map<Long, Job>) {
        invocation.closed = true
        val done = withTimeoutOrNull(cancelGraceMs) {
            // Fence: after this no result of this invocation can be enqueued; what is still in
            // flight is exactly what gets an AsyncCancel.
            val unfinished = writeLock.withLock { inflightOps.toList().also { inflightOps.clear() } }
            scope.coroutineContext[Job]?.cancelAndJoin()
            // Pending async ops die with the invocation; their results are never delivered.
            if (broken == null) unfinished.forEach { send(SandboxFrame.AsyncCancel(it)) }
            true
        }
        if (done == null) {
            scope.cancel()
            kill("invocation teardown did not finish within $cancelGraceMs ms")
        }
    }

    /**
     * Async ops the sandbox may consider in flight (guarded by [writeLock]). An op leaves this set
     * before its result becomes visible to the writer, so the sandbox starting its next queued op
     * the moment it sees a result can never look like one op too many; coroutine liveness is not
     * used for this (a job is still "active" for a moment after it enqueued its result).
     */
    private val inflightOps = HashSet<Long>()

    /** Emits an async result only if its invocation is still open, atomically with [teardown]'s fence. */
    private suspend fun sendAsyncResult(invocation: SandboxInvocation, frame: SandboxFrame.AsyncBridgeResult) {
        writeLock.withLock {
            inflightOps.remove(frame.opId)
            if (invocation.closed || broken != null) return
            enqueueLocked(frame)
        }
    }

    private inner class Session(
        private val callId: Long,
        private val invocation: SandboxInvocation,
        private val scope: CoroutineScope,
        private val asyncOps: HashMap<Long, Job>,
    ) {
        private var lastSeq = 0L
        private val seenOps = HashSet<Long>()

        // Frame budgets: a sandbox cannot keep the host busy past its quotas with frames that need
        // no answer (logs) or that the dispatcher would refuse anyway (calls beyond the bridge quota).
        private var bridgeFrames = 0L
        private var logFrames = 0

        private fun countBridgeFrame() {
            if (++bridgeFrames > limits.maxBridgeCalls + BRIDGE_FRAME_SLACK) violation("bridge frames beyond the ${limits.maxBridgeCalls}-call quota")
        }

        private fun countLog() {
            if (++logFrames > MAX_LOG_FRAMES_PER_INVOCATION) violation("more than $MAX_LOG_FRAMES_PER_INVOCATION log frames")
        }

        suspend fun send(frame: SandboxFrame) = this@SandboxSession.send(frame)

        suspend fun nextFrame(): SandboxFrame = this@SandboxSession.nextFrame()

        /** Serves bridge calls until this invocation's Result or Failure. */
        suspend fun loop(): String? {
            while (true) {
                when (val f = nextFrame()) {
                    is SandboxFrame.BridgeCall -> {
                        requireOwn(f.callId)
                        countBridgeFrame()
                        if (f.seq <= lastSeq) violation("bridge seq ${f.seq} not increasing")
                        lastSeq = f.seq
                        val outcome = dispatcher.dispatch(invocation, f.op, f.argsJson)
                        send(SandboxFrame.BridgeResult(f.seq, outcome))
                    }
                    is SandboxFrame.AsyncBridgeCall -> {
                        requireOwn(f.callId)
                        countBridgeFrame()
                        if (!seenOps.add(f.opId)) violation("async op ${f.opId} reused")
                        asyncOps.entries.removeAll { !it.value.isActive }
                        val admitted = writeLock.withLock {
                            if (inflightOps.size >= limits.maxAsyncInflight) false else { inflightOps += f.opId; true }
                        }
                        if (!admitted) violation("more than ${limits.maxAsyncInflight} async ops in flight")
                        asyncOps[f.opId] = scope.launch {
                            val outcome = dispatcher.dispatchAsync(invocation, f.op, f.argsJson)
                            runCatching { sendAsyncResult(invocation, SandboxFrame.AsyncBridgeResult(f.opId, outcome)) }
                        }
                    }
                    is SandboxFrame.Result -> { requireOwn(f.callId); return f.valueJson }
                    is SandboxFrame.Failure -> { requireOwn(f.callId); throw SandboxInvocationException(f.code) }
                    is SandboxFrame.LimitExceeded -> requireOwn(f.callId)
                    is SandboxFrame.Log -> { countLog(); onLog(f.bytes) }
                    is SandboxFrame.Ready -> violation("unexpected Ready")
                    else -> violation("unexpected ${f.type}")
                }
            }
        }

        /** After the caller was cancelled: ask the sandbox to stop, accept its answer briefly, else kill. */
        suspend fun cancelAndDrain() {
            if (broken != null) return
            try {
                // The whole exchange, including the Cancel write itself, is bounded.
                withTimeout(cancelGraceMs) {
                    send(SandboxFrame.Cancel(callId))
                    while (true) {
                        when (val f = nextFrame()) {
                            is SandboxFrame.Result, is SandboxFrame.Failure -> { requireOwnId(f); return@withTimeout }
                            // A bridge call racing the cancel gets a stable refusal, not host work.
                            is SandboxFrame.BridgeCall -> { countBridgeFrame(); send(SandboxFrame.BridgeResult(f.seq, BridgeOutcome.Error(SandboxErrorCode.CANCELLED))) }
                            is SandboxFrame.AsyncBridgeCall -> { countBridgeFrame(); send(SandboxFrame.AsyncBridgeResult(f.opId, BridgeOutcome.Error(SandboxErrorCode.CANCELLED))) }
                            is SandboxFrame.Log -> countLog()
                            is SandboxFrame.LimitExceeded -> Unit
                            else -> violation("unexpected ${f.type} while cancelling")
                        }
                    }
                }
            } catch (e: Throwable) {
                kill("cancelled invocation did not stop")
            }
        }

        private fun requireOwnId(f: SandboxFrame) {
            val id = when (f) {
                is SandboxFrame.Result -> f.callId
                is SandboxFrame.Failure -> f.callId
                else -> callId
            }
            requireOwn(id)
        }

        private fun requireOwn(id: Long) {
            if (id != callId) violation("frame for call $id during call $callId")
        }
    }

    private suspend fun send(frame: SandboxFrame) {
        broken?.let { throw it }
        writeLock.withLock {
            broken?.let { throw it }
            enqueueLocked(frame)
        }
    }

    private suspend fun nextFrame(): SandboxFrame {
        while (true) {
            // Timeouts are cooperative: a peer whose input is always ready must still be cancellable,
            // and on a single-threaded dispatcher the timeout itself must get a chance to run.
            currentCoroutineContext().ensureActive()
            if (++framesSinceYield >= YIELD_EVERY_FRAMES) { framesSinceYield = 0; yield() }
            queued.removeFirstOrNull()?.let { return it }
            broken?.let { throw it }
            ensurePump()
            val bytes = takeBytes() ?: fail("sandbox exited")
            val frames = try {
                reader.feed(bytes)
            } catch (e: SandboxProtocolException) {
                fail("protocol violation: ${e.message}")
            }
            queued.addAll(frames)
        }
    }

    /** Next chunk of bytes, or null when the pump ended. Cancellable, and never loses data. */
    private suspend fun takeBytes(): ByteArray? {
        while (true) {
            val r = incoming.tryReceive()
            if (r.isSuccess) return r.getOrThrow()
            if (r.isClosed) return null
            available.receive()
        }
    }

    private fun violation(reason: String): Nothing = fail("protocol violation: $reason")

    private fun fail(reason: String): Nothing {
        val e = SandboxBrokenException(reason)
        if (broken == null) broken = e
        transport.close()
        throw e
    }

    private fun kill(reason: String) {
        if (broken == null) broken = SandboxBrokenException(reason)
        transport.close()
    }
}
