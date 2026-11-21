import XCTest

final class SilentWavTests: XCTestCase {
    private var bytes: [UInt8] { Array(SilentWav.data) }

    private func littleEndian(_ range: Range<Int>) -> UInt64 {
        bytes[range].enumerated().reduce(0) { $0 | UInt64($1.element) << (8 * UInt64($1.offset)) }
    }

    private func ascii(_ range: Range<Int>) -> String? {
        String(bytes: bytes[range], encoding: .ascii)
    }

    func testHasTheRiffWaveHeader() {
        XCTAssertEqual(ascii(0..<4), "RIFF")
        XCTAssertEqual(ascii(8..<12), "WAVE")
        XCTAssertEqual(ascii(12..<16), "fmt ")
        XCTAssertEqual(ascii(36..<40), "data")
    }

    func testDescribesEightBitMonoAtEightKilohertz() {
        XCTAssertEqual(littleEndian(20..<22), 1, "PCM")
        XCTAssertEqual(littleEndian(22..<24), 1, "mono")
        XCTAssertEqual(littleEndian(24..<28), 8_000, "sample rate")
        XCTAssertEqual(littleEndian(28..<32), 8_000, "byte rate")
        XCTAssertEqual(littleEndian(32..<34), 1, "block align")
        XCTAssertEqual(littleEndian(34..<36), 8, "bits per sample")
    }

    func testSizesAreConsistentWithTheHundredMillisecondPayload() {
        XCTAssertEqual(SilentWav.dataSize, 800)
        XCTAssertEqual(bytes.count, 44 + 800)
        XCTAssertEqual(littleEndian(4..<8), UInt64(bytes.count - 8), "RIFF chunk size")
        XCTAssertEqual(littleEndian(40..<44), 800, "data chunk size")
    }

    func testPayloadIsUnsignedEightBitSilence() {
        XCTAssertTrue(bytes[44...].allSatisfy { $0 == 0x80 })
    }
}
