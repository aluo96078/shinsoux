package dev.shinsou.kmp.sync.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets

/** Dedicated sync client: redirects are forbidden so bearer/capability headers never cross origins. */
fun createSyncHttpClient(platformClient: HttpClient): HttpClient = platformClient.config {
    followRedirects = false
    install(WebSockets) {
        pingIntervalMillis = 25_000
        maxFrameSize = 512 * 1024L
    }
}
