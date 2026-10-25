package dev.shinsou.kmp.plugin

import io.ktor.http.Url
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Host-only image transport. Unsupported platforms keep their existing pinned native path. */
public expect fun createPlatformReviewedImageTransport(): PluginHttpTransport?

internal const val REVIEWED_BROWSER_IMAGE_MAX_BYTES: Int = 4 * 1024 * 1024
internal const val REVIEWED_BROWSER_IMAGE_MAX_PIXELS: Int = 8 * 1024 * 1024
internal const val REVIEWED_BROWSER_IMAGE_ORIGIN: String = "https://i.motiezw.com"
private val REVIEWED_BROWSER_IMAGE_PATH = Regex("^/[0-9]+/[0-9]+/[0-9]+/[0-9]+\\.(?:avif|webp|jpg|jpeg|png)$")

internal fun isReviewedBrowserImageUrl(value: String): Boolean = runCatching {
    val url = Url(value)
    value.length <= 2048 && url.protocol.name == "https" && url.host == "i.motiezw.com" &&
        url.port == 443 && url.user.isNullOrEmpty() && url.password.isNullOrEmpty() &&
        '?' !in value && '#' !in value && '@' !in value &&
        value == REVIEWED_BROWSER_IMAGE_ORIGIN + url.encodedPath &&
        REVIEWED_BROWSER_IMAGE_PATH.matches(url.encodedPath)
}.getOrDefault(false)

/** Rechecked by the platform before creating any browser or loopback proxy. */
internal fun validateReviewedBrowserImageRequest(request: PluginHttpRequest, resolution: PluginHostResolution) {
    require(request.method == "GET" && request.body.isEmpty() && request.headers.isEmpty()) {
        "Reviewed browser images permit only host-owned credential-free GET requests"
    }
    require(isReviewedBrowserImageUrl(request.url)) { "Image is outside the reviewed browser URL scope" }
    require(request.maxResponseBytes in 1..REVIEWED_BROWSER_IMAGE_MAX_BYTES) { "Invalid browser image byte limit" }
    require(resolution.host == "i.motiezw.com" && resolution.addresses.size in 1..32 &&
        resolution.addresses.all { isPluginIpLiteral(it) && !isBlockedPluginAddress(it) }) {
        "Browser image requires an exact public DNS resolution"
    }
}

/** Runs only in a fresh, host-owned same-CDN-origin document behind the pinned CONNECT proxy. */
internal fun reviewedBrowserImageStartScript(request: PluginHttpRequest): String {
    require(isReviewedBrowserImageUrl(request.url))
    require(request.maxResponseBytes in 1..REVIEWED_BROWSER_IMAGE_MAX_BYTES)
    return """
        (() => {
          const controller = new AbortController();
          globalThis.__shinsouImage = { controller, result: null };
          const slot = globalThis.__shinsouImage;
          (async () => {
            let stage = 'connect';
            try {
              const response = await fetch(${JsonPrimitive(request.url)}, {
                credentials: 'omit', redirect: 'error', cache: 'no-store',
                referrerPolicy: 'no-referrer', signal: controller.signal
              });
              stage = 'http';
              if (!response.ok) throw new Error('http');
              stage = 'type';
              const type = (response.headers.get('Content-Type') || '').split(';')[0].trim().toLowerCase();
              if (!/^(?:image\/(?:avif|webp|jpeg|png)|application\/octet-stream|binary\/octet-stream)${'$'}/.test(type)) throw new Error('type');
              stage = 'stream';
              if (!response.body || !response.body.getReader) throw new Error('stream');
              const reader = response.body.getReader();
              const chunks = []; let total = 0;
              while (true) {
                const part = await reader.read();
                if (part.done) break;
                total += part.value.byteLength;
                if (total > ${request.maxResponseBytes}) { stage = 'size'; await reader.cancel(); throw new Error('size'); }
                chunks.push(part.value);
              }
              stage = 'encoding';
              let bytes = new Uint8Array(total); let offset = 0;
              for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
              let outputType = type;
              // The app's Skia build cannot decode AVIF. WebKit can; normalize only this
              // reviewed format to lossless PNG while keeping the existing byte reader.
              if (${JsonPrimitive(request.url)}.endsWith('.avif')) {
                stage = 'decode';
                const extent = (${reviewedAvifDimensionParserScript()})(bytes);
                const bitmap = await createImageBitmap(new Blob([bytes], { type: 'image/avif' }));
                try {
                  if (bitmap.width < 1 || bitmap.height < 1 || bitmap.width > 8192 || bitmap.height > 8192 ||
                      bitmap.width * bitmap.height > $REVIEWED_BROWSER_IMAGE_MAX_PIXELS ||
                      bitmap.width !== extent.width || bitmap.height !== extent.height) throw new Error('dimensions');
                  const canvas = document.createElement('canvas');
                  canvas.width = bitmap.width; canvas.height = bitmap.height;
                  try {
                    canvas.getContext('2d').drawImage(bitmap, 0, 0);
                    const png = await new Promise(resolve => canvas.toBlob(resolve, 'image/png'));
                    stage = 'size';
                    if (!png || png.size < 1 || png.size > ${request.maxResponseBytes}) throw new Error('size');
                    bytes = new Uint8Array(await png.arrayBuffer());
                    outputType = 'image/png';
                  } finally { canvas.width = 0; canvas.height = 0; }
                } finally { bitmap.close(); }
              }
              stage = 'encoding';
              let binary = '';
              for (let start = 0; start < bytes.length; start += 8192) {
                binary += String.fromCharCode.apply(null, bytes.subarray(start, start + 8192));
              }
              slot.result = { status: response.status, contentType: outputType, bodyBase64: btoa(binary) };
            } catch (_) { slot.result = { error: 'browser_image_failed', stage }; }
          })();
          return 'started';
        })()
    """.trimIndent()
}

internal fun reviewedBrowserImagePollScript(): String =
    "JSON.stringify(globalThis.__shinsouImage && globalThis.__shinsouImage.result)"

internal fun reviewedBrowserImageCleanupScript(): String =
    "(() => {if(globalThis.__shinsouImage){globalThis.__shinsouImage.controller.abort();delete globalThis.__shinsouImage;}return 'cleaned';})()"

/** Bounded binary result; never forwards browser error details or response credentials. */
internal fun decodeReviewedBrowserImageResult(raw: String?, maxBytes: Int): PluginHttpResponse? {
    require(maxBytes in 1..REVIEWED_BROWSER_IMAGE_MAX_BYTES)
    if (raw.isNullOrBlank() || raw == "null" || raw == "undefined") return null
    require(raw.length <= ((maxBytes + 2) / 3) * 4 + 1024) { "Browser image result is too large" }
    val json = PluginJson.parseToJsonElement(raw) as? JsonObject
        ?: throw IllegalArgumentException("Invalid browser image result")
    if (json["error"] != null) {
        val stage = (json["stage"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it in setOf("connect", "http", "type", "stream", "size", "decode", "encoding") }
            ?: "unknown"
        throw IllegalArgumentException("Reviewed browser image request failed during $stage")
    }
    require(json.keys == setOf("status", "contentType", "bodyBase64")) { "Invalid browser image fields" }
    val statusValue = requireNotNull(json["status"] as? JsonPrimitive)
    require(!statusValue.isString) { "Invalid browser image status" }
    val status = requireNotNull(statusValue.intOrNull)
    val encodedValue = requireNotNull(json["bodyBase64"] as? JsonPrimitive)
    require(encodedValue.isString) { "Invalid browser image body" }
    val encoded = requireNotNull(encodedValue.contentOrNull)
    require(encoded.length <= ((maxBytes + 2) / 3) * 4) { "Browser image is too large" }
    val body = Base64.decode(encoded)
    require(body.size <= maxBytes && Base64.encode(body) == encoded) { "Invalid browser image encoding" }
    val typeValue = requireNotNull(json["contentType"] as? JsonPrimitive)
    require(typeValue.isString) { "Invalid browser image content type" }
    val type = requireNotNull(typeValue.contentOrNull)
    require(type in setOf("image/avif", "image/webp", "image/jpeg", "image/png", "application/octet-stream", "binary/octet-stream")) {
        "Browser image returned non-raster content"
    }
    return PluginHttpResponse(status, body, mapOf("Content-Type" to listOf(type))).also {
        require(it.imageBodyForDecoderOrNull() != null) { "Browser image returned no valid content" }
    }
}
