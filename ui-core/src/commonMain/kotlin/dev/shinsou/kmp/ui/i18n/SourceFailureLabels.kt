package dev.shinsou.kmp.ui.i18n

import dev.shinsou.kmp.domain.model.sourceFailureMarker

private val sourceFailureKeys = mapOf(
    "SHINSOU_SOURCE_HTTP_CHALLENGE" to StringKey.THE_WEBSITE_REQUIRES_VERIFICATION_IF_WEB_VERIFICATION,
    "SHINSOU_SOURCE_HTTP_BLOCKED" to StringKey.THE_WEBSITE_REJECTED_THE_CONNECTION_AND_RETURNED,
    "SHINSOU_SOURCE_HTTP_FORBIDDEN" to StringKey.THE_WEBSITE_DENIED_ACCESS_HTTP_403_RETRY,
    "SHINSOU_SOURCE_HTTP_UNAVAILABLE" to StringKey.THE_SOURCE_WEBSITE_OR_NETWORK_REQUEST_IS,
    "SHINSOU_SOURCE_QUOTA_EXCEEDED" to StringKey.THE_SOURCE_S_DAILY_IMAGE_QUOTA_HAS,
)

public fun Throwable.localizedSourceFailure(strings: ShinsouStrings): String? =
    sourceFailureKeys[sourceFailureMarker()]?.let { strings.text(it) }

public fun String.localizedSourceFailure(strings: ShinsouStrings): String? =
    sourceFailureKeys[sourceFailureMarker(this)]?.let { strings.text(it) }
