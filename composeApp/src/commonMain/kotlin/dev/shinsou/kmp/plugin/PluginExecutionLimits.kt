package dev.shinsou.kmp.plugin

/**
 * Host-owned limits for one in-process JavaScript source.
 *
 * These values are deliberately absent from repository metadata: an extension may request
 * capabilities, but it must never be able to raise its own resource limits. Platforms may inject
 * tighter values for tests or constrained devices through [ScriptPluginEnvironment].
 */
public data class PluginExecutionLimits(
    val initializationWallTimeMillis: Long = 10_000,
    val invocationWallTimeMillis: Long = 30_000,
    val initializationInstructionCount: Long = 50_000_000,
    val invocationInstructionCount: Long = 100_000_000,
    val instructionObserverThreshold: Int = 10_000,
    val maximumInterpreterStackDepth: Int = 1_024,
    val maxBridgeCallsPerInvocation: Int = 4_096,
    val maxLogEntries: Int = 256,
    val maxLogBytes: Int = 256 * 1_024,
    val maxLogEntryBytes: Int = 16 * 1_024,
    val maxDomInputBytes: Int = 4 * 1_024 * 1_024,
    val maxDomHandles: Int = 4_096,
    /** Entire parsed trees include text nodes; selector handles have a separate, smaller budget. */
    val maxDomNodes: Int = 65_536,
    val maxDomDepth: Int = 256,
    val maxSelectorChars: Int = 4_096,
    val maxParsedElements: Int = 4_096,
    val maxBridgeMethodBytes: Int = 256,
    val maxBridgeArgumentBytes: Int = 4 * 1_024 * 1_024,
    val maxBridgeResultBytes: Int = 4 * 1_024 * 1_024,
    val maxInvocationInputBytes: Int = 1 * 1_024 * 1_024,
    val maxResultBytes: Int = 4 * 1_024 * 1_024,
    val maxResultStringBytes: Int = 1 * 1_024 * 1_024,
    val maxResultArrayElements: Int = 4_096,
    val maxResultObjectEntries: Int = 4_096,
    val maxResultTotalElements: Int = 16_384,
    val maxResultDepth: Int = 32,
) {
    init {
        require(initializationWallTimeMillis > 0)
        require(invocationWallTimeMillis > 0)
        require(initializationInstructionCount > 0)
        require(invocationInstructionCount > 0)
        require(instructionObserverThreshold > 0)
        require(maximumInterpreterStackDepth > 0)
        require(maxBridgeCallsPerInvocation > 0)
        require(maxLogEntries > 0)
        require(maxLogBytes > 0)
        require(maxLogEntryBytes in 1..maxLogBytes)
        require(maxDomInputBytes > 0)
        require(maxDomHandles > 0)
        require(maxDomNodes > 0)
        require(maxDomDepth > 0)
        require(maxSelectorChars > 0)
        require(maxParsedElements > 0)
        require(maxBridgeMethodBytes > 0)
        require(maxBridgeArgumentBytes > 0)
        require(maxBridgeResultBytes > 0)
        require(maxInvocationInputBytes > 0)
        require(maxResultBytes > 0)
        require(maxResultStringBytes in 1..maxResultBytes)
        require(maxResultArrayElements > 0)
        require(maxResultObjectEntries > 0)
        require(maxResultTotalElements >= maxOf(maxResultArrayElements, maxResultObjectEntries))
        require(maxResultDepth > 0)
    }
}

/**
 * Counts UTF-8 bytes without first allocating an attacker-sized byte array. Returns `null` as
 * soon as [maximum] is exceeded. Unpaired UTF-16 surrogates are counted as the three-byte Unicode
 * replacement character, matching Kotlin's UTF-8 encoder.
 */
internal fun pluginUtf8ByteCountAtMost(value: String, maximum: Int): Int? {
    var bytes = 0
    var index = 0
    while (index < value.length) {
        val char = value[index]
        val width = when {
            char.code < 0x80 -> 1
            char.code < 0x800 -> 2
            char.isHighSurrogate() && index + 1 < value.length && value[index + 1].isLowSurrogate() -> {
                index += 1
                4
            }
            char.isSurrogate() -> 3
            else -> 3
        }
        if (bytes > maximum - width) return null
        bytes += width
        index += 1
    }
    return bytes
}

/** Rejects oversized JSON arrays before a serializer allocates their element lists. */
internal fun pluginJsonArrayCountsAtMost(
    value: String,
    maximum: Int,
    maximumDepth: Int = 64,
): Boolean {
    require(maximum >= 0)
    require(maximumDepth > 0)
    var inString = false
    var escaped = false
    val containers = mutableListOf<Char>()
    val arrayElementCounts = mutableListOf<Int>()
    val arrayHasValue = mutableListOf<Boolean>()

    fun markArrayValue() {
        if (containers.lastOrNull() == '[') arrayHasValue[arrayHasValue.lastIndex] = true
    }

    value.forEach { character ->
        if (inString) {
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == '"' -> inString = false
            }
            return@forEach
        }
        when (character) {
            '"' -> {
                markArrayValue()
                inString = true
            }
            '[', '{' -> {
                markArrayValue()
                if (containers.size >= maximumDepth) return false
                containers += character
                arrayElementCounts += 0
                arrayHasValue += false
            }
            ']' -> {
                if (containers.lastOrNull() != '[') return false
                val last = containers.lastIndex
                val count = arrayElementCounts[last] + if (arrayHasValue[last]) 1 else 0
                if (count > maximum) return false
                containers.removeAt(last)
                arrayElementCounts.removeAt(last)
                arrayHasValue.removeAt(last)
            }
            '}' -> {
                if (containers.lastOrNull() != '{') return false
                val last = containers.lastIndex
                containers.removeAt(last)
                arrayElementCounts.removeAt(last)
                arrayHasValue.removeAt(last)
            }
            ',' -> if (containers.lastOrNull() == '[') {
                val last = containers.lastIndex
                if (!arrayHasValue[last]) return false
                arrayElementCounts[last] += 1
                if (arrayElementCounts[last] >= maximum) return false
                arrayHasValue[last] = false
            }
            else -> if (!character.isWhitespace()) markArrayValue()
        }
    }
    return !inString && containers.isEmpty()
}

/** Stable error used when a plugin exceeds a host-owned resource boundary. */
public class PluginResourceLimitException(message: String) : IllegalStateException(message)

internal fun boundedPluginLogMessage(message: String, maxBytes: Int): String {
    if (pluginUtf8ByteCountAtMost(message, maxBytes) != null) return message

    // Binary-search a UTF-16 prefix whose UTF-8 encoding fits. Avoid ending on a leading
    // surrogate so a truncated diagnostic remains valid text on every platform.
    var low = 0
    var high = message.length
    while (low < high) {
        val candidate = (low + high + 1) / 2
        if (message.substring(0, candidate).encodeToByteArray().size <= maxBytes) low = candidate
        else high = candidate - 1
    }
    var end = low
    if (end > 0 && end < message.length && message[end - 1].isHighSurrogate()) end -= 1
    return message.substring(0, end)
}
