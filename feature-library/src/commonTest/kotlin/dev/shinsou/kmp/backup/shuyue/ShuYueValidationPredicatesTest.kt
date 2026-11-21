package dev.shinsou.kmp.backup.shuyue

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pins the accept/reject decisions of the untrusted-field predicates in the V1 validator. */
class ShuYueValidationPredicatesTest {
    private fun codes(body: String): Set<String> =
        ShuYueBackupV1Inspector.inspect("""{"version":1,"createdAt":1,$body}""")
            .preview.issues.map { it.code }.toSet()

    private fun cookie(domain: String, path: String = "/", name: String = "session", value: String = "v") =
        """"pluginCookies":[{"sourceId":"s","name":"$name","value":"$value","domain":"$domain","path":"$path"}]"""

    @Test
    fun cookieDomainsAreCheckedLabelByLabel() {
        val accepted = listOf("example.com", ".example.com", "a-b.example.com")
        val rejected = listOf("", "a@b.com", "a:80.com", "a/b.com", "-a.com", "a-.com", "a..com", "a b.com", "example.com.")
        accepted.forEach {
            assertFalse(ShuYueMigrationIssueCode.INVALID_COOKIE in codes(cookie(it)), "domain $it")
        }
        rejected.forEach {
            assertTrue(ShuYueMigrationIssueCode.INVALID_COOKIE in codes(cookie(it)), "domain $it")
        }
    }

    @Test
    fun cookiePathAndValueAreChecked() {
        assertFalse(ShuYueMigrationIssueCode.INVALID_COOKIE in codes(cookie("example.com", path = "/a")))
        assertTrue(ShuYueMigrationIssueCode.INVALID_COOKIE in codes(cookie("example.com", path = "a")))
        assertTrue(ShuYueMigrationIssueCode.INVALID_COOKIE in codes(cookie("example.com", path = "/a;b")))
        assertTrue(ShuYueMigrationIssueCode.INVALID_COOKIE in codes(cookie("example.com", value = "a\\nb")))
        assertTrue(ShuYueMigrationIssueCode.INVALID_COOKIE in codes(cookie("example.com", name = "a=b")))
    }

    @Test
    fun repositoryUrlsRejectUnsafeAuthorities() {
        fun repo(url: String) =
            """"selectedPluginRepositoryUrl":"$url","pluginRepositories":[{"baseUrl":"$url","manifest":{"meta":{"name":"n"}},"lastLoadedAt":1}]"""
        val accepted = listOf("https://example.com/x", "http://example.com:8080", "https://[::1]:80/x")
        val rejected = listOf("ftp://example.com", "https://", "https://u@example.com", "https://a b.com", "https://-a.com", "https://a.com:80x")
        accepted.forEach {
            assertFalse(ShuYueMigrationIssueCode.INVALID_REPOSITORY_URL in codes(repo(it)), "url $it")
        }
        rejected.forEach {
            assertTrue(ShuYueMigrationIssueCode.INVALID_REPOSITORY_URL in codes(repo(it)), "url $it")
        }
    }

    @Test
    fun preferencesAndReaderSettingsAreChecked() {
        fun pref(key: String, value: String) = """"pluginPreferences":{"$key":"$value"}"""
        assertFalse(ShuYueMigrationIssueCode.INVALID_PREFERENCE in codes(pref("s\\u0000k", "v")))
        assertTrue(ShuYueMigrationIssueCode.INVALID_PREFERENCE in codes(pref("nosep", "v")))
        assertTrue(ShuYueMigrationIssueCode.INVALID_PREFERENCE in codes(pref("s\\u0000k\\u0000x", "v")))
        assertTrue(ShuYueMigrationIssueCode.INVALID_PREFERENCE in codes(pref("s\\u0000k", "v\\u0001")))
        assertTrue(ShuYueMigrationIssueCode.INVALID_PREFERENCE in codes(pref("\\u0000k", "v")))

        assertTrue(ShuYueMigrationIssueCode.INVALID_READER_SETTINGS in codes(""""readerSettings":{"fontSizeSp":0}"""))
        assertTrue(ShuYueMigrationIssueCode.INVALID_READER_SETTINGS in codes(""""readerSettings":{"lineHeightPercent":2000}"""))
        assertTrue(ShuYueMigrationIssueCode.INVALID_READER_SETTINGS in codes(""""readerSettings":{"pageChars":0}"""))
        assertFalse(ShuYueMigrationIssueCode.INVALID_READER_SETTINGS in codes(""""readerSettings":{}"""))
    }
}
