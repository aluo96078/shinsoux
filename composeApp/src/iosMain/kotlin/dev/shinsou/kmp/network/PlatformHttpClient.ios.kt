package dev.shinsou.kmp.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding

actual fun createPlatformHttpClient(): HttpClient = HttpClient(Darwin) {
    expectSuccess = false
    followRedirects = true
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
        configureRequest {
            setAllowsCellularAccess(true)
            setAllowsExpensiveNetworkAccess(true)
            setAllowsConstrainedNetworkAccess(true)
        }
    }
}
