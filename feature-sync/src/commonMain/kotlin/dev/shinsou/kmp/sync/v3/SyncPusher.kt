package dev.shinsou.kmp.sync.v3

import dev.shinsou.kmp.concurrent.runCatchingCancellable
import dev.shinsou.kmp.logging.Log
import dev.shinsou.kmp.sync.v3.SyncV3Service.Companion.DESCRIPTORS_VERSION
import dev.shinsou.kmp.sync.v3.SyncV3Service.Companion.DESCRIPTOR_CHUNK
import dev.shinsou.kmp.sync.v3.SyncV3Service.Companion.MAX_PUSH_CHARS
import dev.shinsou.kmp.sync.v3.SyncV3Service.Companion.MAX_PUSH_RECORDS
import dev.shinsou.kmp.sync.v3.SyncV3Service.Companion.META_DESCRIPTORS
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** The push side: sends promoted records and work descriptors, and records what the server refused. */
@OptIn(ExperimentalUuidApi::class)
internal class SyncPusher(
    private val context: SyncV3Context,
    private val tokens: SyncSessionTokens,
    private val puller: SyncPuller,
) {
    private val store = context.store
    private val identity = context.identity
    private val api = context.api
    private val nowMillis = context.nowMillis

    /**
     * Titles synced before this device knew their source (an older app, or the package was
     * installed later) get the source's name, language and repository now, so the web front end can
     * show where a title comes from. Works are sent without records; the server keeps the descriptor
     * (records.md §3). Runs again whenever the installed sources change, tracked by [fingerprint].
     */
    suspend fun refreshDescriptors(fingerprint: String) {
        // From the stored records rather than the library: a title that is only in the reading
        // history has records too, and no library entry.
        val works = LinkedHashMap<String, WireWork>()
        for (json in store.liveWorkJson()) {
            // A stored work that does not decode is not described; it is not this device's to send.
            val stored = runCatchingCancellable { SyncWireJson.decodeFromString(WireWork.serializer(), json) }.getOrNull() ?: continue
            // Only what this device can say something about: for any other source the stored copy
            // may be older than what the server has, and sending it would put the old values back.
            if (!identity.describes(stored)) continue
            val described = identity.withSource(stored)
            // A package known only from a repository index adds to the stored copy; with nothing
            // to add there is nothing to send.
            if (described == stored && !identity.hasInstalled(stored)) continue
            if (described.sourceName != null) works[described.hash] = described
        }
        val url = context.baseUrl
        try {
            works.values.chunked(DESCRIPTOR_CHUNK).forEach { chunk ->
                tokens.authed { api.push(url, it, WirePushRequest(Uuid.random().toString(), context.cursor, chunk, emptyList())) }
            }
        } catch (error: SyncApiException) {
            Log.warn("Sync", error) { "Sending work descriptors failed; retrying on the next sync cycle" }
            return // retried on the next cycle
        }
        store.setMeta(META_DESCRIPTORS, fingerprint)
    }

    /** Identifies what the installed sources say about themselves; null when nothing is installed. */
    fun sourcesFingerprint(sources: Map<Pair<String, String>, SyncSourceInfo>): String? {
        if (sources.isEmpty()) return null
        var hash = -0x340d631b7bdddcdbL // FNV-1a 64
        sources.entries
            .map { (key, info) ->
                listOf(key.first, key.second, info.name, info.language, info.repositoryUrl).joinToString("\u0000") { it.orEmpty() }
            }
            .sorted()
            .forEach { line ->
                for (char in line) hash = (hash xor char.code.toLong()) * 0x100000001b3L
                hash = (hash xor 0x1eL) * 0x100000001b3L
            }
        return "v$DESCRIPTORS_VERSION:" + hash.toULong().toString(16)
    }

    // ---- push -------------------------------------------------------------------------------

    suspend fun pushAll(): Int {
        var applied = 0
        val url = context.baseUrl
        while (true) {
            val batch = store.pushableRecords(MAX_PUSH_RECORDS, nowMillis(), ::retryAfterMillis, context.disabledKinds())
            if (batch.isEmpty()) break
            val (request, sent) = buildPush(batch)
            if (sent.isEmpty()) break
            val response = tokens.authed { api.push(url, it, request) }
            val byKey = response.results.associateBy { it.kind to it.key }
            val ingestible = ArrayList<WireRecord>()
            var progressed = 0
            for (record in sent) {
                val result = byKey[record.kind to record.key]
                when {
                    result == null -> reject(record, "no_result")
                    result.result.startsWith("rejected:") -> reject(record, result.result.removePrefix("rejected:"))
                    else -> {
                        if (result.result == "applied") applied++
                        result.record?.let { ingestible += it } ?: reject(record, "no_record")
                        progressed++
                    }
                }
            }
            if (ingestible.isNotEmpty()) puller.ingest(ingestible, null)
            if (progressed == 0) break
        }
        return applied
    }

    private fun reject(record: StoredRecord, reason: String) {
        // A server from before the `package` kind answers `invalid_record` for those records. Park
        // them under a reason of their own: they are retried slowly (an upgraded server accepts
        // them) and are neither a failure to show nor a reason to stop pushing the other records.
        val stored = if (record.kind == SyncKinds.PACKAGE && reason == "invalid_record") REJECT_UNSUPPORTED_KIND else reason
        store.saveRecord(record.copy(rejectReason = stored, rejectedAt = nowMillis()))
    }

    private fun retryAfterMillis(reason: String): Long? = when (reason) {
        "clock_skew", "unknown_work", "no_result", "no_record", "missing_work" -> 10 * 60_000L
        "quota_exceeded" -> 60 * 60_000L
        REJECT_UNSUPPORTED_KIND -> 6 * 60 * 60_000L
        else -> null // invalid_*: wait for a new local edit instead of hammering the server
    }

    private fun buildPush(batch: List<StoredRecord>): Pair<WirePushRequest, List<StoredRecord>> {
        val works = LinkedHashMap<String, WireWork>()
        val records = ArrayList<WirePushRecord>()
        val sent = ArrayList<StoredRecord>()
        var budget = 0
        for (record in batch) {
            val workJson = record.workJson
            val needsWork = SyncKinds.needsWork(record.kind, record.key)
            // A work that does not decode is rejected just below as "missing_work".
            val work = workJson?.let { runCatching { SyncWireJson.decodeFromString(WireWork.serializer(), it) }.getOrNull() }
            if (needsWork && work == null) {
                reject(record, "missing_work")
                continue
            }
            val dirtyFields = record.state.fields.filterValues { it.dirty }
            val deleted = record.state.deleted?.takeIf { it.dirty }
            if (dirtyFields.isEmpty() && deleted == null) continue
            val size = dirtyFields.entries.sumOf { it.key.length + it.value.value.toString().length + it.value.clock.length } +
                (workJson?.length ?: 0) + 128
            if (sent.isNotEmpty() && budget + size > MAX_PUSH_CHARS) break
            budget += size
            work?.let { if (it.hash !in works) works[it.hash] = it }
            records += WirePushRecord(
                kind = record.kind,
                key = record.key,
                work = if (record.kind == SyncKinds.UNIT) record.workHash else null,
                unitRemoteId = if (record.kind == SyncKinds.UNIT) record.unitRemoteId else null,
                fields = if (dirtyFields.isEmpty()) null else JsonObject(dirtyFields.mapValues { it.value.value }),
                clocks = if (dirtyFields.isEmpty()) null else dirtyFields.mapValues { it.value.clock },
                deleted = deleted?.let { WireDeleted(it.value, it.clock) },
            )
            sent += record
        }
        return WirePushRequest(Uuid.random().toString(), context.cursor, works.values.toList(), records) to sent
    }
}
