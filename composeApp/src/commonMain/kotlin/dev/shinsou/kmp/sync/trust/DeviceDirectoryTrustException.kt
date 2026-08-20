package dev.shinsou.kmp.sync.trust

sealed class DeviceDirectoryTrustException(message: String) : IllegalStateException(message) {
    class Malformed(message: String) : DeviceDirectoryTrustException(message)
    class Rollback(message: String) : DeviceDirectoryTrustException(message)
    class Equivocation(message: String) : DeviceDirectoryTrustException(message)
    class KeySubstitution(message: String) : DeviceDirectoryTrustException(message)
    class UntrustedAttestation(message: String) : DeviceDirectoryTrustException(message)
    class FullDirectoryRequired(message: String) : DeviceDirectoryTrustException(message)
    class ConcurrentUpdate(message: String) : DeviceDirectoryTrustException(message)
}
