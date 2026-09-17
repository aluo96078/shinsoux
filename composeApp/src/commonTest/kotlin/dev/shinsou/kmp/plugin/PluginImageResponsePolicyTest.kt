package dev.shinsou.kmp.plugin

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

class PluginImageResponsePolicyTest {
    private val imageBytes = byteArrayOf(1, 2, 3)

    @Test
    fun explicitDocumentResponsesAreRejectedBeforeDecoding() {
        listOf(
            "text/html",
            "application/xhtml+xml",
            "image/svg",
            "image/svg+xml",
            "IMAGE/SVG+XML; charset=utf-8",
            "image/vnd.example+xml",
        ).forEach { contentType ->
            assertNull(response(contentType = contentType).imageBodyForDecoderOrNull())
        }
    }

    @Test
    fun rasterTypeWithParametersIsAcceptedCaseInsensitively() {
        val response = response(
            contentType = "IMAGE/PNG; charset=binary",
            headerName = "content-type",
        )

        assertSame(imageBytes, response.imageBodyForDecoderOrNull())
    }

    @Test
    fun unspecifiedAndGenericBinaryTypesRemainCompatible() {
        assertSame(imageBytes, response(contentType = null).imageBodyForDecoderOrNull())
        assertSame(
            imageBytes,
            response(contentType = "application/octet-stream").imageBodyForDecoderOrNull(),
        )
        // Some reviewed image CDNs use this legacy generic MIME alias. The raster decoder
        // still validates the bytes; explicit HTML/SVG responses remain rejected above.
        assertSame(imageBytes, response(contentType = "binary/octet-stream").imageBodyForDecoderOrNull())
    }

    @Test
    fun emptyUnsuccessfulMalformedAndAmbiguousResponsesAreRejected() {
        assertNull(response(contentType = "image/png", status = 404).imageBodyForDecoderOrNull())
        assertNull(
            PluginHttpResponse(
                status = 200,
                body = byteArrayOf(),
                headers = mapOf("Content-Type" to listOf("image/png")),
            ).imageBodyForDecoderOrNull(),
        )
        assertNull(response(contentType = "image/png, text/html").imageBodyForDecoderOrNull())
        assertNull(response(contentType = "image png").imageBodyForDecoderOrNull())
        assertNull(
            PluginHttpResponse(
                status = 200,
                body = imageBytes,
                headers = mapOf("Content-Type" to listOf("image/png", "text/html")),
            ).imageBodyForDecoderOrNull(),
        )
        assertNull(
            PluginHttpResponse(
                status = 200,
                body = imageBytes,
                headers = mapOf(
                    "Content-Type" to listOf("image/png"),
                    "content-type" to listOf("text/html"),
                ),
            ).imageBodyForDecoderOrNull(),
        )
    }

    private fun response(
        contentType: String?,
        status: Int = 200,
        headerName: String = "Content-Type",
    ): PluginHttpResponse = PluginHttpResponse(
        status = status,
        body = imageBytes,
        headers = contentType?.let { mapOf(headerName to listOf(it)) }.orEmpty(),
    )
}
