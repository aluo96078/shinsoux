package dev.shinsou.kmp.migration.shuyue

import dev.shinsou.kmp.plugin.PluginKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ShuYueMigrationSecretPayloadTest {
    @Test
    fun completeBatchIsPersistedAsOneSensitiveReplacement() = runTest {
        val store = RecordingKeyValueStore()
        val subject = KeyValueShuYueMigrationSecretStore(store)

        subject.replaceAtomically(secretBatch())

        assertTrue(subject.protectedAtRest)
        assertEquals(listOf(SHUYUE_MIGRATION_SECRET_BATCH_KEY), store.writeKeys)
        assertTrue(SHUYUE_MIGRATION_SECRET_BATCH_KEY.contains("secret"))
        val payload = Json.parseToJsonElement(requireNotNull(store.value)).jsonObject
        assertEquals(1, payload.getValue("schemaVersion").jsonPrimitive.content.toInt())
        assertEquals("credential-source", payload.getValue("credentials").jsonArray.single()
            .jsonObject.getValue("sourceId").jsonPrimitive.content)
        assertEquals("cookie-value", payload.getValue("cookies").jsonArray.single()
            .jsonObject.getValue("value").jsonPrimitive.content)
    }

    @Test
    fun failedSingleWriteDoesNotPublishAPartialBatch() = runTest {
        val store = RecordingKeyValueStore(value = "previous", failWrites = true)
        val subject = KeyValueShuYueMigrationSecretStore(store)

        assertFailsWith<IllegalStateException> { subject.replaceAtomically(secretBatch()) }

        assertEquals("previous", store.value)
        assertEquals(listOf(SHUYUE_MIGRATION_SECRET_BATCH_KEY), store.writeKeys)
    }

    @Test
    fun diagnosticStringsNeverContainSecretValues() {
        val batch = secretBatch()

        assertFalse(batch.toString().contains("credential-password"))
        assertFalse(batch.credentials.single().toString().contains("credential-password"))
        assertFalse(batch.cookies.single().toString().contains("cookie-value"))
    }

    private fun secretBatch(): ShuYueSecretWriteBatch = ShuYueSecretWriteBatch(
        credentials = listOf(
            ShuYueSecretCredential(
                sourceId = "credential-source",
                username = "credential-user",
                password = "credential-password",
                updatedAtEpochMillis = 100,
            ),
        ),
        cookies = listOf(
            ShuYueSecretCookie(
                sourceId = "cookie-source",
                name = "session",
                value = "cookie-value",
                domain = "example.com",
                path = "/",
                expiresAtEpochMillis = 200,
            ),
        ),
    )

    private class RecordingKeyValueStore(
        var value: String? = null,
        private val failWrites: Boolean = false,
    ) : PluginKeyValueStore {
        val writeKeys = mutableListOf<String>()

        override suspend fun getString(key: String): String? = value

        override suspend fun putString(key: String, value: String) {
            writeKeys += key
            if (failWrites) error("injected write failure")
            this.value = value
        }

        override suspend fun remove(key: String) {
            value = null
        }
    }
}
