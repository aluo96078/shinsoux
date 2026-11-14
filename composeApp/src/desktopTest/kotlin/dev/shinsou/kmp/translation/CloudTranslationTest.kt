package dev.shinsou.kmp.translation

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shinsou.kmp.plugin.InMemoryPluginKeyValueStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudTranslationTest {
    private class Service(
        private val status: HttpStatusCode = HttpStatusCode.OK,
        private val errorBody: String = "{}",
        /** Requests answered normally before [status] applies. */
        private val healthyRequests: Int = 0,
    ) {
        val requests = ArrayList<HttpRequestData>()
        val bodies = ArrayList<String>()
        val client = HttpClient(
            MockEngine { request ->
                requests += request
                val body = (request.body as TextContent).text
                bodies += body
                if (status != HttpStatusCode.OK && requests.size > healthyRequests) return@MockEngine respond(errorBody, status)
                val json = Json.parseToJsonElement(body).jsonObject
                val answer = if (request.url.host.endsWith("deepl.com")) {
                    buildJsonObject {
                        put("translations", JsonArray(json.getValue("text").jsonArray.map { buildJsonObject { put("text", "D(${it.jsonPrimitive.content})") } }))
                    }
                } else {
                    buildJsonObject {
                        put("data", buildJsonObject {
                            put("translations", buildJsonArray { json.getValue("q").jsonArray.forEach { add(buildJsonObject { put("translatedText", "G(${it.jsonPrimitive.content})") }) } })
                        })
                    }
                }
                respond(answer.toString(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        )
    }

    private val hop = TranslationHop("ja", "zh-Hant", "x")

    @Test
    fun deepLSendsTheKeyInTheHeaderAndPicksTheEndpointFromTheKey() = runBlocking<Unit> {
        val service = Service()
        val free = DeepLTranslationProvider(service.client, " abc:fx ")
        assertEquals(listOf(TranslationHop("ja", "zh-Hant", "deepl:v2")), free.route("ja", "zh-TW"))
        assertNull(free.route("ja", "ja"))
        assertEquals(listOf("D(一)", "D(二)"), free.translate(hop, listOf("一", "二")))
        val request = service.requests.single()
        assertEquals("api-free.deepl.com", request.url.host)
        assertEquals("DeepL-Auth-Key abc:fx", request.headers["Authorization"])
        val body = Json.parseToJsonElement(service.bodies.single()).jsonObject
        assertEquals(JsonPrimitive("JA"), body["source_lang"])
        assertEquals(JsonPrimitive("ZH-HANT"), body["target_lang"])
        assertFalse("abc:fx" in request.url.toString(), "the key never goes in the address")

        DeepLTranslationProvider(service.client, "paid-key").translate(hop, listOf("三"))
        assertEquals("api.deepl.com", service.requests.last().url.host)
    }

    @Test
    fun longChaptersAreSplitIntoRequestsTheServiceAccepts() = runBlocking<Unit> {
        val service = Service()
        val lines = (1..230).map { "行$it" }
        assertEquals(lines.map { "D($it)" }, DeepLTranslationProvider(service.client, "k").translate(hop, lines))
        assertEquals(5, service.requests.size)

        val google = Service()
        assertEquals(lines.map { "G($it)" }, GoogleTranslationProvider(google.client, "k").translate(hop, lines))
        assertEquals(3, google.requests.size)
        assertEquals("k", google.requests.first().url.parameters["key"])
        val body = Json.parseToJsonElement(google.bodies.first()).jsonObject
        assertEquals(JsonPrimitive("zh-TW"), body["target"])
        assertEquals(JsonPrimitive("text"), body["format"])
    }

    @Test
    fun refusalsAreToldApart() = runBlocking<Unit> {
        suspend fun failure(provider: TranslationProvider) = assertFailsWith<TranslationException> { provider.translate(hop, listOf("一")) }.failure
        assertEquals(TranslationFailure.INVALID_KEY, failure(DeepLTranslationProvider(Service(HttpStatusCode.Forbidden).client, "k")))
        assertEquals(TranslationFailure.QUOTA_EXCEEDED, failure(DeepLTranslationProvider(Service(HttpStatusCode(456, "Quota")).client, "k")))
        assertEquals(TranslationFailure.PROVIDER_UNAVAILABLE, failure(DeepLTranslationProvider(Service(HttpStatusCode.InternalServerError).client, "k")))
        val badKey = Service(HttpStatusCode.BadRequest, """{"error":{"message":"API key not valid. Please pass a valid API key."}}""")
        assertEquals(TranslationFailure.INVALID_KEY, failure(GoogleTranslationProvider(badKey.client, "k")))
        assertEquals(TranslationFailure.QUOTA_EXCEEDED, failure(GoogleTranslationProvider(Service(HttpStatusCode.TooManyRequests).client, "k")))
    }

    private object NoEngine : OfflineTranslationEngine {
        override suspend fun translate(model: OfflineModelPaths, lines: List<String>): List<String> = error("the offline engine must not run")
        override suspend fun unload() = Unit
    }

    private class Device(service: Service, val secrets: InMemoryPluginKeyValueStore? = InMemoryPluginKeyValueStore()) {
        var time = 1_000L
        val memory = TranslationMemoryStore(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        val translation = ReaderTranslation(memory, OfflineTranslationPlatform(NoEngine, "/nonexistent"), service.client, secrets, now = { time })
    }

    private fun request(text: String, chapter: String = "c1") = ChapterTranslationRequest("work", chapter, text, "ja", "zh-Hant")

    @Test
    fun theChosenServiceTranslatesWithTheStoredKeyAndItsTranslationsAreKeptAsPaid() = runBlocking<Unit> {
        val service = Service()
        val device = Device(service)
        assertEquals(TranslationProviderKind.OFFLINE, device.translation.preferences().provider)

        device.translation.setProvider(TranslationProviderKind.DEEPL)
        // Chosen but without a key: nothing is sent, and the reader is told why.
        assertEquals(TranslationFailure.INVALID_KEY, assertFailsWith<TranslationException> { device.translation.translate(request("空が青い")) { _, _ -> } }.failure)
        assertTrue(service.requests.isEmpty())

        device.translation.setKey(TranslationProviderKind.DEEPL, "abc:fx")
        assertEquals("abc:fx", device.secrets!!.getString("translation.deepl.secret"))
        assertTrue(device.translation.preferences().deepLKeySet)
        assertTrue(device.translation.missingModels("ja", "zh-Hant").isEmpty())

        val result = device.translation.translate(request("空が青い\n\n雨が降る")) { _, _ -> }
        assertEquals(listOf("D(空が青い)", null, "D(雨が降る)"), result.segments.map { it.translation })
        assertEquals(1, service.requests.size)
        assertTrue(device.memory.paidBytes() > 0 && device.memory.freeBytes() == 0L)
        assertEquals(8, device.translation.preferences().charactersUsedToday)

        // Read again: from the memory, nothing is sent or counted.
        assertTrue(device.translation.stored(request("空が青い\n\n雨が降る"))!!.complete)
        device.translation.translate(request("空が青い\n\n雨が降る")) { _, _ -> }
        assertEquals(1, service.requests.size)
        assertEquals(8, device.translation.preferences().charactersUsedToday)

        device.translation.setKey(TranslationProviderKind.DEEPL, "")
        assertNull(device.secrets.getString("translation.deepl.secret"))
    }

    @Test
    fun paidTranslationStopsAtTheDailyLimitAndStartsAgainTheNextDay() = runBlocking<Unit> {
        val service = Service()
        val device = Device(service)
        device.translation.setProvider(TranslationProviderKind.GOOGLE)
        device.translation.setKey(TranslationProviderKind.GOOGLE, "k")
        device.translation.setDailyCharacterLimit(10)

        device.translation.translate(request("一二三四五六")) { _, _ -> }
        val refused = assertFailsWith<TranslationException> { device.translation.translate(request("七八九十壱弐", "c2")) { _, _ -> } }
        assertEquals(TranslationFailure.DAILY_LIMIT, refused.failure)
        assertEquals(1, service.requests.size)
        assertEquals(6, device.translation.preferences().charactersUsedToday)

        device.time += 86_400_000L
        assertEquals(0, device.translation.preferences().charactersUsedToday)
        device.translation.translate(request("七八九十壱弐", "c2")) { _, _ -> }
        assertEquals(2, service.requests.size)

        device.translation.setDailyCharacterLimit(0)
        device.translation.translate(request("とても長い文章がここに続いていく", "c3")) { _, _ -> }
        assertEquals(3, service.requests.size)
    }

    @Test
    fun aFailedRequestIsNotCountedAgainstTheLimit() = runBlocking<Unit> {
        val device = Device(Service(HttpStatusCode.InternalServerError))
        device.translation.setProvider(TranslationProviderKind.DEEPL)
        device.translation.setKey(TranslationProviderKind.DEEPL, "k")
        assertFailsWith<TranslationException> { device.translation.translate(request("空が青い")) { _, _ -> } }
        assertEquals(0, device.translation.preferences().charactersUsedToday)
    }

    @Test
    fun whatWasSentBeforeALaterRequestFailedStaysCounted() = runBlocking<Unit> {
        val device = Device(Service(HttpStatusCode.InternalServerError, healthyRequests = 1))
        device.translation.setProvider(TranslationProviderKind.DEEPL)
        device.translation.setKey(TranslationProviderKind.DEEPL, "k")
        // More lines than go in one request: the first is answered, the second fails.
        val text = (1..60).joinToString("\n") { "行" + ('a' + it % 26) + it }
        assertFailsWith<TranslationException> { device.translation.translate(request(text)) { _, _ -> } }
        val used = device.translation.preferences().charactersUsedToday
        val all = text.lines().sumOf { it.length }
        assertTrue(used in 1 until all, "the answered request stays counted, the refused one does not: $used of $all")
    }

    @Test
    fun whatAnEarlierRequestReturnedIsKeptWhenALaterOneFails() = runBlocking<Unit> {
        // Lines long enough that one call to the provider becomes two requests.
        val lines = (1..40).map { index -> "行$index" + "あ".repeat(900) }
        val failing = Service(HttpStatusCode.InternalServerError, healthyRequests = 1)
        val store = TranslationMemoryStore(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        val service = TranslationService(store, now = { 1L })
        val request = ChapterTranslationRequest("work", "c1", lines.joinToString("\n"), "ja", "zh-Hant")
        assertFailsWith<TranslationException> { service.translate(request, DeepLTranslationProvider(failing.client, "k")) }
        val kept = service.stored(request, DeepLTranslationProvider(failing.client, "k"))!!.segments.count { it.translation != null }
        assertTrue(kept in 1 until lines.size, "the answered request's lines are stored: $kept")

        // The retry only sends what is still missing.
        val healthy = Service()
        service.translate(request, DeepLTranslationProvider(healthy.client, "k"))
        val resent = healthy.bodies.sumOf { body -> Json.parseToJsonElement(body).jsonObject.getValue("text").jsonArray.size }
        assertEquals(lines.size - kept, resent)
    }

    @Test
    fun aParagraphLongerThanTheEngineTakesIsTranslatedSentenceBySentence() = runBlocking<Unit> {
        val long = "これは長い文です。".repeat(1_000) // 9,000 characters in one line
        val pieces = OfflineTranslationProvider.splitLongLine(long)
        assertTrue(pieces.size >= 3 && pieces.all { it.length <= OfflineTranslationProvider.MAX_LINE_CHARACTERS && it.endsWith("。") })
        assertEquals(long, pieces.joinToString(""))
        assertEquals(listOf("短い"), OfflineTranslationProvider.splitLongLine("短い"))

        val seen = ArrayList<Int>()
        val engine = object : OfflineTranslationEngine {
            override suspend fun translate(model: OfflineModelPaths, lines: List<String>): List<String> {
                seen += lines.map { it.length }
                return lines.map { "[${it.length}]" }
            }
            override suspend fun unload() = Unit
        }
        val files = object : OfflineModelFiles {
            override fun installed(model: OfflineModel) = OfflineModelPaths("m", "s", "t", "l")
            override suspend fun download(model: OfflineModel, onProgress: (Long, Long) -> Unit) = Unit
            override fun remove(model: OfflineModel) = Unit
        }
        val provider = OfflineTranslationProvider(files, engine)
        val hop = provider.route("ja", "en")!!.single()
        val answers = provider.translate(hop, listOf("短い", long, "終わり"))
        assertEquals(3, answers.size)
        assertEquals("[2]", answers[0])
        assertEquals(pieces.joinToString(" ") { "[${it.length}]" }, answers[1])
        assertEquals("[3]", answers[2])
        assertTrue(seen.all { it <= OfflineTranslationProvider.MAX_LINE_CHARACTERS })
    }

    @Test
    fun withoutProtectedStorageOnlyTheOfflineEngineIsOffered() = runBlocking<Unit> {
        val device = Device(Service(), secrets = null)
        device.translation.setProvider(TranslationProviderKind.DEEPL)
        val preferences = device.translation.preferences()
        assertFalse(preferences.cloudAvailable)
        assertEquals(TranslationProviderKind.OFFLINE, preferences.provider)
    }
}
