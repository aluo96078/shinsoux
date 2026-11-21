import Foundation

/// The pure decisions behind the reader's hardware volume keys (no UIKit, so unit-testable).
enum VolumeKeyLogic {
    /// The volume the system is put back to after a press, and the midpoint used when the real
    /// volume is too close to either end for both directions to register.
    static let defaultVolume: Float = 0.5
    /// The smallest change of the output volume that counts as a key press.
    static let volumeThreshold: Float = 0.001
    /// Volumes at or below / at or above these have no room left for one direction.
    static let lowerSafeBound: Float = 0.01
    static let upperSafeBound: Float = 0.99

    enum Direction: Equatable {
        case up
        case down
    }

    /// The key a change of the output volume stands for; nil when it is too small to be a press.
    static func direction(delta: Float) -> Direction? {
        guard abs(delta) > volumeThreshold else { return nil }
        return delta > 0 ? .up : .down
    }

    /// Whether [volume] is so close to 0 or 1 that the next press in one direction would not change it.
    static func needsSafeRange(_ volume: Float) -> Bool {
        volume <= lowerSafeBound || volume >= upperSafeBound
    }

    /// Whether the system volume has reached the [target] a reset aimed for.
    static func hasSettled(actual: Float, target: Float) -> Bool {
        abs(actual - target) <= volumeThreshold
    }
}
