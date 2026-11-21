import XCTest

final class VolumeKeyLogicTests: XCTestCase {
    func testDirectionFollowsTheSignOfTheDelta() {
        XCTAssertEqual(VolumeKeyLogic.direction(delta: 0.0625), .up)
        XCTAssertEqual(VolumeKeyLogic.direction(delta: -0.0625), .down)
    }

    func testChangesWithinTheThresholdAreNotKeyPresses() {
        XCTAssertNil(VolumeKeyLogic.direction(delta: 0))
        XCTAssertNil(VolumeKeyLogic.direction(delta: VolumeKeyLogic.volumeThreshold))
        XCTAssertNil(VolumeKeyLogic.direction(delta: -VolumeKeyLogic.volumeThreshold))
        XCTAssertEqual(VolumeKeyLogic.direction(delta: VolumeKeyLogic.volumeThreshold * 2), .up)
    }

    func testVolumesNearEitherEndNeedTheSafeRange() {
        XCTAssertTrue(VolumeKeyLogic.needsSafeRange(0))
        XCTAssertTrue(VolumeKeyLogic.needsSafeRange(0.01))
        XCTAssertTrue(VolumeKeyLogic.needsSafeRange(0.99))
        XCTAssertTrue(VolumeKeyLogic.needsSafeRange(1))
        XCTAssertFalse(VolumeKeyLogic.needsSafeRange(VolumeKeyLogic.defaultVolume))
        XCTAssertFalse(VolumeKeyLogic.needsSafeRange(0.02))
    }

    func testDefaultVolumeLeavesRoomForBothDirections() {
        XCTAssertFalse(VolumeKeyLogic.needsSafeRange(VolumeKeyLogic.defaultVolume))
    }

    func testSettlingIsAMatterOfTheThreshold() {
        XCTAssertTrue(VolumeKeyLogic.hasSettled(actual: 0.5, target: 0.5))
        XCTAssertTrue(VolumeKeyLogic.hasSettled(actual: 0.5005, target: 0.5))
        XCTAssertFalse(VolumeKeyLogic.hasSettled(actual: 0.52, target: 0.5))
    }
}
