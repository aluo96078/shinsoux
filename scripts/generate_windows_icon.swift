#!/usr/bin/env swift

import Foundation

guard CommandLine.arguments.count >= 4 else {
    FileHandle.standardError.write(
        Data("usage: generate_windows_icon.swift <output.ico> <16.png> [32.png ... 256.png]\n".utf8),
    )
    exit(2)
}

private let pngSignature = Data([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])

private func pngDimension(_ data: Data, offset: Int) throws -> Int {
    guard data.count >= offset + 4 else { throw CocoaError(.fileReadCorruptFile) }
    return data[offset..<(offset + 4)].reduce(0) { result, byte in
        (result << 8) | Int(byte)
    }
}

private func littleEndian<T: FixedWidthInteger>(_ value: T) -> Data {
    var encoded = value.littleEndian
    return Data(bytes: &encoded, count: MemoryLayout<T>.size)
}

struct IconImage {
    let width: Int
    let height: Int
    let payload: Data
}

let output = URL(fileURLWithPath: CommandLine.arguments[1])
let images = try CommandLine.arguments.dropFirst(2).map { path -> IconImage in
    let payload = try Data(contentsOf: URL(fileURLWithPath: path))
    guard payload.prefix(pngSignature.count) == pngSignature else {
        throw CocoaError(.fileReadCorruptFile)
    }
    let width = try pngDimension(payload, offset: 16)
    let height = try pngDimension(payload, offset: 20)
    guard width == height, width > 0, width <= 256 else {
        throw CocoaError(.fileReadCorruptFile)
    }
    return IconImage(width: width, height: height, payload: payload)
}.sorted { $0.width < $1.width }

guard Set(images.map(\.width)).count == images.count else {
    throw CocoaError(.fileWriteFileExists)
}

var result = Data()
result.append(littleEndian(UInt16(0)))
result.append(littleEndian(UInt16(1)))
result.append(littleEndian(UInt16(images.count)))

var payloadOffset = 6 + images.count * 16
for image in images {
    result.append(UInt8(image.width == 256 ? 0 : image.width))
    result.append(UInt8(image.height == 256 ? 0 : image.height))
    result.append(0)
    result.append(0)
    result.append(littleEndian(UInt16(1)))
    result.append(littleEndian(UInt16(32)))
    result.append(littleEndian(UInt32(image.payload.count)))
    result.append(littleEndian(UInt32(payloadOffset)))
    payloadOffset += image.payload.count
}

images.forEach { result.append($0.payload) }
try result.write(to: output, options: .atomic)
