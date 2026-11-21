import Foundation

/// Exact silent PCM format used by the original app: 100 ms, 8 kHz, unsigned 8-bit mono.
/// Playing it keeps the audio session active so the volume buttons report changes.
enum SilentWav {
    static let sampleRate: UInt32 = 8_000
    static let dataSize: UInt32 = sampleRate / 10

    static let data: Data = {
        let channelCount: UInt16 = 1
        let bitsPerSample: UInt16 = 8
        let byteRate = sampleRate
        let blockAlign: UInt16 = 1
        var data = Data()

        func appendAscii(_ value: String) {
            data.append(Data(value.utf8))
        }

        func appendLittleEndian<T: FixedWidthInteger>(_ value: T) {
            var littleEndian = value.littleEndian
            Swift.withUnsafeBytes(of: &littleEndian) { bytes in
                data.append(contentsOf: bytes)
            }
        }

        appendAscii("RIFF")
        appendLittleEndian(UInt32(36) + dataSize)
        appendAscii("WAVE")
        appendAscii("fmt ")
        appendLittleEndian(UInt32(16))
        appendLittleEndian(UInt16(1))
        appendLittleEndian(channelCount)
        appendLittleEndian(sampleRate)
        appendLittleEndian(byteRate)
        appendLittleEndian(blockAlign)
        appendLittleEndian(bitsPerSample)
        appendAscii("data")
        appendLittleEndian(dataSize)
        data.append(Data(repeating: 0x80, count: Int(dataSize)))
        return data
    }()
}
