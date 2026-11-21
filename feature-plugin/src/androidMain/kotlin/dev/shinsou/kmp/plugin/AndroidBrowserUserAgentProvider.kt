package dev.shinsou.kmp.plugin

import android.content.Context
import android.webkit.WebSettings

/** Returns the UA used by Android System WebView on this exact device. */
public class AndroidBrowserUserAgentProvider(context: Context) : PluginUserAgentProvider {
    private val applicationContext = context.applicationContext

    @Volatile
    private var cached: String? = null

    override suspend fun userAgent(host: String): String = cached ?: synchronized(this) {
        cached ?: runCatching { WebSettings.getDefaultUserAgent(applicationContext) }
            .getOrNull()
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.also { cached = it }
            ?: System.getProperty("http.agent").orEmpty().trim().takeIf(String::isNotEmpty)
            ?: "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 (KHTML, like Gecko) Mobile Safari/537.36"
    }
}
