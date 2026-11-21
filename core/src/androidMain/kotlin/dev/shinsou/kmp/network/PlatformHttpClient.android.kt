package dev.shinsou.kmp.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import java.net.Proxy

actual fun createPlatformHttpClient(): HttpClient = HttpClient(OkHttp) {
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
        config {
            // Ktor owns redirect policy. Plugin traffic clones this client with redirects off so
            // cookies can be rebuilt safely for every hop.
            followRedirects(false)
            retryOnConnectionFailure(true)
            // The Android emulator used for local deployment can inherit a dead host proxy
            // (10.0.2.2:18080) even when Android's global proxy setting is empty.  Shinsou has
            // its own per-source/Cloudflare proxy routing, so bypass the ambient JVM proxy here.
            proxy(Proxy.NO_PROXY)
        }
    }
}
