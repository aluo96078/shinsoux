package dev.shinsou.kmp.plugin

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReviewedImageConnectPolicyTest {
    private val authorization = "Basic opaque"

    @Test
    fun acceptsOnlyExactAuthenticatedAuthority() {
        assertValid(validHeader())
        assertValid(validHeader().replace("Host: i.motiezw.com:443", "Host: i.motiezw.com"))
        assertInvalid(validHeader().replace("i.motiezw.com:443", "example.com:443"))
        assertInvalid(validHeader().replace("Host: i.motiezw.com:443", "Host: i.motiezw.com:444"))
        assertInvalid(validHeader().replace("Proxy-Authorization: $authorization\r\n", ""))
        assertInvalid(validHeader().replace(authorization, "Basic wrong"))
        assertInvalid(validHeader() + "\r\nHost: i.motiezw.com:443")
        assertInvalid(validHeader() + "\r\nProxy-Authorization: $authorization")
    }

    @Test
    fun rejectsAmbiguousFramingAndHeaderSyntax() {
        assertInvalid(validHeader() + "\r\nContent-Length: 0")
        assertInvalid(validHeader() + "\r\nTransfer-Encoding: chunked")
        assertInvalid(validHeader() + "\r\n continuation")
        assertInvalid(validHeader() + "\r\nBad Header: value")
        assertInvalid(validHeader().replace("\r\n", "\n"))
        assertInvalid(validHeader() + "\u0000")
        assertInvalid(validHeader() + "\u0009")
        assertInvalid(validHeader() + "\u001f")
        assertInvalid(validHeader() + "\u007f")
        assertInvalid(validHeader() + "é")
        assertInvalid(validHeader().replace("Host:", "Host:\t"))
    }

    @Test
    fun rejectsOtherMethodsVersionsAndOversizeInput() {
        assertInvalid(validHeader().replace("CONNECT", "GET"))
        assertInvalid(validHeader().replace("HTTP/1.1", "HTTP/1.0"))
        assertInvalid("A".repeat(REVIEWED_IMAGE_CONNECT_MAX_HEADER_BYTES + 1))
    }

    private fun validHeader(): String =
        "CONNECT i.motiezw.com:443 HTTP/1.1\r\n" +
            "Host: i.motiezw.com:443\r\n" +
            "Proxy-Authorization: $authorization\r\n" +
            "User-Agent: WebKit"

    private fun assertValid(header: String) {
        assertTrue(isValidReviewedImageConnectHeader(header, authorization))
    }

    private fun assertInvalid(header: String) {
        assertFalse(isValidReviewedImageConnectHeader(header, authorization))
    }
}
