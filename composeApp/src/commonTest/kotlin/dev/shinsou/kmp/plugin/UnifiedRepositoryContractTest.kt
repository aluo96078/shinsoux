package dev.shinsou.kmp.plugin

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class UnifiedRepositoryContractTest {
    @Test
    fun fetchIndexKeepsBothShinsouAndShuYueHalves() = runTest {
        val body = """
            {"format":"shinsou-unified-v1","shinsou":[
              {"id":"manga","name":"Manga","version":"1.0.0","versionCode":1,"lang":"zh",
               "scriptUrl":"manga.js","sources":[{"name":"Manga","lang":"zh","id":1,"baseUrl":"https://manga.example"}]}
            ],"shuyue":[
              {"id":"novel","name":"Novel","version":"1.0.0","versionCode":1,"lang":"zh",
               "scriptUrl":"novel.js","type":"novel",
               "sources":[{"id":"novel","name":"Novel","lang":"zh","baseUrl":"https://novel.example","type":"novel"}]}
            ]}
        """.trimIndent()
        val client = ExtensionRepositoryClient(
            HttpClient(MockEngine { respond(body, HttpStatusCode.OK) }),
            cacheToken = { 1L },
        )

        val index = client.fetchIndex("https://repo.example")
        val combined = assertIs<RepositoryIndex.Combined>(index)
        assertEquals(listOf("manga"), combined.plugins.map { it.id })
        assertEquals(listOf("novel"), combined.shuyue.map { it.id })
    }
}
