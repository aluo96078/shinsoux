package dev.shinsou.kmp.network

import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.network.ktor3.KtorNetworkFetcherFactory
import io.ktor.client.HttpClient

/** Installs a Coil loader that shares the app's configured Ktor client on JVM targets. */
@OptIn(ExperimentalCoilApi::class)
public fun installConfiguredImageLoader(httpClient: HttpClient) {
    SingletonImageLoader.setSafe { context ->
        ImageLoader.Builder(context)
            .components { add(KtorNetworkFetcherFactory(httpClient)) }
            .build()
    }
}
