package dev.shinsou.kmp.sync.v3

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.KSerializer

/**
 * In-process stand-in for shinsou-server used by the unit tests: the same LWW merge as the real
 * server ([LwwMerge]), per-account seq numbers, token rotation and a tombstone floor for 410.
 */
internal class FakeSyncServer {
    val baseUrl = "http://127.0.0.1:8787"

    private class Account(val id: String, val username: String, val password: String) {
        var seq = 0L
        var floor = 0L
        val records = LinkedHashMap<Pair<String, String>, Stored>()
        val works = HashMap<String, WireWork>()
        val devices = ArrayList<WireDevice>()
    }

    private class Stored(var state: RecordState, var seq: Long, var workHash: String?, var unitRemoteId: String?)

    private val accounts = HashMap<String, Account>()
    private val accessTokens = HashMap<String, Pair<Account, String>>() // token -> account, device id
    private val refreshTokens = HashMap<String, Pair<Account, String>>()
    private val usedRefreshTokens = HashSet<String>()
    private var counter = 0
    var requests = ArrayList<String>()
    var nowMillis: () -> Long = { System.currentTimeMillis() }
    var failNextPushWith: Int? = null
    var pushCount = 0
    var goneCount = 0
    var maxBatch = 0

    val engine = MockEngine { request -> handle(request) }
    fun client() = HttpClient(engine)

    fun accountWork(username: String, hash: String): WireWork? = accounts[username]?.works?.get(hash)

    fun accountRecords(username: String): Map<Pair<String, String>, RecordState> =
        accounts.getValue(username).records.mapValues { it.value.state }

    fun raiseFloor(username: String) {
        val a = accounts.getValue(username)
        a.floor = a.seq
    }

    /** Pretends another device removed the record (a tombstone with a fresh seq). */
    fun deleteRecord(username: String, kind: String, key: String, clock: String) {
        val a = accounts.getValue(username)
        val s = a.records[kind to key] ?: return
        a.seq++
        s.state = LwwMerge.merge(s.state, PushInput(deleted = DeletedRegister(true, clock)), Long.MAX_VALUE / 2).state!!
        s.seq = a.seq
    }

    /** Removes the record entirely, as if tombstone cleanup had purged it. */
    fun purgeRecord(username: String, kind: String, key: String) {
        accounts.getValue(username).records.remove(kind to key)
    }

    /** Invalidates every access token so the next call must refresh. */
    fun expireAccessTokens() = accessTokens.clear()

    private fun MockRequestHandleScope.json(status: HttpStatusCode, body: String) = respond(
        content = body,
        status = status,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

    private fun <T> MockRequestHandleScope.ok(serializer: KSerializer<T>, value: T, status: HttpStatusCode = HttpStatusCode.OK) =
        json(status, SyncWireJson.encodeToString(serializer, value))

    private fun MockRequestHandleScope.error(status: HttpStatusCode, code: String) =
        json(status, """{"error":{"code":"$code","message":"$code"}}""")

    private fun body(request: HttpRequestData): String = (request.body as? TextContent)?.text.orEmpty()

    private fun auth(request: HttpRequestData): Pair<Account, String>? =
        request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.let { accessTokens[it] }

    private fun session(account: Account, deviceName: String, platform: String) =
        run {
            counter++
            val device = WireDevice(
                id = "00000000-0000-4000-8000-%012d".format(counter),
                syncDeviceId = "dev-$counter",
                name = deviceName,
                platform = platform,
                current = true,
            )
            account.devices += device
            val access = "access-${counter}-0"
            val refresh = "refresh-${counter}-0"
            accessTokens[access] = account to device.id
            refreshTokens[refresh] = account to device.id
            WireSession(
                accessToken = access,
                accessTokenExpiresAt = nowMillis() + 15 * 60_000L,
                refreshToken = refresh,
                user = WireUser(account.id, account.username),
                device = device,
            )
        }

    private fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
        val path = request.url.encodedPath
        requests += "${request.method.value} $path"
        require(request.headers["Shinsou-Api-Version"] == "1") { "Missing Shinsou-Api-Version header on $path" }
        return when {
            path == "/.well-known/shinsou" -> ok(
                WireInstance.serializer(),
                WireInstance(name = "Fake", description = "test", registration = "open"),
            )
            path == "/api/v1/auth/register" && request.method == HttpMethod.Post -> {
                val req = SyncWireJson.decodeFromString(WireRegisterRequest.serializer(), body(request))
                if (accounts.containsKey(req.username)) return error(HttpStatusCode.Conflict, "username_taken")
                val account = Account("account-${req.username}", req.username, req.password).also { accounts[req.username] = it }
                ok(WireSession.serializer(), session(account, req.device.name, req.device.platform), HttpStatusCode.Created)
            }
            path == "/api/v1/auth/login" && request.method == HttpMethod.Post -> {
                val req = SyncWireJson.decodeFromString(WireLoginRequest.serializer(), body(request))
                val account = accounts[req.username]?.takeIf { it.password == req.password }
                    ?: return error(HttpStatusCode.Unauthorized, "invalid_credentials")
                ok(WireSession.serializer(), session(account, req.device.name, req.device.platform))
            }
            path == "/api/v1/auth/refresh" -> {
                val token = SyncWireJson.decodeFromString(WireRefreshRequest.serializer(), body(request)).refreshToken
                if (token in usedRefreshTokens) return error(HttpStatusCode.Unauthorized, "refresh_token_reused")
                val owner = refreshTokens.remove(token) ?: return error(HttpStatusCode.Unauthorized, "unauthorized")
                usedRefreshTokens += token
                counter++
                val access = "access-$counter"
                val refresh = "refresh-$counter"
                accessTokens[access] = owner
                refreshTokens[refresh] = owner
                ok(WireTokens.serializer(), WireTokens(access, nowMillis() + 15 * 60_000L, refresh))
            }
            else -> {
                val (account, deviceId) = auth(request) ?: return error(HttpStatusCode.Unauthorized, "unauthorized")
                authed(request, path, account, deviceId)
            }
        }
    }

    private fun MockRequestHandleScope.authed(request: HttpRequestData, path: String, account: Account, deviceId: String): HttpResponseData =
        when {
            path == "/api/v1/auth/logout" -> {
                accessTokens.entries.removeAll { it.value.second == deviceId }
                respond("", HttpStatusCode.NoContent)
            }
            path == "/api/v1/devices" && request.method == HttpMethod.Get -> ok(
                WireDevices.serializer(),
                WireDevices(account.devices.map { it.copy(current = it.id == deviceId) }),
            )
            path.startsWith("/api/v1/devices/") && request.method == HttpMethod.Patch -> {
                val id = path.substringAfterLast('/')
                val name = SyncWireJson.decodeFromString(WireRename.serializer(), body(request)).name
                val index = account.devices.indexOfFirst { it.id == id }
                if (index < 0) return error(HttpStatusCode.NotFound, "not_found")
                account.devices[index] = account.devices[index].copy(name = name)
                ok(WireDevice.serializer(), account.devices[index].copy(current = id == deviceId))
            }
            path.startsWith("/api/v1/devices/") && request.method == HttpMethod.Delete -> {
                val id = path.substringAfterLast('/')
                if (!account.devices.removeIf { it.id == id }) return error(HttpStatusCode.NotFound, "not_found")
                respond("", HttpStatusCode.NoContent)
            }
            path == "/api/v1/sync/push" -> push(request, account)
            path == "/api/v1/sync/changes" -> changes(request, account)
            else -> error(HttpStatusCode.NotFound, "not_found")
        }

    private fun MockRequestHandleScope.push(request: HttpRequestData, account: Account): HttpResponseData {
        pushCount++
        failNextPushWith?.let { status ->
            failNextPushWith = null
            return error(HttpStatusCode.fromValue(status), "internal")
        }
        val req = SyncWireJson.decodeFromString(WirePushRequest.serializer(), body(request))
        if (req.since != 0L && req.since < account.floor) return error(HttpStatusCode.Gone, "resync_required").also { goneCount++ }
        maxBatch = maxOf(maxBatch, req.records.size)
        for (work in req.works) {
            // Mirror the real server's descriptor validation (work-key.md §5).
            val key = runCatching { WorkKey(work.keyVersion, work.packageId, work.sourceId, work.remoteId) }.getOrNull()
            if (key == null || key.hash != work.hash || work.title.isBlank() || work.title.length > 512 ||
                (work.coverUrl != null && !work.coverUrl.startsWith("https://"))
            ) {
                return error(HttpStatusCode.UnprocessableEntity, "invalid_work_key")
            }
            // The real server keeps what it knows about the source when a descriptor omits it.
            val known = account.works[work.hash]
            account.works[work.hash] = work.copy(
                language = work.language ?: known?.language,
                sourceName = work.sourceName ?: known?.sourceName,
                repositoryUrl = work.repositoryUrl ?: known?.repositoryUrl,
            )
        }
        for (record in req.records) {
            if (record.kind == SyncKinds.UNIT) {
                val work = account.works[record.work] ?: continue
                val expected = WorkKey(1, work.packageId, work.sourceId, work.remoteId).unitHash(record.unitRemoteId.orEmpty())
                if (expected != record.key) return error(HttpStatusCode.UnprocessableEntity, "invalid_key")
            }
        }
        val results = req.records.map { record ->
            val id = record.kind to record.key
            val input = PushInput(
                fields = record.fields?.toMap().orEmpty(),
                clocks = record.clocks.orEmpty(),
                deleted = record.deleted?.let { DeletedRegister(it.value, it.clock) },
            )
            val workHash = record.work ?: record.key.takeIf { record.kind != SyncKinds.CATEGORY && !it.startsWith("local:") }
            if (workHash != null && workHash !in account.works) {
                return@map WirePushResult(record.kind, record.key, "rejected:unknown_work")
            }
            val existing = account.records[id]
            when (val outcome = LwwMerge.merge(existing?.state, input, nowMillis())) {
                is MergeOutcome.Rejected -> WirePushResult(record.kind, record.key, outcome.wire)
                is MergeOutcome.Unchanged -> WirePushResult(
                    record.kind, record.key, "unchanged",
                    existing?.let { wire(account, id, it) },
                )
                is MergeOutcome.Applied -> {
                    account.seq++
                    val stored = existing ?: Stored(outcome.state, account.seq, workHash, record.unitRemoteId).also { account.records[id] = it }
                    stored.state = outcome.state
                    stored.seq = account.seq
                    if (record.unitRemoteId != null) stored.unitRemoteId = record.unitRemoteId
                    WirePushResult(record.kind, record.key, "applied", wire(account, id, stored))
                }
            }
        }
        return ok(WirePushResponse.serializer(), WirePushResponse(results, account.seq))
    }

    private fun wire(account: Account, id: Pair<String, String>, s: Stored) = WireRecord(
        kind = id.first,
        key = id.second,
        seq = s.seq,
        work = s.workHash?.let { account.works[it] },
        unitRemoteId = s.unitRemoteId,
        fields = JsonObject(s.state.fields.mapValues { it.value.value }),
        clocks = s.state.fields.mapValues { it.value.clock },
        deleted = s.state.deleted?.let { WireDeleted(it.value, it.clock) },
        cleared = s.state.cleared,
    )

    private fun MockRequestHandleScope.changes(request: HttpRequestData, account: Account): HttpResponseData {
        val since = request.url.parameters["since"]!!.toLong()
        val limit = request.url.parameters["limit"]?.toInt() ?: 500
        if (since != 0L && since < account.floor) return error(HttpStatusCode.Gone, "resync_required").also { goneCount++ }
        val page = account.records.entries.filter { it.value.seq > since }.sortedBy { it.value.seq }
        val slice = page.take(limit)
        return ok(
            WireChanges.serializer(),
            WireChanges(
                records = slice.map { wire(account, it.key, it.value) },
                nextSince = slice.lastOrNull()?.value?.seq ?: since,
                hasMore = page.size > limit,
            ),
        )
    }
}
