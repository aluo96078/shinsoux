package dev.shinsou.kmp

import platform.Foundation.NSCocoaErrorDomain
import platform.Foundation.NSPOSIXErrorDomain
import platform.Foundation.NSURLErrorDomain
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosOpenInPlaceDenialTest {
    @Test
    fun aRefusedReadIsADenial() {
        assertTrue(isOpenInPlaceDenial(NSCocoaErrorDomain, 257), "NSFileReadNoPermissionError")
        assertTrue(isOpenInPlaceDenial(NSPOSIXErrorDomain, 1), "EPERM")
        assertTrue(isOpenInPlaceDenial(NSPOSIXErrorDomain, 13), "EACCES")
    }

    @Test
    fun aFileThatIsNotThereOrCouldNotBeFetchedIsNotADenial() {
        // Remembering copies for any of these would cost every later import its size check.
        assertFalse(isOpenInPlaceDenial(NSCocoaErrorDomain, 260), "NSFileReadNoSuchFileError: not downloaded, or gone")
        assertFalse(isOpenInPlaceDenial(NSCocoaErrorDomain, 256), "NSFileReadUnknownError")
        assertFalse(isOpenInPlaceDenial(NSCocoaErrorDomain, 3072), "NSUserCancelledError")
        assertFalse(isOpenInPlaceDenial(NSCocoaErrorDomain, 513), "NSFileWriteNoPermissionError: the snapshot, not the original")
        assertFalse(isOpenInPlaceDenial(NSPOSIXErrorDomain, 2), "ENOENT")
        assertFalse(isOpenInPlaceDenial(NSURLErrorDomain, -1009), "offline")
        // Other domains reuse the numbers.
        assertFalse(isOpenInPlaceDenial(NSURLErrorDomain, 257))
        assertFalse(isOpenInPlaceDenial("NSFileProviderErrorDomain", -1000), "the provider needs a sign-in")
        assertFalse(isOpenInPlaceDenial(null, 257))
    }
}
