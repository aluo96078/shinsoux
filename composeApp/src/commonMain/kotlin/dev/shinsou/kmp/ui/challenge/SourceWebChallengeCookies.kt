package dev.shinsou.kmp.ui.challenge

import dev.shinsou.kmp.ui.SourceCookie
import dev.shinsou.kmp.ui.SourceWebChallengeRequest
import io.ktor.http.Url
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Clock

/** Browser integration available on the current target. */
internal enum class PlatformWebChallengeMode {
    Embedded,
    ExternalBrowserOnly,
}

internal expect val platformWebChallengeMode: PlatformWebChallengeMode

/**
 * Parses Android WebView's Cookie header. Since that API omits cookie attributes, imported
 * cookies are deliberately narrowed to the exact origin host and current source path.
 */
internal fun parseWebViewCookieHeader(
    header: String?,
    requestUrl: String,
    nowEpochMillis: Long = Clock.System.now().toEpochMilliseconds(),
): List<SourceCookie> {
    val origin = challengeOrigin(requestUrl) ?: return emptyList()
    if (header.isNullOrBlank()) return emptyList()
    val capturePath = origin.encodedPath.ifEmpty { "/" }.let { if (it.startsWith('/')) it else "/" }
    val cookies = header.split(';').mapNotNull { part ->
        val nameValue = part.trim()
        val separator = nameValue.indexOf('=')
        if (separator <= 0) return@mapNotNull null
        SourceCookie(
            name = nameValue.substring(0, separator).trim(),
            value = nameValue.substring(separator + 1).trim(),
            domain = origin.host.lowercase(),
            path = capturePath,
            secure = origin.protocol.name == "https",
            hostOnly = true,
        )
    }
    return normalizeWebChallengeCookies(requestUrl, cookies, nowEpochMillis)
}

/**
 * Rejects expired, malformed, unrelated-domain, or unrelated-path cookies before they enter the
 * source jar. Native cookie stores already enforce these rules; this is a second trust boundary.
 */
internal fun normalizeWebChallengeCookies(
    requestUrl: String,
    cookies: List<SourceCookie>,
    nowEpochMillis: Long = Clock.System.now().toEpochMilliseconds(),
): List<SourceCookie> {
    val origin = challengeOrigin(requestUrl) ?: return emptyList()
    val requestHost = origin.host.lowercase().trimEnd('.')
    val requestPath = origin.encodedPath.ifEmpty { "/" }
    val normalized = linkedMapOf<Triple<String, String, String>, SourceCookie>()
    cookies.forEach { cookie ->
        if (!isCookieToken(cookie.name) || !isSafeCookieValue(cookie.value)) return@forEach
        if (cookie.expiresAtEpochMillis?.let { it <= nowEpochMillis } == true) return@forEach
        if (cookie.secure && origin.protocol.name != "https") return@forEach

        val domain = cookie.domain.trim().trimStart('.').trimEnd('.').lowercase()
        if (domain.isEmpty()) return@forEach
        val domainMatches = if (cookie.hostOnly) {
            requestHost == domain
        } else {
            requestHost == domain || requestHost.endsWith(".$domain")
        }
        if (!domainMatches) return@forEach

        val path = cookie.path.ifBlank { "/" }
        // A challenge may redirect from /login.php to /. Capture against the original origin,
        // while retaining any same-origin cookie whose path is usable by at least one of those
        // locations. This keeps site-wide cf_clearance and member-session cookies without widening
        // their domain or path.
        val pathMatchesChallenge = cookiePathMatches(requestPath, path) || cookiePathMatches("/", path)
        if (!path.startsWith('/') || path.any(::isCookieControl) || !pathMatchesChallenge) {
            return@forEach
        }
        val safeCookie = cookie.copy(
            domain = if (cookie.hostOnly) domain else ".$domain",
            path = path,
        )
        normalized[Triple(safeCookie.domain, safeCookie.path, safeCookie.name)] = safeCookie
    }
    return normalized.values.toList()
}

/**
 * Seeds an isolated browser without reusing the cookie that this challenge is expected to mint.
 * In particular, an old browser-bound cf_clearance must not make a new session look verified.
 */
internal fun webChallengeSeedCookies(
    request: SourceWebChallengeRequest,
    nowEpochMillis: Long = Clock.System.now().toEpochMilliseconds(),
): List<SourceCookie> =
    normalizeWebChallengeCookies(request.url, request.cookies, nowEpochMillis).filterNot { cookie ->
        request.requiredCookieName?.let { required ->
            cookie.name.equals(required, ignoreCase = true)
        } == true
    }

/** Rejects header injection and unbounded native/browser values before persistence. */
internal fun normalizeWebChallengeUserAgent(value: String?): String? = value
    ?.trim()
    ?.takeIf { it.isNotEmpty() && it.length <= 512 && it.none(::isCookieControl) }

internal const val MAX_WEB_CHALLENGE_STORAGE_KEYS: Int = 8
internal const val MAX_WEB_CHALLENGE_STORAGE_VALUE_BYTES: Int = 16 * 1_024
internal const val MAX_WEB_CHALLENGE_STORAGE_TOTAL_BYTES: Int = 32 * 1_024

/** Validates a source-declared localStorage allowlist before any browser JavaScript is built. */
internal fun normalizeWebChallengeLocalStorageKeys(values: Iterable<String>): List<String> =
    values.asSequence()
        .map(String::trim)
        .filter(::isWebChallengeLocalStorageKey)
        .distinct()
        .take(MAX_WEB_CHALLENGE_STORAGE_KEYS)
        .toList()

/** Keeps only explicitly allowlisted, bounded values returned by the same-origin browser. */
internal fun normalizeWebChallengeLocalStorage(
    values: Map<String, String>,
    allowlist: Iterable<String>,
): Map<String, String> {
    val allowed = normalizeWebChallengeLocalStorageKeys(allowlist).toSet()
    if (allowed.isEmpty()) return emptyMap()
    var totalBytes = 0
    val normalized = linkedMapOf<String, String>()
    values.forEach { (rawKey, value) ->
        val key = rawKey.trim()
        if (key !in allowed || value.any(::isCookieControl)) return@forEach
        val bytes = value.encodeToByteArray().size
        if (bytes > MAX_WEB_CHALLENGE_STORAGE_VALUE_BYTES ||
            totalBytes + bytes > MAX_WEB_CHALLENGE_STORAGE_TOTAL_BYTES
        ) {
            return@forEach
        }
        totalBytes += bytes
        normalized[key] = value
    }
    return normalized
}

/** JavaScript used only inside the isolated same-origin WebView to read the declared allowlist. */
internal fun webChallengeLocalStorageCaptureScript(request: SourceWebChallengeRequest): String {
    val origin = challengeOrigin(request.url)?.originString().orEmpty()
    val keys = normalizeWebChallengeLocalStorageKeys(request.localStorageKeys)
    val encodedOrigin = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(origin))
    val encodedKeys = buildJsonArray { keys.forEach { add(JsonPrimitive(it)) } }.toString()
    return """
        (() => {
          const expectedOrigin = $encodedOrigin;
          const keys = $encodedKeys;
          if (!expectedOrigin || location.origin !== expectedOrigin) {
            return JSON.stringify({ ok: false, error: "origin" });
          }
          const values = {};
          try {
            for (const key of keys) {
              const value = localStorage.getItem(key);
              if (value !== null) values[key] = String(value);
            }
          } catch (_) {
            return JSON.stringify({ ok: false, error: "storage" });
          }
          return JSON.stringify({ ok: true, values });
        })()
    """.trimIndent()
}

internal data class WebChallengeLocalStorageCapture(
    val values: Map<String, String> = emptyMap(),
    val error: String? = null,
)

/** Decodes the JSON string returned by [webChallengeLocalStorageCaptureScript]. */
internal fun decodeWebChallengeLocalStorageCapture(
    encoded: String?,
    allowlist: Iterable<String>,
): WebChallengeLocalStorageCapture {
    val payload = encoded
        ?.let { raw ->
            runCatching {
                when (val first = Json.parseToJsonElement(raw)) {
                    is JsonObject -> first
                    is JsonPrimitive -> Json.parseToJsonElement(first.content).jsonObject
                    else -> null
                }
            }.getOrNull()
        }
        ?: return WebChallengeLocalStorageCapture(error = "The browser session data could not be read.")
    if (payload["ok"]?.jsonPrimitive?.contentOrNull != "true") {
        val reason = when (payload["error"]?.jsonPrimitive?.contentOrNull) {
            "origin" -> "The browser left the source origin. Return to the source website and try again."
            "storage" -> "The website blocked access to its browser session data."
            else -> "The browser session data could not be read."
        }
        return WebChallengeLocalStorageCapture(error = reason)
    }
    val values = payload["values"] as? JsonObject ?: JsonObject(emptyMap())
    val decoded = values.mapNotNull { (key, value) ->
        value.jsonPrimitive.contentOrNull?.let { key to it }
    }.toMap()
    return WebChallengeLocalStorageCapture(
        values = normalizeWebChallengeLocalStorage(decoded, allowlist),
    )
}

/**
 * Installs a same-origin form watcher. It fills React-controlled inputs, opens a conventional
 * login dialog when needed, and submits at most once for this isolated browser document.
 */
internal fun automaticWebChallengeLoginScript(request: SourceWebChallengeRequest): String? {
    val username = request.username?.takeIf(String::isNotBlank) ?: return null
    val password = request.password?.takeIf(String::isNotEmpty) ?: return null
    val origin = challengeOrigin(request.url)?.originString() ?: return null
    val encodedOrigin = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(origin))
    val encodedUsername = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(username))
    val encodedPassword = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(password))
    return """
        (() => {
          const expectedOrigin = $encodedOrigin;
          const suppliedUsername = $encodedUsername;
          const suppliedPassword = $encodedPassword;
          if (location.origin !== expectedOrigin || !suppliedUsername.trim() || !suppliedPassword) {
            return "blocked";
          }
          const stateKey = "__shinsouAutomaticLoginState";
          const submittedKey = "__shinsouAutomaticLoginSubmitted";
          if (sessionStorage.getItem(submittedKey) === "1") return "already-submitted";
          const visible = (element) => !!element && element.getClientRects().length > 0;
          const fill = (input, value) => {
            const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")?.set;
            if (setter) setter.call(input, value); else input.value = value;
            input.dispatchEvent(new Event("input", { bubbles: true, composed: true }));
            input.dispatchEvent(new Event("change", { bubbles: true }));
          };
          const submit = () => {
            const passwordInput = Array.from(document.querySelectorAll('input[type="password"]'))
              .find((input) => visible(input) && !input.disabled && !input.readOnly);
            if (!passwordInput) return false;
            const form = passwordInput.form || passwordInput.closest("form");
            if (!form) return false;
            const action = new URL(form.getAttribute("action") || location.href, location.href);
            if (!/^https?:$/.test(action.protocol) || action.origin !== location.origin) return false;
            const selectors = [
              'input[autocomplete~="username" i]', 'input[name="username" i]',
              'input[name="email" i]', 'input[type="email"]', 'input[name*="user" i]',
              'input[name*="account" i]', 'input[id*="user" i]', 'input[id*="email" i]',
              'input[type="text"]'
            ];
            let usernameInput = null;
            for (const selector of selectors) {
              const candidate = Array.from(form.querySelectorAll(selector)).find((input) =>
                input !== passwordInput && visible(input) && !input.disabled && !input.readOnly &&
                String(input.type).toLowerCase() !== "hidden");
              if (candidate) { usernameInput = candidate; break; }
            }
            if (!usernameInput) return false;
            fill(usernameInput, suppliedUsername);
            fill(passwordInput, suppliedPassword);
            sessionStorage.setItem(submittedKey, "1");
            const submitter = Array.from(form.querySelectorAll('button, input[type="submit"], input[type="image"]'))
              .find((element) => visible(element) && !element.disabled &&
                (String(element.type).toLowerCase() === "submit" || element.tagName === "BUTTON"));
            if (typeof form.requestSubmit === "function") form.requestSubmit(submitter || undefined);
            else if (submitter) submitter.click();
            else HTMLFormElement.prototype.submit.call(form);
            return true;
          };
          if (submit()) return "submitted";
          if (!window[stateKey]) {
            const observer = new MutationObserver(() => {
              if (submit()) observer.disconnect();
            });
            observer.observe(document.documentElement, { childList: true, subtree: true });
            window[stateKey] = observer;
            const labels = new Set(["login", "log in", "sign in", "登入", "登录", "會員登入", "会员登录"]);
            const opener = Array.from(document.querySelectorAll('button, a, [role="button"]')).find((element) => {
              if (!visible(element) || element.disabled) return false;
              const label = String(element.getAttribute("aria-label") || element.getAttribute("title") ||
                element.textContent || "").trim().toLowerCase();
              if (!labels.has(label)) return false;
              if (element.tagName === "A" && element.href) {
                try { if (new URL(element.href, location.href).origin !== location.origin) return false; } catch (_) { return false; }
              }
              return true;
            });
            if (opener) opener.click();
          }
          return "watching";
        })()
    """.trimIndent()
}

internal fun cookiePathMatches(requestPath: String, cookiePath: String): Boolean =
    requestPath == cookiePath ||
        requestPath.startsWith(cookiePath) &&
        (cookiePath.endsWith('/') || requestPath.getOrNull(cookiePath.length) == '/')

/** Deterministic Set-Cookie value used when seeding an isolated platform WebView jar. */
internal fun webChallengeSetCookieValue(
    cookie: SourceCookie,
    nowEpochMillis: Long = Clock.System.now().toEpochMilliseconds(),
): String = buildString {
    append(cookie.name)
    append('=')
    append(cookie.value)
    append("; Path=")
    append(cookie.path.ifBlank { "/" })
    if (!cookie.hostOnly) {
        append("; Domain=")
        append(cookie.domain)
    }
    cookie.expiresAtEpochMillis?.let { expires ->
        append("; Max-Age=")
        append(((expires - nowEpochMillis) / 1_000L).coerceAtLeast(0L))
    }
    if (cookie.secure) append("; Secure")
    if (cookie.httpOnly) append("; HttpOnly")
    append("; SameSite=Lax")
}

private fun challengeOrigin(value: String): Url? = runCatching { Url(value.trim()) }
    .getOrNull()
    ?.takeIf { it.protocol.name in setOf("http", "https") && it.host.isNotBlank() }

private fun Url.originString(): String = buildString {
    append(protocol.name)
    append("://")
    append(host)
    val defaultPort = if (protocol.name == "https") 443 else 80
    if (port != defaultPort) {
        append(':')
        append(port)
    }
}

private fun isWebChallengeLocalStorageKey(value: String): Boolean =
    value.length in 1..64 && value.all { it.isLetterOrDigit() || it in "._-" }

private fun isCookieToken(value: String): Boolean = value.isNotEmpty() && value.all { character ->
    character.isLetterOrDigit() || character in "!#$%&'*+-.^_`|~"
}

private fun isSafeCookieValue(value: String): Boolean =
    value.none { isCookieControl(it) || it == ';' }

private fun isCookieControl(value: Char): Boolean = value.code in 0..31 || value.code == 127
