package dev.shinsou.kmp.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.endpoint
import io.ktor.client.plugins.HttpTimeout

actual fun createPlatformHttpClient(): HttpClient = HttpClient(CIO) {
    expectSuccess = false
    followRedirects = true
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
