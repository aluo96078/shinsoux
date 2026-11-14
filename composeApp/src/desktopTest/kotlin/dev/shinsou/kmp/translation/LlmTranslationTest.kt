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
import kotlinx.serialization.json.JsonObject
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LlmTranslationTest {
    /** A model that answers each numbered line with "譯" + its text, in the shape of the protocol asked. */
    private class Model(
        private val status: HttpStatusCode = HttpStatusCode.OK,
        private val rewrite: (String) -> String = { it },
        private val wrap: ((String) -> JsonObject)? = null,
    ) {
        val requests = ArrayList<HttpRequestData>()
        val bodies = ArrayList<JsonObject>()
        val client = HttpClient(
            MockEngine { request ->
                requests += request
                val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                bodies += body
                if (status != HttpStatusCode.OK) return@MockEngine respond("{}", status)
                val path = request.url.encodedPath
                val user = when {
                    path.endsWith("/chat/completions") -> body.getValue("messages").jsonArray.last().jsonObject.getValue("content").jsonPrimitive.content
                    path.endsWith("/v1/messages") -> body.getValue("messages").jsonArray.last().jsonObject.getValue("content").jsonPrimitive.content
                    else -> body.getValue("contents").jsonArray.last().jsonObject.getValue("parts").jsonArray.first().jsonObject.getValue("text").jsonPrimitive.content
                }
                val text = rewrite(user.lines().joinToString("\n") { line -> line.replaceFirst("] ", "] 譯") })
                val answer = wrap?.invoke(text) ?: when {
                    path.endsWith("/chat/completions") -> buildJsonObject {
                        put("choices", buildJsonArray {
                            add(buildJsonObject {
                                put("finish_reason", "stop")
                                put("message", buildJsonObject { put("role", "assistant"); put("content", text) })
                            })
                        })
                    }
                    path.endsWith("/v1/messages") -> buildJsonObject {
                        put("stop_reason", "end_turn")
                        put("content", buildJsonArray {
                            add(buildJsonObject { put("type", "thinking"); put("thinking", "") })
                            add(buildJsonObject { put("type", "text"); put("text", text) })
                        })
                    }
                    else -> buildJsonObject {
                        put("candidates", buildJsonArray {
                            add(buildJsonObject {
                                put("finishReason", "STOP")
                                put("content", buildJsonObject { put("parts", buildJsonArray { add(buildJsonObject { put("text", text) }) }) })
                            })
                        })
                    }
                }
                respond(answer.toString(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        )
    }

    private val hop = TranslationHop("ko", "zh-Hant", "x")

    private fun provider(model: Model, protocol: LlmProtocol, base: String = protocol.defaultBaseUrl, name: String = "m-1", key: String = "secret") =
        LlmTranslationProvider(model.client, LlmSettings(protocol, base, name), key)

    @Test
    fun eachWireFormatSendsTheKeyAndTheModelWhereItsServiceExpectsThem() = runBlocking<Unit> {
        val lines = listOf("안녕", "세계")
        val expected = listOf("譯안녕", "譯세계")

        val openAi = Model()
        assertEquals(expected, provider(openAi, LlmProtocol.OPENAI).translate(hop, lines))
        assertEquals("https://api.openai.com/v1/chat/completions", openAi.requests.single().url.toString())
        assertEquals("Bearer secret", openAi.requests.single().headers["Authorization"])
        assertEquals("m-1", openAi.bodies.single().getValue("model").jsonPrimitive.content)
        assertEquals(listOf("system", "user"), openAi.bodies.single().getValue("messages").jsonArray.map { it.jsonObject.getValue("role").jsonPrimitive.content })
        // Nothing the newer models refuse: no sampling settings are sent.
        assertFalse("temperature" in openAi.bodies.single())

        val anthropic = Model()
        assertEquals(expected, provider(anthropic, LlmProtocol.ANTHROPIC).translate(hop, lines))
        val sent = anthropic.requests.single()
        assertEquals("https://api.anthropic.com/v1/messages", sent.url.toString())
        assertEquals("secret", sent.headers["x-api-key"])
        assertNotNull(sent.headers["anthropic-version"])
        assertNull(sent.headers["Authorization"])
        val body = anthropic.bodies.single()
        assertTrue(body.getValue("max_tokens").jsonPrimitive.content.toInt() > 0)
        assertTrue("Traditional Chinese" in body.getValue("system").jsonPrimitive.content)
        assertFalse("temperature" in body)
        assertEquals("[1] 안녕\n[2] 세계", body.getValue("messages").jsonArray.single().jsonObject.getValue("content").jsonPrimitive.content)

        val google = Model()
        assertEquals(expected, provider(google, LlmProtocol.GOOGLE, name = "g-model").translate(hop, lines))
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/g-model:generateContent", google.requests.single().url.toString())
        assertEquals("secret", google.requests.single().headers["x-goog-api-key"])
        assertFalse("secret" in google.requests.single().url.toString(), "the key never goes in the address")
    }

    @Test
    fun aGatewayOrALocalServerIsReachedAtItsOwnAddressAndMayNeedNoKey() = runBlocking<Unit> {
        val local = Model()
        provider(local, LlmProtocol.OPENAI, base = "http://127.0.0.1:11434/v1/", key = "").translate(hop, listOf("안녕"))
        assertEquals("http://127.0.0.1:11434/v1/chat/completions", local.requests.single().url.toString())
        assertNull(local.requests.single().headers["Authorization"])

        assertEquals("https://gateway.example/api", normalizedLlmBaseUrl(" https://gateway.example/api/ "))
        assertNotNull(normalizedLlmBaseUrl("http://192.168.1.20:8080"))
        assertNotNull(normalizedLlmBaseUrl("http://localhost:1234/v1"))
        // A key in clear text must not cross the internet.
        assertNull(normalizedLlmBaseUrl("http://gateway.example/v1"))
        assertNull(normalizedLlmBaseUrl("http://8.8.8.8/v1"))
        assertNull(normalizedLlmBaseUrl("ftp://gateway.example"))
        assertNull(normalizedLlmBaseUrl("https://user:pass@gateway.example"))
        assertNull(normalizedLlmBaseUrl("not an address"))

        val unusable = LlmTranslationProvider(Model().client, LlmSettings(LlmProtocol.OPENAI, "http://gateway.example/v1", "m"), "k")
        assertEquals(TranslationFailure.NOT_CONFIGURED, assertFailsWith<TranslationException> { unusable.translate(hop, listOf("안녕")) }.failure)
    }

    @Test
    fun linesTheModelMergedDroppedOrInventedAreNotMisplaced() {
        fun parse(answer: String, count: Int) = LlmTranslationProvider.parseNumberedLines(answer, count)
        assertEquals(listOf("一", "二", "三"), parse("[1] 一\n[2] 二\n[3] 三", 3))
        // Chatter around the answer, a dropped line, a number that does not exist, a repeated number.
        assertEquals(listOf("一", "", "三"), parse("Here is the translation:\n[1] 一\n[3] 三\n[9] 九\n[1] 又一", 3))
        // A line the model wrapped continues the one above it.
        assertEquals(listOf("一 接續", "二"), parse("[1] 一\n接續\n[2] 二", 2))
        assertEquals(listOf("", ""), parse("我無法翻譯這段內容。", 2))
    }

    @Test
    fun aDroppedLineIsTranslatedAgainLaterWhileTheOthersAreKept() = runBlocking<Unit> {
        var drop = true
        val model = Model(rewrite = { text -> if (drop) text.lines().filterNot { it.startsWith("[2]") }.joinToString("\n") else text })
        val store = TranslationMemoryStore(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        val service = TranslationService(store, now = { 1L })
        val request = ChapterTranslationRequest("work", "c1", "하나\n둘\n셋", "ko", "zh-Hant")
        val first = service.translate(request, provider(model, LlmProtocol.ANTHROPIC))
        assertEquals(listOf("譯하나", null, "譯셋"), first.segments.map { it.translation })
        assertFalse(first.complete)

        drop = false
        val second = service.translate(request, provider(model, LlmProtocol.ANTHROPIC))
        assertTrue(second.complete)
        // Only the missing line went out again.
        assertEquals("[1] 둘", model.bodies.last().getValue("messages").jsonArray.single().jsonObject.getValue("content").jsonPrimitive.content)
    }

    @Test
    fun aRefusalIsToldApartFromAFailureInEveryFormat() = runBlocking<Unit> {
        suspend fun failure(model: Model, protocol: LlmProtocol) =
            assertFailsWith<TranslationException> { provider(model, protocol).translate(hop, listOf("안녕")) }.failure

        val anthropic = Model(wrap = { buildJsonObject { put("stop_reason", "refusal"); put("content", buildJsonArray { }) } })
        assertEquals(TranslationFailure.REFUSED, failure(anthropic, LlmProtocol.ANTHROPIC))
        val openAi = Model(wrap = {
            buildJsonObject { put("choices", buildJsonArray { add(buildJsonObject { put("finish_reason", "content_filter"); put("message", buildJsonObject { }) }) }) }
        })
        assertEquals(TranslationFailure.REFUSED, failure(openAi, LlmProtocol.OPENAI))
        val google = Model(wrap = { buildJsonObject { put("promptFeedback", buildJsonObject { put("blockReason", "SAFETY") }) } })
        assertEquals(TranslationFailure.REFUSED, failure(google, LlmProtocol.GOOGLE))

        assertEquals(TranslationFailure.INVALID_KEY, failure(Model(HttpStatusCode.Unauthorized), LlmProtocol.OPENAI))
        assertEquals(TranslationFailure.QUOTA_EXCEEDED, failure(Model(HttpStatusCode.TooManyRequests), LlmProtocol.ANTHROPIC))
        // An answer with no numbered line at all is a failure, not an empty translation.
        assertEquals(TranslationFailure.ENGINE_FAILED, failure(Model(rewrite = { "抱歉，我不確定你的意思。" }), LlmProtocol.GOOGLE))
    }

    @Test
    fun anAnswerCutOffAtTheOutputLimitDoesNotStoreItsHalfLine() = runBlocking<Unit> {
        val lines = listOf("하나", "둘", "셋")
        val openAi = Model(wrap = { text ->
            buildJsonObject {
                put("choices", buildJsonArray {
                    add(buildJsonObject {
                        put("finish_reason", "length")
                        put("message", buildJsonObject { put("content", text.lines().take(2).joinToString("\n")) })
                    })
                })
            }
        })
        assertEquals(listOf("譯하나", "", ""), provider(openAi, LlmProtocol.OPENAI).translate(hop, lines))
        val anthropic = Model(wrap = { text ->
            buildJsonObject {
                put("stop_reason", "max_tokens")
                put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", text.lines().take(2).joinToString("\n")) }) })
            }
        })
        assertEquals(listOf("譯하나", "", ""), provider(anthropic, LlmProtocol.ANTHROPIC).translate(hop, lines))
        val google = Model(wrap = { text ->
            buildJsonObject {
                put("candidates", buildJsonArray {
                    add(buildJsonObject {
                        put("finishReason", "MAX_TOKENS")
                        put("content", buildJsonObject { put("parts", buildJsonArray { add(buildJsonObject { put("text", text.lines().take(2).joinToString("\n")) }) }) })
                    })
                })
            }
        })
        assertEquals(listOf("譯하나", "", ""), provider(google, LlmProtocol.GOOGLE).translate(hop, lines))
    }

    @Test
    fun anotherAddressIsAnotherEngineAndARefusalStaysCounted() = runBlocking<Unit> {
        val a = LlmTranslationProvider(Model().client, LlmSettings(LlmProtocol.OPENAI, "http://127.0.0.1:1234/v1", "default"), "")
        val b = LlmTranslationProvider(Model().client, LlmSettings(LlmProtocol.OPENAI, "http://127.0.0.1:8080/v1", "default"), "")
        assertTrue(a.route("ko", "zh-Hant")!!.single().engine != b.route("ko", "zh-Hant")!!.single().engine)

        val refusing = Model(wrap = { buildJsonObject { put("stop_reason", "refusal"); put("content", buildJsonArray { }) } })
        val memory = TranslationMemoryStore(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        val translation = ReaderTranslation(memory, OfflineTranslationPlatform(NoEngine, "/nonexistent"), refusing.client, InMemoryPluginKeyValueStore(), now = { 1_000L })
        translation.setProvider(TranslationProviderKind.LLM)
        translation.setLlmSettings(LlmSettings(LlmProtocol.ANTHROPIC, "https://api.anthropic.com", "m"))
        val request = ChapterTranslationRequest("work", "c1", "하나\n둘", "ko", "zh-Hant")
        assertEquals(TranslationFailure.REFUSED, assertFailsWith<TranslationException> { translation.translate(request) { _, _ -> } }.failure)
        // The model read the text to decline it: that is counted, so retries cannot run past the limit unseen.
        assertEquals(3, translation.preferences().charactersUsedToday)
    }

    private object NoEngine : OfflineTranslationEngine {
        override suspend fun translate(model: OfflineModelPaths, lines: List<String>): List<String> = error("the offline engine must not run")
        override suspend fun unload() = Unit
    }

    @Test
    fun theReaderUsesTheServiceTheUserSetUpAndKeepsItsTranslationsPerModel() = runBlocking<Unit> {
        val model = Model()
        val secrets = InMemoryPluginKeyValueStore()
        val memory = TranslationMemoryStore(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        val translation = ReaderTranslation(memory, OfflineTranslationPlatform(NoEngine, "/nonexistent"), model.client, secrets, now = { 1_000L })
        val request = ChapterTranslationRequest("work", "c1", "하나\n둘", "ko", "zh-Hant")

        translation.setProvider(TranslationProviderKind.LLM)
        // The default service has no model named yet: nothing is sent.
        assertEquals(TranslationFailure.NOT_CONFIGURED, assertFailsWith<TranslationException> { translation.translate(request) { _, _ -> } }.failure)
        assertTrue(model.requests.isEmpty())
        assertFailsWith<IllegalArgumentException> { translation.setLlmSettings(LlmSettings(LlmProtocol.OPENAI, "http://gateway.example", "m")) }

        translation.setLlmSettings(LlmSettings(LlmProtocol.ANTHROPIC, "https://api.anthropic.com/", " claude-haiku-5-5 "))
        translation.setKey(TranslationProviderKind.LLM, "sk-test")
        val preferences = translation.preferences()
        assertEquals(LlmSettings(LlmProtocol.ANTHROPIC, "https://api.anthropic.com", "claude-haiku-5-5"), preferences.llm)
        assertTrue(preferences.llmKeySet)
        assertEquals("sk-test", secrets.getString("translation.llm.secret"))
        assertTrue(translation.supports("ko", "zh-Hant") && translation.missingModels("ko", "zh-Hant").isEmpty())

        assertEquals(listOf("譯하나", "譯둘"), translation.translate(request) { _, _ -> }.segments.map { it.translation })
        assertEquals(1, model.requests.size)
        assertTrue(memory.paidBytes() > 0)
        assertEquals(3, translation.preferences().charactersUsedToday)
        // Read again: from the memory.
        translation.translate(request) { _, _ -> }
        assertEquals(1, model.requests.size)

        // Another model is another translation: its lines are asked for afresh.
        translation.setLlmSettings(LlmSettings(LlmProtocol.ANTHROPIC, "https://api.anthropic.com", "another-model"))
        assertFalse(translation.stored(request)!!.complete)
        translation.translate(request) { _, _ -> }
        assertEquals(2, model.requests.size)
    }
}
