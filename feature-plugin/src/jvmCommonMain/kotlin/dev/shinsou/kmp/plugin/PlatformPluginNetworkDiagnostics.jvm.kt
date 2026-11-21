package dev.shinsou.kmp.plugin

public actual fun inspectPlatformPluginNetwork(): PlatformPluginNetworkCapability =
    PlatformPluginNetworkCapability(
        hostResolverAvailable = true,
        pinnedTransportAvailable = true,
        detail = "OkHttp pins validated plugin DNS answers with a request-local Dns view while " +
            "retaining the URL hostname for HTTP Host and TLS SNI",
    )
