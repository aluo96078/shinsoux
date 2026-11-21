package dev.shinsou.kmp.network

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.network.ktor3.KtorNetworkFetcherFactory
import io.ktor.client.HttpClient

/** Installs a Coil loader that shares the app's configured Ktor client on every platform. */
@OptIn(ExperimentalCoilApi::class)
public fun installConfiguredImageLoader(
    httpClient: HttpClient,
    configureComponents: coil3.ComponentRegistry.Builder.() -> Unit = {},
) {
    SingletonImageLoader.setSafe { context ->
        createConfiguredImageLoader(context, httpClient, configureComponents)
    }
}

/** Builds the same loader as [installConfiguredImageLoader], exposed for deterministic tests. */
@OptIn(ExperimentalCoilApi::class)
internal fun createConfiguredImageLoader(
    context: PlatformContext,
    httpClient: HttpClient,
    configureComponents: coil3.ComponentRegistry.Builder.() -> Unit = {},
): ImageLoader = ImageLoader.Builder(context)
    .components {
        add(KtorNetworkFetcherFactory(httpClient))
        configureComponents()
    }
    .build()
