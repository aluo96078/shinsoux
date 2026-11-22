package dev.shinsou.kmp.plugin.network

internal actual fun inspectPlatformPluginNetwork(): PlatformPluginNetworkCapability =
    PlatformPluginNetworkCapability(
        hostResolverAvailable = true,
        pinnedTransportAvailable = true,
        detail = "iOS Network.framework connects only to validated literal addresses while " +
            "preserving the original hostname for TLS SNI, certificate validation, and HTTP Host",
    )
