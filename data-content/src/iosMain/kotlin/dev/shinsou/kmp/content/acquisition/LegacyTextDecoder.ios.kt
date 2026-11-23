package dev.shinsou.kmp.content.acquisition

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFStringConvertEncodingToNSStringEncoding
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.dataWithBytesNoCopy

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal actual fun platformDecodeLegacyText(bytes: ByteArray, encoding: SourceTextEncoding): String? {
    val cfEncoding: UInt? = when (encoding) {
        SourceTextEncoding.GB18030 -> CF_STRING_ENCODING_GB_18030_2000
        SourceTextEncoding.BIG5 -> CF_STRING_ENCODING_BIG5
        else -> null
    }
    return when {
        cfEncoding == null -> null
        bytes.isEmpty() -> ""
        else -> decodeWith(bytes, cfEncoding)
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun decodeWith(bytes: ByteArray, cfEncoding: UInt): String? =
    bytes.usePinned { pinned ->
        // No copy: the data only lives while the array is pinned, and NSString copies what it decodes.
        val data = NSData.dataWithBytesNoCopy(
            bytes = pinned.addressOf(0),
            length = bytes.size.toULong(),
            freeWhenDone = false,
        )
        NSString.create(data = data, encoding = CFStringConvertEncodingToNSStringEncoding(cfEncoding))
            ?.toString()
    }

/** kCFStringEncodingGB_18030_2000 and kCFStringEncodingBig5 from CFStringEncodingExt.h. */
private const val CF_STRING_ENCODING_GB_18030_2000: UInt = 0x0632u
private const val CF_STRING_ENCODING_BIG5: UInt = 0x0A03u
