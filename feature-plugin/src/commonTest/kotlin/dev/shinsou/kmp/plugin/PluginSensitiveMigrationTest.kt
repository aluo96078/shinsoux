package dev.shinsou.kmp.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PluginSensitiveMigrationTest {
    @Test
    fun migratesEverySensitiveValueAndPreservesAuthoritativeSecureCopies() {
        val plain = linkedMapOf(
            "source.1.credential.username" to "legacy-user",
            "source.1.cookie.jar" to "legacy-cookie",
            "tracking.token.1" to "legacy-token",
            "source.1.reader-mode" to "webtoon",
        )
        val secure = mutableMapOf("source.1.credential.username" to "keychain-user")

        val remaining = migrateLegacySensitivePluginValues(
            plainValues = plain,
            readSecure = secure::get,
            writeSecure = { key, value -> secure[key] = value },
        )

        assertEquals(mapOf("source.1.reader-mode" to "webtoon"), remaining)
        assertEquals("keychain-user", secure["source.1.credential.username"])
        assertEquals("legacy-cookie", secure["source.1.cookie.jar"])
        assertEquals("legacy-token", secure["tracking.token.1"])
    }

    @Test
    fun refusesToReturnAPlaintextRemovalUntilTheSecureWriteReadsBack() {
        val plain = mapOf("network.proxy.secret" to "api-key")

        assertFailsWith<IllegalStateException> {
            migrateLegacySensitivePluginValues(
                plainValues = plain,
                readSecure = { null },
                writeSecure = { _, _ -> },
            )
        }
        assertEquals("api-key", plain["network.proxy.secret"])
    }

    @Test
    fun partialSecureWritesAreIdempotentAndRetryKeepsExistingSecureValues() {
        val plain = linkedMapOf(
            "source.1.credential.username" to "user",
            "source.1.cookie.jar" to "cookie",
            "source.1.reader-mode" to "pager",
        )
        val secure = mutableMapOf<String, String>()

        assertFailsWith<IllegalStateException> {
            migrateLegacySensitivePluginValues(
                plainValues = plain,
                readSecure = secure::get,
                writeSecure = { key, value ->
                    if (key.contains("cookie")) error("Keychain unavailable")
                    secure[key] = value
                },
            )
        }
        assertEquals("user", secure["source.1.credential.username"])
        assertEquals(3, plain.size)

        val remaining = migrateLegacySensitivePluginValues(
            plainValues = plain,
            readSecure = secure::get,
            writeSecure = { key, value -> secure[key] = value },
        )

        assertEquals(mapOf("source.1.reader-mode" to "pager"), remaining)
        assertEquals("user", secure["source.1.credential.username"])
        assertEquals("cookie", secure["source.1.cookie.jar"])
    }
}
