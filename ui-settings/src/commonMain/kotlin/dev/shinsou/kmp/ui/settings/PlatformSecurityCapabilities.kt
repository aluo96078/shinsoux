package dev.shinsou.kmp.ui.settings

// 0008 §7-5: from AppServices.kt; the settings show them, the platform hosts supply them.
data class SecurityFeatureCapability(
    val available: Boolean,
    val unavailableReason: String? = null,
) {
    init {
        require(available || !unavailableReason.isNullOrBlank()) {
            "An unavailable security capability must explain why it is unavailable."
        }
    }

    companion object {
        val Available = SecurityFeatureCapability(available = true)

        fun unavailable(reason: String) = SecurityFeatureCapability(
            available = false,
            unavailableReason = reason,
        )
    }
}

data class PlatformSecurityCapabilities(
    val appLock: SecurityFeatureCapability,
    val secureScreen: SecurityFeatureCapability,
) {
    companion object {
        val Available = PlatformSecurityCapabilities(
            appLock = SecurityFeatureCapability.Available,
            secureScreen = SecurityFeatureCapability.Available,
        )

        val Unavailable = PlatformSecurityCapabilities(
            appLock = SecurityFeatureCapability.unavailable("Device authentication is unavailable on this platform."),
            secureScreen = SecurityFeatureCapability.unavailable("Secure-screen protection is unavailable on this platform."),
        )
    }
}

/**
 * Mobile hosts can always enforce their secure-screen implementation, but app lock is safe to
 * advertise only while the device can actually perform owner authentication. This value is a
 * runtime capability and must never be copied into the portable snapshot.
 */
public fun mobileSecurityCapabilities(
    deviceOwnerAuthenticationAvailable: Boolean,
    unavailableReason: String = "Set up a device passcode, PIN, password, or biometric authentication to use app lock.",
): PlatformSecurityCapabilities = PlatformSecurityCapabilities(
    appLock = if (deviceOwnerAuthenticationAvailable) {
        SecurityFeatureCapability.Available
    } else {
        SecurityFeatureCapability.unavailable(unavailableReason)
    },
    secureScreen = SecurityFeatureCapability.Available,
)
