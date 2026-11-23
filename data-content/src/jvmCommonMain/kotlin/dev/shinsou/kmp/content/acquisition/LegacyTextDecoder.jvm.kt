package dev.shinsou.kmp.content.acquisition

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.IllegalCharsetNameException
import java.nio.charset.UnsupportedCharsetException

internal actual fun platformDecodeLegacyText(bytes: ByteArray, encoding: SourceTextEncoding): String? {
    val charset = try {
        Charset.forName(encoding.canonicalName)
    } catch (_: UnsupportedCharsetException) {
        null
    } catch (_: IllegalCharsetNameException) {
        null
    }
    return charset?.let { decodeStrictly(bytes, it) }
}

private fun decodeStrictly(bytes: ByteArray, charset: Charset): String? =
    try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }
