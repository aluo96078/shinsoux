package dev.shinsou.kmp.plugin.sandbox

/**
 * SandboxProtocol v1 (ADR-0002 §4.2): the only thing that crosses the sandbox boundary.
 *
 * Wire format: a stream of envelopes, each `u32 big-endian body length` + body, body ≤ [MAX_FRAME_BYTES].
 * A body is one CBOR array `[frameType, fields…]` in the subset of [CborReader]. A frame whose body
 * would exceed [MAX_FRAME_BYTES] is sent as consecutive [FrameType.CHUNK] envelopes
 * `[0, more, bytes]` whose concatenated bytes are the frame body. Every chunk but the last carries
 * exactly [SandboxFrameCodec.CHUNK_PAYLOAD_BYTES] bytes (the only split the encoder produces), so the
 * number of chunks is bounded by the size; the reassembled message is capped by the receiver
 * ([SandboxFrameReader.maxMessageBytes]).
 *
 * JSON values (hook arguments, results, bridge arguments and replies) travel as UTF-8 text inside
 * the frames: the sandbox core already works in JSON, and the host re-validates every value with its
 * existing bounded JSON decoders. Each side accepts only the frame types the other side may send;
 * anything unexpected is a [SandboxProtocolException], after which the transport must tear the
 * sandbox down (fail closed).
 */
internal const val SANDBOX_PROTOCOL_VERSION: Int = 1
internal const val MAX_FRAME_BYTES: Int = 1 shl 20

internal enum class FrameType(val code: Int) {
    CHUNK(0),

    // Host -> sandbox
    INIT(1),
    INVOKE(2),
    CANCEL(3),
    BRIDGE_RESULT(4),
    ASYNC_BRIDGE_RESULT(5),
    ASYNC_CANCEL(6),

    // Sandbox -> host
    READY(16),
    BRIDGE_CALL(17),
    ASYNC_BRIDGE_CALL(18),
    RESULT(19),
    FAILURE(20),
    LOG(21),
    LIMIT_EXCEEDED(22),
    ;

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun of(code: Long): FrameType? = if (code in 0..255) byCode[code.toInt()] else null
    }
}

/** Which side is reading: each side only accepts the other side's frame types. */
internal enum class SandboxPeer(val accepts: Set<FrameType>) {
    /** The host reads what the sandbox sends. */
    HOST(
        setOf(
            FrameType.READY, FrameType.BRIDGE_CALL, FrameType.ASYNC_BRIDGE_CALL, FrameType.RESULT,
            FrameType.FAILURE, FrameType.LOG, FrameType.LIMIT_EXCEEDED,
        ),
    ),

    /** The sandbox reads what the host sends. */
    SANDBOX(
        setOf(
            FrameType.INIT, FrameType.INVOKE, FrameType.CANCEL, FrameType.BRIDGE_RESULT,
            FrameType.ASYNC_BRIDGE_RESULT, FrameType.ASYNC_CANCEL,
        ),
    ),
}

/**
 * Stable failure codes. 1–11 are the sandbox core's `ssc_status` values; the rest are produced
 * by the transport or the host dispatcher. Never carries host exception text.
 */
internal enum class SandboxErrorCode(val code: Int) {
    SCRIPT_ERROR(1),
    LIMIT_MEMORY(2),
    LIMIT_STACK(3),
    LIMIT_TIME(4),
    LIMIT_BRIDGE(5),
    LIMIT_RESULT(6),
    CANCELLED(7),
    NO_SUCH_HOOK(8),
    PROMISE_STALLED(9),
    INTERNAL(10),
    BAD_ARGUMENT(11),
    UNSUPPORTED_PROTOCOL(32),
    PERMISSION_DENIED(33),
    UNKNOWN_OPERATION(34),
    HOST_FAILURE(35),
    NETWORK_FAILED(36),
    REVOKED(37),
    ;

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun of(code: Int): SandboxErrorCode? = byCode[code]
    }
}

/** What exceeded its limit, for [SandboxFrame.LimitExceeded] diagnostics. */
internal enum class SandboxLimitKind(val code: Int) {
    MEMORY(1), STACK(2), TIME(3), BRIDGE_CALLS(4), RESULT_SIZE(5), ASYNC_QUEUE(6), DOM_NODES(7), DOM_DEPTH(8), LOG(9);

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun of(code: Int): SandboxLimitKind? = byCode[code]
    }
}

/** Host-owned limits for one sandboxed runtime; mirrors the core's `ssc_limits` field order. */
internal data class SandboxLimits(
    val heapBytes: Long,
    val stackBytes: Long,
    val initTimeoutMs: Long,
    val invokeTimeoutMs: Long,
    val maxBridgeCalls: Long,
    val maxAsyncInflight: Long,
    val maxAsyncQueue: Long,
    val maxResultBytes: Long,
    val domMaxNodes: Long,
    val domMaxDepth: Long,
) {
    init {
        listOf(heapBytes, stackBytes, initTimeoutMs, invokeTimeoutMs, maxBridgeCalls, maxAsyncInflight, maxAsyncQueue, maxResultBytes, domMaxNodes, domMaxDepth)
            .forEach { require(it > 0) { "sandbox limits must be positive" } }
    }

    fun toList(): List<Long> = listOf(
        heapBytes, stackBytes, initTimeoutMs, invokeTimeoutMs, maxBridgeCalls,
        maxAsyncInflight, maxAsyncQueue, maxResultBytes, domMaxNodes, domMaxDepth,
    )

    companion object {
        const val FIELD_COUNT = 10

        fun fromList(v: List<Long>): SandboxLimits {
            require(v.size == FIELD_COUNT)
            return SandboxLimits(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9])
        }
    }
}

/** A bridge reply: a JSON value (`null` allowed) or a stable error code, never an exception message. */
internal sealed interface BridgeOutcome {
    data class Value(val json: String?) : BridgeOutcome
    data class Error(val code: SandboxErrorCode) : BridgeOutcome
}

internal sealed interface SandboxFrame {
    val type: FrameType

    // ---- Host -> sandbox ----
    data class Init(
        val callId: Long,
        val protocolVersion: Int,
        val apiLevel: Int,
        val limits: SandboxLimits,
        val script: String,
    ) : SandboxFrame {
        override val type get() = FrameType.INIT
    }

    data class Invoke(val callId: Long, val method: String, val argsJson: String) : SandboxFrame {
        override val type get() = FrameType.INVOKE
    }

    data class Cancel(val callId: Long) : SandboxFrame {
        override val type get() = FrameType.CANCEL
    }

    data class BridgeResult(val seq: Long, val outcome: BridgeOutcome) : SandboxFrame {
        override val type get() = FrameType.BRIDGE_RESULT
    }

    data class AsyncBridgeResult(val opId: Long, val outcome: BridgeOutcome) : SandboxFrame {
        override val type get() = FrameType.ASYNC_BRIDGE_RESULT
    }

    data class AsyncCancel(val opId: Long) : SandboxFrame {
        override val type get() = FrameType.ASYNC_CANCEL
    }

    // ---- Sandbox -> host ----
    /** Answer to [Init]: the protocol version the sandbox speaks. Anything but v1 fails closed. */
    data class Ready(val callId: Long, val protocolVersion: Int) : SandboxFrame {
        override val type get() = FrameType.READY
    }

    data class BridgeCall(val callId: Long, val seq: Long, val op: String, val argsJson: String) : SandboxFrame {
        override val type get() = FrameType.BRIDGE_CALL
    }

    data class AsyncBridgeCall(val callId: Long, val opId: Long, val op: String, val argsJson: String) : SandboxFrame {
        override val type get() = FrameType.ASYNC_BRIDGE_CALL
    }

    data class Result(val callId: Long, val valueJson: String) : SandboxFrame {
        override val type get() = FrameType.RESULT
    }

    data class Failure(val callId: Long, val code: SandboxErrorCode) : SandboxFrame {
        override val type get() = FrameType.FAILURE
    }

    /** Diagnostic bytes only (bounded); never parsed or shown as host UI. */
    class Log(val bytes: ByteArray) : SandboxFrame {
        override val type get() = FrameType.LOG
        override fun equals(other: Any?): Boolean = other is Log && other.bytes.contentEquals(bytes)
        override fun hashCode(): Int = bytes.contentHashCode()
        override fun toString(): String = "Log(${bytes.size} bytes)"
    }

    data class LimitExceeded(val callId: Long, val kind: SandboxLimitKind) : SandboxFrame {
        override val type get() = FrameType.LIMIT_EXCEEDED
    }
}

/** Per-field caps applied while decoding; the reassembled message cap is separate. */
internal data class SandboxFieldLimits(
    val maxScriptBytes: Int = 8 shl 20,
    val maxMethodBytes: Int = 256,
    val maxJsonBytes: Int = 4 shl 20,
    val maxLogBytes: Int = 16 * 1024,
)

internal object SandboxFrameCodec {
    /** Largest chunk payload that keeps a CHUNK body within [MAX_FRAME_BYTES]. */
    internal const val CHUNK_PAYLOAD_BYTES: Int = MAX_FRAME_BYTES - 16

    /** Encodes [frame] as one or more length-prefixed envelopes, ready to write in order. */
    fun encode(frame: SandboxFrame): List<ByteArray> {
        val body = encodeBody(frame)
        if (body.size <= MAX_FRAME_BYTES) return listOf(envelope(body))
        val out = ArrayList<ByteArray>((body.size + CHUNK_PAYLOAD_BYTES - 1) / CHUNK_PAYLOAD_BYTES)
        var off = 0
        while (off < body.size) {
            val n = minOf(CHUNK_PAYLOAD_BYTES, body.size - off)
            val more = off + n < body.size
            val chunk = CborWriter(n + 16).array(3).uint(FrameType.CHUNK.code.toLong()).bool(more)
                .bytes(body.copyOfRange(off, off + n)).toByteArray()
            check(chunk.size <= MAX_FRAME_BYTES)
            out += envelope(chunk)
            off += n
        }
        return out
    }

    fun encodeBody(frame: SandboxFrame): ByteArray {
        val w = CborWriter()
        val t = frame.type.code.toLong()
        when (frame) {
            is SandboxFrame.Init -> {
                w.array(6).uint(t).uint(frame.callId).uint(frame.protocolVersion.toLong()).uint(frame.apiLevel.toLong())
                w.array(SandboxLimits.FIELD_COUNT)
                frame.limits.toList().forEach { w.uint(it) }
                w.text(frame.script)
            }
            is SandboxFrame.Invoke -> w.array(4).uint(t).uint(frame.callId).text(frame.method).text(frame.argsJson)
            is SandboxFrame.Cancel -> w.array(2).uint(t).uint(frame.callId)
            is SandboxFrame.BridgeResult -> { w.array(4).uint(t).uint(frame.seq); outcome(w, frame.outcome) }
            is SandboxFrame.AsyncBridgeResult -> { w.array(4).uint(t).uint(frame.opId); outcome(w, frame.outcome) }
            is SandboxFrame.AsyncCancel -> w.array(2).uint(t).uint(frame.opId)
            is SandboxFrame.Ready -> w.array(3).uint(t).uint(frame.callId).uint(frame.protocolVersion.toLong())
            is SandboxFrame.BridgeCall -> w.array(5).uint(t).uint(frame.callId).uint(frame.seq).text(frame.op).text(frame.argsJson)
            is SandboxFrame.AsyncBridgeCall -> w.array(5).uint(t).uint(frame.callId).uint(frame.opId).text(frame.op).text(frame.argsJson)
            is SandboxFrame.Result -> w.array(3).uint(t).uint(frame.callId).text(frame.valueJson)
            is SandboxFrame.Failure -> w.array(3).uint(t).uint(frame.callId).uint(frame.code.code.toLong())
            is SandboxFrame.Log -> w.array(2).uint(t).bytes(frame.bytes)
            is SandboxFrame.LimitExceeded -> w.array(3).uint(t).uint(frame.callId).uint(frame.kind.code.toLong())
        }
        return w.toByteArray()
    }

    /** `ok` + JSON text or null, or `!ok` + error code: always the same two trailing fields. */
    private fun outcome(w: CborWriter, o: BridgeOutcome) {
        when (o) {
            is BridgeOutcome.Value -> w.bool(true).textOrNull(o.json)
            is BridgeOutcome.Error -> w.bool(false).uint(o.code.code.toLong())
        }
    }

    /** Decodes one complete frame body; [peer] is the reading side. */
    fun decodeBody(body: ByteArray, peer: SandboxPeer, limits: SandboxFieldLimits = SandboxFieldLimits()): SandboxFrame {
        val r = CborReader(body)
        val n = r.array(maxCount = 8)
        if (n < 1) throw SandboxProtocolException("empty frame")
        val type = FrameType.of(r.uint()) ?: throw SandboxProtocolException("unknown frame type")
        if (type == FrameType.CHUNK) throw SandboxProtocolException("chunk inside a reassembled frame")
        if (type !in peer.accepts) throw SandboxProtocolException("frame $type not accepted by $peer")
        fun arity(want: Int) {
            if (n != want) throw SandboxProtocolException("frame $type has $n fields, expected $want")
        }
        val frame: SandboxFrame = when (type) {
            FrameType.INIT -> {
                arity(6)
                val callId = r.uint()
                val version = r.uintAsInt(0xffff)
                val api = r.uintAsInt(0xffff)
                if (r.array(maxCount = SandboxLimits.FIELD_COUNT) != SandboxLimits.FIELD_COUNT) {
                    throw SandboxProtocolException("limits must have ${SandboxLimits.FIELD_COUNT} fields")
                }
                val values = List(SandboxLimits.FIELD_COUNT) { r.uint() }
                if (values.any { it <= 0 }) throw SandboxProtocolException("limits must be positive")
                SandboxFrame.Init(callId, version, api, SandboxLimits.fromList(values), r.text(limits.maxScriptBytes))
            }
            FrameType.INVOKE -> { arity(4); SandboxFrame.Invoke(r.uint(), r.text(limits.maxMethodBytes), r.text(limits.maxJsonBytes)) }
            FrameType.CANCEL -> { arity(2); SandboxFrame.Cancel(r.uint()) }
            FrameType.BRIDGE_RESULT -> { arity(4); SandboxFrame.BridgeResult(r.uint(), outcome(r, limits)) }
            FrameType.ASYNC_BRIDGE_RESULT -> { arity(4); SandboxFrame.AsyncBridgeResult(r.uint(), outcome(r, limits)) }
            FrameType.ASYNC_CANCEL -> { arity(2); SandboxFrame.AsyncCancel(r.uint()) }
            FrameType.READY -> { arity(3); SandboxFrame.Ready(r.uint(), r.uintAsInt(0xffff)) }
            FrameType.BRIDGE_CALL -> {
                arity(5)
                SandboxFrame.BridgeCall(r.uint(), r.uint(), r.text(limits.maxMethodBytes), r.text(limits.maxJsonBytes))
            }
            FrameType.ASYNC_BRIDGE_CALL -> {
                arity(5)
                SandboxFrame.AsyncBridgeCall(r.uint(), r.uint(), r.text(limits.maxMethodBytes), r.text(limits.maxJsonBytes))
            }
            FrameType.RESULT -> { arity(3); SandboxFrame.Result(r.uint(), r.text(limits.maxJsonBytes)) }
            FrameType.FAILURE -> { arity(3); SandboxFrame.Failure(r.uint(), errorCode(r)) }
            FrameType.LOG -> { arity(2); SandboxFrame.Log(r.bytes(limits.maxLogBytes)) }
            FrameType.LIMIT_EXCEEDED -> {
                arity(3)
                val callId = r.uint()
                val kind = SandboxLimitKind.of(r.uintAsInt(0xff)) ?: throw SandboxProtocolException("unknown limit kind")
                SandboxFrame.LimitExceeded(callId, kind)
            }
            FrameType.CHUNK -> error("unreachable")
        }
        r.requireEnd()
        return frame
    }

    private fun outcome(r: CborReader, limits: SandboxFieldLimits): BridgeOutcome =
        if (r.bool()) BridgeOutcome.Value(r.textOrNull(limits.maxJsonBytes)) else BridgeOutcome.Error(errorCode(r))

    private fun errorCode(r: CborReader): SandboxErrorCode =
        SandboxErrorCode.of(r.uintAsInt(0xffff)) ?: throw SandboxProtocolException("unknown error code")

    private fun envelope(body: ByteArray): ByteArray {
        val out = ByteArray(4 + body.size)
        val n = body.size
        out[0] = (n ushr 24).toByte(); out[1] = (n ushr 16).toByte(); out[2] = (n ushr 8).toByte(); out[3] = n.toByte()
        body.copyInto(out, 4)
        return out
    }
}

/**
 * Incremental reader for one direction of a sandbox stream. Feed it bytes as they arrive; it returns
 * complete frames, reassembles CHUNK sequences up to [maxMessageBytes], and throws
 * [SandboxProtocolException] on the first malformation. After a throw the stream is unusable.
 */
internal class SandboxFrameReader(
    private val peer: SandboxPeer,
    val maxMessageBytes: Int = 8 shl 20,
    private val fieldLimits: SandboxFieldLimits = SandboxFieldLimits(),
) {
    private var pending = ByteArray(0)
    private var pendingSize = 0
    /** Reassembly buffer of the current chunk sequence (null when none is open). */
    private var chunks: ByteArray? = null
    private var chunkBytes = 0
    private var broken = false

    /** True when no partial envelope or chunk sequence is buffered (a clean point to close). */
    val idle: Boolean get() = pendingSize == 0 && chunks == null

    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): List<SandboxFrame> {
        if (broken) throw SandboxProtocolException("stream already failed")
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
        try {
            append(bytes, offset, length)
            val out = ArrayList<SandboxFrame>()
            var pos = 0
            while (pendingSize - pos >= 4) {
                val n = ((pending[pos].toInt() and 0xff) shl 24) or ((pending[pos + 1].toInt() and 0xff) shl 16) or
                    ((pending[pos + 2].toInt() and 0xff) shl 8) or (pending[pos + 3].toInt() and 0xff)
                if (n <= 0 || n > MAX_FRAME_BYTES) throw SandboxProtocolException("bad envelope length $n")
                if (pendingSize - pos - 4 < n) break
                val body = pending.copyOfRange(pos + 4, pos + 4 + n)
                pos += 4 + n
                onBody(body)?.let { out += it }
            }
            pending.copyInto(pending, 0, pos, pendingSize)
            pendingSize -= pos
            return out
        } catch (e: SandboxProtocolException) {
            broken = true
            throw e
        }
    }

    private fun onBody(body: ByteArray): SandboxFrame? {
        if (isChunk(body)) {
            val r = CborReader(body)
            if (r.array(maxCount = 3) != 3) throw SandboxProtocolException("chunk must have 3 fields")
            r.uint()
            val more = r.bool()
            val part = r.bytes(MAX_FRAME_BYTES)
            r.requireEnd()
            if (part.isEmpty()) throw SandboxProtocolException("empty chunk")
            // Canonical split only: a peer cannot inflate bookkeeping with many tiny chunks.
            if (more && part.size != SandboxFrameCodec.CHUNK_PAYLOAD_BYTES) throw SandboxProtocolException("non-final chunk of ${part.size} bytes")
            if (part.size > SandboxFrameCodec.CHUNK_PAYLOAD_BYTES) throw SandboxProtocolException("chunk above ${SandboxFrameCodec.CHUNK_PAYLOAD_BYTES} bytes")
            if (chunkBytes.toLong() + part.size > maxMessageBytes) throw SandboxProtocolException("message above $maxMessageBytes bytes")
            var buf = chunks ?: ByteArray(minOf(maxMessageBytes, 2 * SandboxFrameCodec.CHUNK_PAYLOAD_BYTES))
            if (chunkBytes + part.size > buf.size) buf = buf.copyOf(minOf(maxMessageBytes, maxOf(chunkBytes + part.size, buf.size * 2)))
            part.copyInto(buf, chunkBytes)
            chunkBytes += part.size
            chunks = buf
            if (more) return null
            val whole = buf.copyOf(chunkBytes)
            chunks = null
            chunkBytes = 0
            // A chunked message must be one that could not fit a single envelope.
            if (whole.size <= MAX_FRAME_BYTES) throw SandboxProtocolException("needlessly chunked frame")
            return SandboxFrameCodec.decodeBody(whole, peer, fieldLimits)
        }
        if (chunks != null) throw SandboxProtocolException("frame interleaved with a chunk sequence")
        return SandboxFrameCodec.decodeBody(body, peer, fieldLimits)
    }

    /** `[0, …]`: array head of 3 followed by unsigned 0. */
    private fun isChunk(body: ByteArray): Boolean = body.size >= 2 && body[0] == 0x83.toByte() && body[1] == 0x00.toByte()

    private fun append(bytes: ByteArray, offset: Int, length: Int) {
        val need = pendingSize + length
        // At most one envelope (header + MAX_FRAME_BYTES) is ever buffered beyond what was consumed.
        if (need > pending.size) pending = pending.copyOf(maxOf(need, minOf(pending.size * 2, MAX_FRAME_BYTES + 4 + length)))
        bytes.copyInto(pending, pendingSize, offset, offset + length)
        pendingSize = need
    }
}
