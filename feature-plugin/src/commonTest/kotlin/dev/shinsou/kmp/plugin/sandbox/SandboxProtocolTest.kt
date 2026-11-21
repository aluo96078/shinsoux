package dev.shinsou.kmp.plugin.sandbox

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SandboxProtocolTest {
    private val limits = SandboxLimits(16L shl 20, 1L shl 20, 2_000, 10_000, 4_096, 6, 64, 4L shl 20, 100_000, 512)

    private val hostToSandbox = listOf(
        SandboxFrame.Init(1, SANDBOX_PROTOCOL_VERSION, 1, limits, "var source = {};"),
        SandboxFrame.Invoke(2, "getPopularManga", "[1]"),
        SandboxFrame.Cancel(2),
        SandboxFrame.BridgeResult(7, BridgeOutcome.Value("\"<html>漫畫</html>\"")),
        SandboxFrame.BridgeResult(8, BridgeOutcome.Value(null)),
        SandboxFrame.BridgeResult(9, BridgeOutcome.Error(SandboxErrorCode.PERMISSION_DENIED)),
        SandboxFrame.AsyncBridgeResult(3, BridgeOutcome.Value("{\"status\":200}")),
        SandboxFrame.AsyncBridgeResult(4, BridgeOutcome.Error(SandboxErrorCode.NETWORK_FAILED)),
        SandboxFrame.AsyncCancel(4),
    )

    private val sandboxToHost = listOf(
        SandboxFrame.Ready(1, SANDBOX_PROTOCOL_VERSION),
        SandboxFrame.BridgeCall(2, 7, "net.request", "{\"url\":\"https://example.test/\"}"),
        SandboxFrame.AsyncBridgeCall(2, 3, "net.request", "{}"),
        SandboxFrame.Result(2, "{\"mangas\":[],\"hasNextPage\":false}"),
        SandboxFrame.Failure(2, SandboxErrorCode.LIMIT_TIME),
        SandboxFrame.Log("hello".encodeToByteArray()),
        SandboxFrame.LimitExceeded(2, SandboxLimitKind.BRIDGE_CALLS),
    )

    @Test
    fun everyFrameRoundTripsThroughTheReader() {
        for ((frames, peer) in listOf(hostToSandbox to SandboxPeer.SANDBOX, sandboxToHost to SandboxPeer.HOST)) {
            val reader = SandboxFrameReader(peer)
            val stream = frames.flatMap { SandboxFrameCodec.encode(it) }.fold(ByteArray(0)) { a, b -> a + b }
            assertEquals(frames, reader.feed(stream))
            assertTrue(reader.idle)
        }
    }

    @Test
    fun bytesAreCanonicalSoEncodingTheDecodedFrameReproducesThem() {
        for ((frames, peer) in listOf(hostToSandbox to SandboxPeer.SANDBOX, sandboxToHost to SandboxPeer.HOST)) {
            for (f in frames) {
                val body = SandboxFrameCodec.encodeBody(f)
                assertContentEquals(body, SandboxFrameCodec.encodeBody(SandboxFrameCodec.decodeBody(body, peer)))
            }
        }
    }

    /** Fixed vectors: the C sandbox core must produce and accept exactly these bytes. */
    @Test
    fun goldenVectors() {
        val vectors = listOf(
            SandboxFrame.Invoke(7, "ok", "[]") to "840207626f6b625b5d",
            SandboxFrame.Cancel(24) to "82031818",
            SandboxFrame.Ready(1, 1) to "83100101",
            SandboxFrame.BridgeResult(1, BridgeOutcome.Value(null)) to "840401f5f6",
            SandboxFrame.BridgeResult(1, BridgeOutcome.Error(SandboxErrorCode.PERMISSION_DENIED)) to "840401f41821",
            SandboxFrame.Failure(1234, SandboxErrorCode.LIMIT_TIME) to "84141904d20400",
            SandboxFrame.Failure(1234, SandboxErrorCode.SCRIPT_ERROR, SandboxSourceFailure.QUOTA_EXCEEDED) to "84141904d20105",
            SandboxFrame.BridgeCall(1, 2, "a", "{}") to "85110102616162 7b7d".hexClean(),
        )
        for ((frame, hex) in vectors) assertEquals(hex, SandboxFrameCodec.encodeBody(frame).hex(), "$frame")
        assertEquals("0000000482031818", SandboxFrameCodec.encode(SandboxFrame.Cancel(24)).single().hex())
    }

    @Test
    fun largeFramesAreChunkedAndReassembledAcrossArbitraryReadBoundaries() {
        val big = "\"" + "漫".repeat(1_200_000) + "\"" // ~3.6 MiB of UTF-8
        val frame = SandboxFrame.Result(5, big)
        val envelopes = SandboxFrameCodec.encode(frame)
        assertTrue(envelopes.size >= 4)
        envelopes.forEach { assertTrue(it.size - 4 <= MAX_FRAME_BYTES) }
        val stream = envelopes.fold(ByteArray(0)) { a, b -> a + b } + SandboxFrameCodec.encode(SandboxFrame.Failure(6, SandboxErrorCode.CANCELLED)).single()
        val reader = SandboxFrameReader(SandboxPeer.HOST)
        val out = ArrayList<SandboxFrame>()
        val rnd = Random(42)
        var pos = 0
        while (pos < stream.size) {
            val n = minOf(stream.size - pos, 1 + rnd.nextInt(200_000))
            out += reader.feed(stream, pos, n)
            pos += n
        }
        assertEquals(listOf(frame, SandboxFrame.Failure(6, SandboxErrorCode.CANCELLED)), out)
        assertTrue(reader.idle)
    }

    @Test
    fun reassembledMessagesAreCapped() {
        val reader = SandboxFrameReader(SandboxPeer.HOST, maxMessageBytes = 2 shl 20)
        val envelopes = SandboxFrameCodec.encode(SandboxFrame.Result(1, "\"" + "x".repeat(3 shl 20) + "\""))
        assertFailsWith<SandboxProtocolException> { envelopes.forEach { reader.feed(it) } }
        // A failed stream stays failed: nothing after the violation is trusted.
        assertFailsWith<SandboxProtocolException> { reader.feed(SandboxFrameCodec.encode(SandboxFrame.Ready(1, 1)).single()) }
    }

    @Test
    fun eachSideAcceptsOnlyThePeersFrames() {
        for (f in hostToSandbox) assertRejected(SandboxFrameCodec.encodeBody(f), SandboxPeer.HOST)
        for (f in sandboxToHost) assertRejected(SandboxFrameCodec.encodeBody(f), SandboxPeer.SANDBOX)
    }

    @Test
    fun malformedBodiesFailClosed() {
        val bad = listOf(
            "" to "empty",
            "80" to "empty array",
            "8163" to "type is not an integer",
            "8118ff" to "unknown frame type",
            "841401010001" to "trailing bytes",
            "84140101" to "truncated",
            "83130163" to "text length beyond the body",
            "821301" to "wrong arity for RESULT",
            "83130163ff5b5d" to "invalid UTF-8",
            "8313180163" to "non-shortest integer (1 encoded as 0x18 0x01)",
            "9f1301" to "indefinite-length array",
            "831320605b5d" to "negative integer",
            "8313a0" to "map",
            "83131901" to "truncated 2-byte integer",
            "8414011b800000000000000000" to "integer above 2^63-1",
            "841401f97c0000" to "float",
            "841401c00100" to "tag",
            "9b000000000000000213" to "array count far beyond the body",
            "83167f" to "indefinite text",
            // Same UTF-8 the C codec rejects (sandbox-core/test/frames_test.c): overlong, surrogate, above U+10FFFF.
            "83130162c0af" to "overlong UTF-8",
            "83130163eda080" to "UTF-8 surrogate",
            "83130164f4908080" to "above U+10FFFF",
        )
        for ((hex, why) in bad) assertRejected(hex.unhex(), SandboxPeer.HOST, why)
        assertRejected("841401186300".unhex(), SandboxPeer.HOST, "error code 99 is unknown")
        assertRejected("83141904d204".unhex(), SandboxPeer.HOST, "FAILURE without the marker field")
        assertRejected("84141904d20405".unhex(), SandboxPeer.HOST, "a marker on a non-script failure")
        assertRejected("84141904d20106".unhex(), SandboxPeer.HOST, "marker 6 is unknown")
        assertRejected("831601ff".unhex(), SandboxPeer.HOST, "unknown limit kind")
        assertRejected("830001410a".unhex(), SandboxPeer.HOST, "chunk decoded as a frame")
    }

    @Test
    fun malformedEnvelopesFailClosed() {
        assertFailsWith<SandboxProtocolException> { SandboxFrameReader(SandboxPeer.HOST).feed("00000000".unhex()) }
        assertFailsWith<SandboxProtocolException> { SandboxFrameReader(SandboxPeer.HOST).feed("00100001".unhex()) }
        // Two chunks in a row for a message that would have fit one envelope.
        val small = SandboxFrameCodec.encodeBody(SandboxFrame.Ready(1, 1))
        val c1 = chunk(true, small.copyOfRange(0, 2))
        val c2 = chunk(false, small.copyOfRange(2, small.size))
        assertFailsWith<SandboxProtocolException> { SandboxFrameReader(SandboxPeer.HOST).feed(c1 + c2) }
        // A normal frame in the middle of a chunk sequence.
        assertFailsWith<SandboxProtocolException> {
            SandboxFrameReader(SandboxPeer.HOST).feed(c1 + SandboxFrameCodec.encode(SandboxFrame.Ready(1, 1)).single())
        }
        assertFailsWith<SandboxProtocolException> { SandboxFrameReader(SandboxPeer.HOST).feed(chunk(false, ByteArray(0))) }
        // Tiny non-final chunks are refused at once, before any bookkeeping can accumulate.
        val tiny = SandboxFrameReader(SandboxPeer.HOST)
        assertFailsWith<SandboxProtocolException> { tiny.feed("000000058300f54100".unhex()) }
        // A non-final chunk of exactly the canonical size is fine; the sequence just isn't finished yet.
        val full = SandboxFrameReader(SandboxPeer.HOST)
        val fullChunk = CborWriter().array(3).uint(0).bool(true).bytes(ByteArray(SandboxFrameCodec.CHUNK_PAYLOAD_BYTES)).toByteArray()
        val env = byteArrayOf((fullChunk.size ushr 24).toByte(), (fullChunk.size ushr 16).toByte(), (fullChunk.size ushr 8).toByte(), fullChunk.size.toByte()) + fullChunk
        assertEquals(emptyList(), full.feed(env))
        assertFalse(full.idle)
    }

    @Test
    fun fieldCapsAreEnforced() {
        val tight = SandboxFieldLimits(maxMethodBytes = 4, maxJsonBytes = 8, maxLogBytes = 2)
        assertRejected(SandboxFrameCodec.encodeBody(SandboxFrame.BridgeCall(1, 1, "net.request", "{}")), SandboxPeer.HOST, "op", tight)
        assertRejected(SandboxFrameCodec.encodeBody(SandboxFrame.Result(1, "\"123456789\"")), SandboxPeer.HOST, "json", tight)
        assertRejected(SandboxFrameCodec.encodeBody(SandboxFrame.Log(ByteArray(3))), SandboxPeer.HOST, "log", tight)
    }

    /**
     * Mutation fuzz over valid frames and random bytes: decoding either succeeds with a frame that
     * re-encodes to the same bytes, or throws [SandboxProtocolException]; nothing else may escape.
     */
    @Test
    fun mutationFuzz() {
        SandboxProtocolFuzz.run(iterations = 60_000, seed = 1)
    }

    private fun assertRejected(body: ByteArray, peer: SandboxPeer, why: String = "", lim: SandboxFieldLimits = SandboxFieldLimits()) {
        assertFailsWith<SandboxProtocolException>("should reject: $why") { SandboxFrameCodec.decodeBody(body, peer, lim) }
    }

    private fun chunk(more: Boolean, bytes: ByteArray): ByteArray {
        val body = CborWriter().array(3).uint(0).bool(more).bytes(bytes).toByteArray()
        return byteArrayOf(0, 0, (body.size ushr 8).toByte(), body.size.toByte()) + body
    }
}

/** Shared by the unit test and the long-running desktop fuzz entry point. */
internal object SandboxProtocolFuzz {
    private val seeds: List<Pair<ByteArray, SandboxPeer>> by lazy {
        val limits = SandboxLimits(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        listOf(
            SandboxFrame.Init(1, 1, 1, limits, "var source={}") to SandboxPeer.SANDBOX,
            SandboxFrame.Invoke(300, "getChapterList", "[{\"url\":\"/a\"}]") to SandboxPeer.SANDBOX,
            SandboxFrame.BridgeResult(70_000, BridgeOutcome.Value("\"x\"")) to SandboxPeer.SANDBOX,
            SandboxFrame.AsyncBridgeResult(1, BridgeOutcome.Error(SandboxErrorCode.REVOKED)) to SandboxPeer.SANDBOX,
            SandboxFrame.Ready(1, 1) to SandboxPeer.HOST,
            SandboxFrame.BridgeCall(5, 1L shl 40, "net.request", "{\"url\":\"u\"}") to SandboxPeer.HOST,
            SandboxFrame.Result(5, "{\"mangas\":[]}") to SandboxPeer.HOST,
            SandboxFrame.Log(byteArrayOf(1, 2, 3)) to SandboxPeer.HOST,
            SandboxFrame.LimitExceeded(5, SandboxLimitKind.MEMORY) to SandboxPeer.HOST,
        ).map { (f, p) -> SandboxFrameCodec.encodeBody(f) to p }
    }

    fun run(iterations: Int, seed: Long) {
        val rnd = Random(seed)
        repeat(iterations) {
            val (orig, peer) = seeds[rnd.nextInt(seeds.size)]
            val input = if (rnd.nextInt(10) == 0) rnd.nextBytes(rnd.nextInt(64)) else mutate(orig, rnd)
            check(input, peer)
            // The same bytes through the envelope reader, possibly split.
            val env = byteArrayOf((input.size ushr 24).toByte(), (input.size ushr 16).toByte(), (input.size ushr 8).toByte(), input.size.toByte()) + input
            val reader = SandboxFrameReader(peer)
            try {
                val cut = if (env.isEmpty()) 0 else rnd.nextInt(env.size)
                reader.feed(env, 0, cut)
                reader.feed(env, cut, env.size - cut)
            } catch (_: SandboxProtocolException) {
            }
        }
    }

    private fun check(input: ByteArray, peer: SandboxPeer) {
        val frame = try {
            SandboxFrameCodec.decodeBody(input, peer)
        } catch (_: SandboxProtocolException) {
            return
        }
        val again = SandboxFrameCodec.encodeBody(frame)
        if (!again.contentEquals(input)) {
            throw AssertionError("non-canonical accept: ${input.hex()} decoded to $frame which re-encodes as ${again.hex()}")
        }
    }

    private fun mutate(src: ByteArray, rnd: Random): ByteArray {
        var b = src.copyOf()
        repeat(1 + rnd.nextInt(4)) {
            b = when (rnd.nextInt(6)) {
                0 -> if (b.isEmpty()) b else b.also { it[rnd.nextInt(it.size)] = rnd.nextInt(256).toByte() }
                1 -> if (b.isEmpty()) b else b.also { val i = rnd.nextInt(it.size); it[i] = (it[i].toInt() xor (1 shl rnd.nextInt(8))).toByte() }
                2 -> if (b.isEmpty()) b else b.copyOfRange(0, rnd.nextInt(b.size)) // truncate
                3 -> { val i = rnd.nextInt(b.size + 1); b.copyOfRange(0, i) + rnd.nextBytes(1 + rnd.nextInt(4)) + b.copyOfRange(i, b.size) }
                4 -> if (b.size < 2) b else { val i = rnd.nextInt(b.size - 1); b.copyOfRange(0, i) + b.copyOfRange(i + 1, b.size) }
                else -> b + b.copyOfRange(0, rnd.nextInt(b.size + 1)) // duplicate a prefix as trailing data
            }
        }
        return b
    }
}

private fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

private fun String.hexClean(): String = replace(" ", "")

private fun String.unhex(): ByteArray {
    val s = hexClean()
    return ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}
