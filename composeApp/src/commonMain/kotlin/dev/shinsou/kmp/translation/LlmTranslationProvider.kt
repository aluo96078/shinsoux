package dev.shinsou.kmp.translation

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** The wire format of a language-model service: three families cover nearly every provider. */
public enum class LlmProtocol(public val id: String, public val defaultBaseUrl: String, public val suggestedModel: String) {
    /** `POST {base}/chat/completions` with a bearer key: OpenAI and the many services and local servers that copy it. */
    OPENAI("openai", "https://api.openai.com/v1", ""),

    /** `POST {base}/v1/messages` with `x-api-key`: Anthropic's Messages API. */
    ANTHROPIC("anthropic", "https://api.anthropic.com", "claude-haiku-5-5"),

    /** `POST {base}/v1beta/models/{model}:generateContent` with `x-goog-api-key`: Google's Gemini API. */
    GOOGLE("google", "https://generativelanguage.googleapis.com", ""),
}

/** The language-model service the user set up. The key is kept apart, in protected storage. */
public data class LlmSettings(
    val protocol: LlmProtocol = LlmProtocol.OPENAI,
    val baseUrl: String = protocol.defaultBaseUrl,
    val model: String = protocol.suggestedModel,
) {
    public val configured: Boolean get() = model.isNotBlank() && normalizedLlmBaseUrl(baseUrl) != null
}

/**
 * [value] as a base address a request may be built on, or null when it must not be used:
 * HTTPS anywhere; plain HTTP only for this device and private networks (a model server run at
 * home), where a key in clear text does not cross the internet.
 */
public fun normalizedLlmBaseUrl(value: String): String? {
    val trimmed = value.trim().trimEnd('/')
    // The scheme must be written out: the URL parser would otherwise read any text as a path on this device.
    val lower = trimmed.lowercase()
    if (!(lower.startsWith("https://") || lower.startsWith("http://")) || trimmed.any { it.isWhitespace() || it.isISOControl() }) return null
    val url = runCatching { Url(trimmed) }.getOrNull() ?: return null
    if (url.host.isBlank() || !url.user.isNullOrEmpty() || !url.password.isNullOrEmpty()) return null
    if (url.parameters.isEmpty().not() || url.fragment.isNotEmpty()) return null
    return when (url.protocol.name.lowercase()) {
        "https" -> trimmed
        "http" -> trimmed.takeIf { isLocalLlmHost(url.host.lowercase()) }
        else -> null
    }
}

private fun isLocalLlmHost(host: String): Boolean {
    if (host == "localhost" || host.endsWith(".local") || host == "[::1]" || host == "::1") return true
    val parts = host.split('.').map { it.toIntOrNull() ?: return false }
    if (parts.size != 4 || parts.any { it !in 0..255 }) return false
    val (a, b) = parts
    return a == 127 || a == 10 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 169 && b == 254)
}

/**
 * Translation by a language model behind one of three wire formats. A batch of consecutive
 * lines goes out numbered and must come back numbered: the model sees its neighbours (so
 * pronouns and tone hold), and a line it merged, dropped or invented is caught by the numbers
 * and translated again later rather than shown in the wrong place.
 */
public class LlmTranslationProvider(
    http: HttpClient,
    private val settings: LlmSettings,
    private val apiKey: String,
    usage: TranslationUsage? = null,
) : CloudTranslationProvider(http, usage) {
    override val id: String get() = ID

    // The model is part of the identity: another model's translation is another translation.
    // So is the address: two servers may serve different models under one name ("default").
    override val engine: String
        get() = "llm:${settings.protocol.id}:${settings.model.trim()}@" +
            TranslationText.hash(normalizedLlmBaseUrl(settings.baseUrl).orEmpty()).take(12)
    override val maximumLines: Int get() = 40

    // What comes back must fit the answer the request allows for.
    override val maximumRequestBytes: Int get() = 9 * 1024

    override fun sourceCode(language: String): String? = LANGUAGE_NAMES[language]
    override fun targetCode(language: String): String? = LANGUAGE_NAMES[language]

    override suspend fun request(from: String, to: String, lines: List<String>): List<String> {
        val base = normalizedLlmBaseUrl(settings.baseUrl)
        if (base == null || settings.model.isBlank()) {
            throw TranslationException(TranslationFailure.NOT_CONFIGURED, "The language-model service has no usable address or model")
        }
        val system = systemPrompt(from, to)
        val user = lines.mapIndexed { index, line -> "[${index + 1}] ${line.replace('\n', ' ')}" }.joinToString("\n")
        val answer = when (settings.protocol) {
            LlmProtocol.OPENAI -> openAi(base, system, user)
            LlmProtocol.ANTHROPIC -> anthropic(base, system, user)
            LlmProtocol.GOOGLE -> google(base, system, user)
        }
        val parsed = parseNumberedLines(answer.text, lines.size).toMutableList()
        // An answer cut off at the output limit ends in half a line: that line is not a
        // translation, and is left to be asked for again rather than stored as one.
        if (answer.truncated) parsed.indexOfLast(String::isNotEmpty).takeIf { it >= 0 }?.let { parsed[it] = "" }
        if (parsed.all(String::isEmpty)) {
            throw TranslationException(TranslationFailure.ENGINE_FAILED, "$id returned no numbered lines")
        }
        return parsed
    }

    private suspend fun send(url: String, body: JsonObject, headers: Map<String, String>): JsonObject {
        val response: HttpResponse = http.post(url) {
            headers.forEach { (name, value) -> header(name, value) }
            // A model writing out forty lines can take longer than an ordinary API call.
            // The answer arrives in one piece at the end, so the wait between bytes is as long as the whole request.
            if (http.pluginOrNull(HttpTimeout) != null) {
                timeout {
                    requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
                    socketTimeoutMillis = REQUEST_TIMEOUT_MILLIS
                }
            }
            setBody(TextContent(body.toString(), ContentType.Application.Json))
        }
        if (response.status.value !in 200..299) fail(response)
        return runCatching { LlmJson.parseToJsonElement(response.bodyAsText()) as JsonObject }.getOrElse {
            throw TranslationException(TranslationFailure.ENGINE_FAILED, "$id answered with something that is not JSON", it)
        }
    }

    /** The model's text, and whether it stopped because it ran out of output rather than because it was done. */
    private class Answer(val text: String, val truncated: Boolean)

    private suspend fun openAi(base: String, system: String, user: String): Answer {
        val body = buildJsonObject {
            put("model", settings.model.trim())
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                add(buildJsonObject { put("role", "user"); put("content", user) })
            })
        }
        // A local server needs no key; sending an empty bearer would only be refused by stricter ones.
        val headers = if (apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${apiKey.trim()}")
        val choice = (send("$base/chat/completions", body, headers)["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        if (choice.string("finish_reason") == "content_filter") refused()
        val message = choice?.get("message") as? JsonObject
        message.string("refusal")?.takeIf(String::isNotBlank)?.let { refused() }
        return Answer(message.string("content").orEmpty(), truncated = choice.string("finish_reason") == "length")
    }

    private suspend fun anthropic(base: String, system: String, user: String): Answer {
        val body = buildJsonObject {
            put("model", settings.model.trim())
            put("max_tokens", MAX_OUTPUT_TOKENS)
            put("system", system)
            put("messages", buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", user) }) })
        }
        val headers = buildMap {
            if (apiKey.isNotBlank()) put("x-api-key", apiKey.trim())
            put("anthropic-version", ANTHROPIC_VERSION)
        }
        val message = send("$base/v1/messages", body, headers)
        // A declined request is a normal answer with this stop reason, not an HTTP error.
        if (message.string("stop_reason") == "refusal") refused()
        // Text blocks only: a model that reasons first also returns blocks that are not the answer.
        val text = (message["content"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.takeIf { block -> block.string("type") == "text" }.string("text") }
            .joinToString("")
        return Answer(text, truncated = message.string("stop_reason") == "max_tokens")
    }

    private suspend fun google(base: String, system: String, user: String): Answer {
        val body = buildJsonObject {
            put("systemInstruction", buildJsonObject { put("parts", buildJsonArray { add(buildJsonObject { put("text", system) }) }) })
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray { add(buildJsonObject { put("text", user) }) })
                })
            })
        }
        val headers = if (apiKey.isBlank()) emptyMap() else mapOf("x-goog-api-key" to apiKey.trim())
        val answer = send("$base/v1beta/models/${settings.model.trim()}:generateContent", body, headers)
        (answer["promptFeedback"] as? JsonObject).string("blockReason")?.let { refused() }
        val candidate = (answer["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
        if (candidate.string("finishReason") in GOOGLE_REFUSALS) refused()
        val text = ((candidate?.get("content") as? JsonObject)?.get("parts") as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject).string("text") }
            .joinToString("")
        return Answer(text, truncated = candidate.string("finishReason") == "MAX_TOKENS")
    }

    private fun refused(): Nothing =
        throw TranslationException(TranslationFailure.REFUSED, "$id declined to translate this text")

    private fun systemPrompt(from: String, to: String): String = """
        You are a literary translator. Translate a passage of a novel from $from into $to.

        The passage arrives as numbered lines, each written as [n] followed by the text. They are consecutive lines of one chapter: read them together so that pronouns, speakers and tone stay consistent, then translate every line.

        Answer with the same numbered lines and nothing else: one output line per input line, each starting with the same [n], in the same order. Never merge two lines, split one, skip one or add one, and write no notes, headings or explanations.

        Write natural, fluent $to as a published translation of this genre would read, keeping the meaning, the register and the line's own punctuation style (dialogue stays dialogue). Translate names consistently.

        A token of the form N12X (the letter N, digits, the letter X) stands for a name the reader has fixed. Copy every such token into your translation exactly as written, where the name belongs; never translate, alter or drop it.
    """.trimIndent()

    public companion object {
        public const val ID: String = "llm"
        private const val ANTHROPIC_VERSION = "2023-06-01"

        /** Room for forty lines of prose and whatever the model reasons before them. */
        private const val MAX_OUTPUT_TOKENS = 16_000
        private const val REQUEST_TIMEOUT_MILLIS = 180_000L
        private val GOOGLE_REFUSALS = setOf("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "RECITATION", "SPII")
        private val LlmJson = Json { ignoreUnknownKeys = true }

        private val LANGUAGE_NAMES = mapOf(
            "zh-Hant" to "Traditional Chinese as written in Taiwan",
            "zh-Hans" to "Simplified Chinese",
            "ja" to "Japanese",
            "ko" to "Korean",
            "en" to "English",
        )
        private val NUMBERED = Regex("""^\s*\[(\d{1,4})]\s?(.*)$""")

        /**
         * The model's answer as [count] lines by number; "" for a number it left out, which the
         * caller treats as not translated. Text before the first number is ignored, a line without
         * a number continues the one above, and a number given twice keeps its first text.
         */
        internal fun parseNumberedLines(answer: String, count: Int): List<String> {
            val out = arrayOfNulls<StringBuilder>(count)
            var current: StringBuilder? = null
            for (raw in answer.lineSequence()) {
                val match = NUMBERED.find(raw)
                if (match == null) {
                    if (raw.isNotBlank()) current?.append(' ')?.append(raw.trim())
                    continue
                }
                val index = match.groupValues[1].toInt() - 1
                current = if (index in 0 until count && out[index] == null) StringBuilder(match.groupValues[2].trim()).also { out[index] = it } else null
            }
            return out.map { it?.toString()?.trim().orEmpty() }
        }
    }
}

private fun JsonObject?.string(name: String): String? = (this?.get(name) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
