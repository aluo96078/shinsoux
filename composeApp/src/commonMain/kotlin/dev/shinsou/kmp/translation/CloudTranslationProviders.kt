package dev.shinsou.kmp.translation

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val CloudJson = Json { ignoreUnknownKeys = true }

/**
 * Translation services the user brings a key for. What is read is sent to the service: the
 * reader only uses one after the user chose it in the translation settings.
 */
public abstract class CloudTranslationProvider(
    protected val http: HttpClient,
    /** Counts what is sent against the user's daily limit; null leaves it uncounted. */
    private val usage: TranslationUsage? = null,
) : TranslationProvider {
    final override val paid: Boolean get() = true

    /** The service's code for one of the reader's languages, or null when the service lacks it. */
    protected abstract fun sourceCode(language: String): String?
    protected abstract fun targetCode(language: String): String?
    protected abstract val engine: String
    protected abstract val maximumLines: Int
    protected abstract suspend fun request(from: String, to: String, lines: List<String>): List<String>

    final override fun route(from: String, to: String): List<TranslationHop>? {
        val source = OfflineModelCatalog.language(from) ?: return null
        val target = OfflineModelCatalog.language(to) ?: return null
        if (source == target || sourceCode(source) == null || targetCode(target) == null) return null
        return listOf(TranslationHop(source, target, engine))
    }

    final override suspend fun translate(hop: TranslationHop, segments: List<String>): List<String> {
        val from = sourceCode(hop.from) ?: throw TranslationException(TranslationFailure.UNSUPPORTED_PAIR, "$id cannot translate from ${hop.from}")
        val to = targetCode(hop.to) ?: throw TranslationException(TranslationFailure.UNSUPPORTED_PAIR, "$id cannot translate into ${hop.to}")
        val out = ArrayList<String>(segments.size)
        // Bounded by the service's line count and by a request size well under its limit.
        var batch = ArrayList<String>()
        var bytes = 0
        suspend fun flush() {
            if (batch.isEmpty()) return
            // Counted request by request, before it is sent: with translation on, every chapter
            // opened is sent, and that must not turn into an unexpected bill.
            val characters = batch.sumOf { it.length }.toLong()
            if (usage?.reserve(characters) == false) {
                throw TranslationException(TranslationFailure.DAILY_LIMIT, "Today's translation limit is used up")
            }
            val answers = try {
                request(from, to, batch)
            } catch (error: CancellationException) {
                throw error // it may have been sent and charged: it stays counted
            } catch (error: TranslationException) {
                // Only a refusal the service answered with is known not to be charged.
                if (error.failure != TranslationFailure.ENGINE_FAILED) usage?.release(characters)
                throw error
            } catch (error: Exception) {
                throw TranslationException(TranslationFailure.PROVIDER_UNAVAILABLE, "$id could not be reached: ${error.message}", error)
            }
            if (answers.size != batch.size) throw TranslationException(TranslationFailure.ENGINE_FAILED, "$id returned ${answers.size} lines for ${batch.size}")
            out += answers
            batch = ArrayList()
            bytes = 0
        }
        try {
            for (segment in segments) {
                val size = segment.encodeToByteArray().size
                if (batch.size >= maximumLines || bytes + size > MAX_REQUEST_TEXT_BYTES) flush()
                batch += segment
                bytes += size
            }
            flush()
        } catch (error: TranslationException) {
            // What earlier requests of this call already returned was paid for: hand it back.
            if (out.isEmpty()) throw error
            throw TranslationException(error.failure, error.message ?: "$id failed", error, completed = out.toList())
        }
        return out
    }

    /** Maps the service's refusal to what the reader can tell the user. */
    protected suspend fun fail(response: HttpResponse): Nothing {
        val status = response.status.value
        val detail = runCatching { response.bodyAsText().take(300) }.getOrDefault("")
        val failure = when (status) {
            401, 403 -> TranslationFailure.INVALID_KEY
            429, 456, 529 -> TranslationFailure.QUOTA_EXCEEDED
            else -> TranslationFailure.PROVIDER_UNAVAILABLE
        }
        throw TranslationException(failure, "$id answered HTTP $status ${detail.replace('\n', ' ')}".trim())
    }

    protected companion object {
        const val MAX_REQUEST_TEXT_BYTES = 60 * 1024
    }
}

/** DeepL's `/v2/translate`. A free-plan key (it ends in `:fx`) uses the free endpoint. */
public class DeepLTranslationProvider(
    http: HttpClient,
    private val apiKey: String,
    usage: TranslationUsage? = null,
) : CloudTranslationProvider(http, usage) {
    override val id: String get() = ID
    override val engine: String get() = "deepl:v2"
    override val maximumLines: Int get() = 50

    override fun sourceCode(language: String): String? = when (language) {
        "zh-Hans", "zh-Hant" -> "ZH"
        "ja" -> "JA"
        "ko" -> "KO"
        "en" -> "EN"
        else -> null
    }

    override fun targetCode(language: String): String? = when (language) {
        "zh-Hans" -> "ZH-HANS"
        "zh-Hant" -> "ZH-HANT"
        "ja" -> "JA"
        "ko" -> "KO"
        "en" -> "EN-US"
        else -> null
    }

    override suspend fun request(from: String, to: String, lines: List<String>): List<String> {
        val host = if (apiKey.trim().endsWith(":fx")) "api-free.deepl.com" else "api.deepl.com"
        val response = http.post("https://$host/v2/translate") {
            header("Authorization", "DeepL-Auth-Key ${apiKey.trim()}")
            setBody(TextContent(CloudJson.encodeToString(Request.serializer(), Request(lines, from, to)), ContentType.Application.Json))
        }
        if (response.status.value !in 200..299) fail(response)
        return CloudJson.decodeFromString(Response.serializer(), response.bodyAsText()).translations.map { it.text }
    }

    @Serializable
    private data class Request(val text: List<String>, val source_lang: String, val target_lang: String)

    @Serializable
    private data class Response(val translations: List<Translation> = emptyList())

    @Serializable
    private data class Translation(val text: String = "")

    public companion object {
        public const val ID: String = "deepl"
    }
}

/** Google Cloud Translation, Basic edition (`/language/translate/v2`), with an API key. */
public class GoogleTranslationProvider(
    http: HttpClient,
    private val apiKey: String,
    usage: TranslationUsage? = null,
) : CloudTranslationProvider(http, usage) {
    override val id: String get() = ID
    override val engine: String get() = "google:v2"
    override val maximumLines: Int get() = 100 // the service takes at most 128 strings

    override fun sourceCode(language: String): String? = code(language)
    override fun targetCode(language: String): String? = code(language)

    private fun code(language: String): String? = when (language) {
        "zh-Hans" -> "zh-CN"
        "zh-Hant" -> "zh-TW"
        "ja", "ko", "en" -> language
        else -> null
    }

    override suspend fun request(from: String, to: String, lines: List<String>): List<String> {
        val response = http.post("https://translation.googleapis.com/language/translate/v2") {
            parameter("key", apiKey.trim())
            // "text": the default, "html", returns HTML entities for quotes and ampersands.
            setBody(TextContent(CloudJson.encodeToString(Request.serializer(), Request(lines, from, to, "text")), ContentType.Application.Json))
        }
        if (response.status.value !in 200..299) {
            // A missing or rejected API key comes back as 400 here, not 401 or 403.
            if (response.status.value == 400 && "API key" in runCatching { response.bodyAsText() }.getOrDefault("")) {
                throw TranslationException(TranslationFailure.INVALID_KEY, "google answered HTTP 400: API key not valid")
            }
            fail(response)
        }
        return CloudJson.decodeFromString(Response.serializer(), response.bodyAsText()).data.translations.map { it.translatedText }
    }

    @Serializable
    private data class Request(val q: List<String>, val source: String, val target: String, val format: String)

    @Serializable
    private data class Response(val data: Data = Data())

    @Serializable
    private data class Data(val translations: List<Translation> = emptyList())

    @Serializable
    private data class Translation(val translatedText: String = "")

    public companion object {
        public const val ID: String = "google"
    }
}

/** Characters sent to paid providers today, against the user's limit. */
public interface TranslationUsage {
    /** Counts [characters] when they fit in today's limit; false when they do not. */
    public fun reserve(characters: Long): Boolean
    public fun release(characters: Long)
}
