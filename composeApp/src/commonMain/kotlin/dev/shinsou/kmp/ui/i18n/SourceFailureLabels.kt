package dev.shinsou.kmp.ui.i18n

import dev.shinsou.kmp.plugin.sourceFailureMarker

private val sourceFailureKeys = mapOf(
    "SHINSOU_SOURCE_HTTP_CHALLENGE" to "The website requires verification. If Web verification is available in source settings, complete it and retry; otherwise try again later.",
    "SHINSOU_SOURCE_HTTP_BLOCKED" to "The website rejected the connection and returned no content. Try again later or contact the site administrator.",
    "SHINSOU_SOURCE_HTTP_FORBIDDEN" to "The website denied access (HTTP 403). Retry later or check whether the site is accessible.",
    "SHINSOU_SOURCE_HTTP_UNAVAILABLE" to "The source website or network request is currently unavailable. Try again later.",
    "SHINSOU_SOURCE_QUOTA_EXCEEDED" to "The source's daily image quota has been reached. Try again after the quota resets.",
)

fun Throwable.localizedSourceFailure(strings: ShinsouStrings): String? =
    sourceFailureKeys[sourceFailureMarker()]?.let { strings.text(it) }

fun String.localizedSourceFailure(strings: ShinsouStrings): String? =
    sourceFailureKeys[sourceFailureMarker(this)]?.let { strings.text(it) }


internal val TraditionalSourceFailureTranslations = mapOf(
    "The source's daily image quota has been reached. Try again after the quota resets." to "來源的每日圖片額度已用完。請等待額度重置後再試。",
    "The website requires verification. If Web verification is available in source settings, complete it and retry; otherwise try again later." to "網站要求驗證。若來源設定提供 Web 驗證，請完成後重試；若沒有此選項，請稍後再試。",
    "The website rejected the connection and returned no content. Try again later or contact the site administrator." to "網站拒絕連線且未傳回內容。請稍後再試，或聯絡網站管理員。",
    "The website denied access (HTTP 403). Retry later or check whether the site is accessible." to "網站拒絕存取（HTTP 403）。請稍後再試，或確認網站是否可用。",
    "The source website or network request is currently unavailable. Try again later." to "來源網站或網路請求目前無法使用。請稍後再試。",
)

internal val SimplifiedSourceFailureTranslations = mapOf(
    "The source's daily image quota has been reached. Try again after the quota resets." to "来源的每日图片额度已用完。请等待额度重置后再试。",
    "The website requires verification. If Web verification is available in source settings, complete it and retry; otherwise try again later." to "网站要求验证。若来源设置提供 Web 验证，请完成后重试；若没有此选项，请稍后再试。",
    "The website rejected the connection and returned no content. Try again later or contact the site administrator." to "网站拒绝连接且未返回内容。请稍后重试，或联系网站管理员。",
    "The website denied access (HTTP 403). Retry later or check whether the site is accessible." to "网站拒绝访问（HTTP 403）。请稍后重试，或确认网站是否可用。",
    "The source website or network request is currently unavailable. Try again later." to "来源网站或网络请求当前不可用。请稍后重试。",
)
