package dev.shinsou.kmp.plugin

/** Closed vocabulary: remote exception text never becomes diagnostic output. */
internal fun iosPluginNetworkFailureCode(error: Throwable): String {
    val messages = generateSequence(error) { it.cause?.takeUnless { cause -> cause === it } }
        .take(8).mapNotNull { it.message }.toList()
    for (message in messages) {
        val network = Regex("Pinned plugin (connection|send|receive) failed \\((-?[0-9]{1,8})\\)")
            .matchEntire(message)
        if (network != null) return "${network.groupValues[1]}:${network.groupValues[2]}"
        when (message) {
            "Plugin network host did not resolve" -> return "dns-empty"
            "Plugin network DNS answer is local/private" -> return "dns-private"
            "Pinned iOS transport requires an identity-encoded response" -> return "response-compressed"
            "Plugin response content length is inconsistent" -> return "response-truncated"
            "Plugin response is too large" -> return "response-too-large"
            "Invalid plugin HTTP request target" -> return "request-target-invalid"
            "Invalid plugin HTTP status" -> return "response-status-invalid"
        }
    }
    return "host-operation-failed"
}
