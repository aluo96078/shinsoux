package dev.shinsou.kmp.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.endpoint
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding

actual fun createPlatformHttpClient(): HttpClient = HttpClient(CIO) {
    expectSuccess = false
    followRedirects = true
    // Sources such as ShuYue's relay explicitly request gzip. Decode the response in Ktor before
    // the synchronous JavaScript bridge turns it into text; otherwise XML/JSON parsers see gzip
    // bytes and silently return an empty catalogue.
    install(ContentEncoding) {
        gzip()
        deflate()
    }
    install(HttpTimeout) {
        requestTimeoutMillis = 45_000
        connectTimeoutMillis = 20_000
        socketTimeoutMillis = 45_000
    }
    engine {
        maxConnectionsCount = 32
        endpoint {
            maxConnectionsPerRoute = 8
            keepAliveTime = 5_000
            connectTimeout = 20_000
        }
    }
}
