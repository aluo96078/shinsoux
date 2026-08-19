#!/usr/bin/env swift

import Foundation

guard CommandLine.arguments.count == 3 else {
    FileHandle.standardError.write(Data("usage: generate_macos_icon.swift <iconset> <output.icns>\n".utf8))
    exit(2)
}

let iconset = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
let output = URL(fileURLWithPath: CommandLine.arguments[2])
let chunks: [(String, String)] = [
    ("icp4", "icon_16x16.png"),
    ("icp5", "icon_32x32.png"),
    ("icp6", "icon_32x32@2x.png"),
    ("ic07", "icon_128x128.png"),
    ("ic08", "icon_256x256.png"),
    ("ic09", "icon_512x512.png"),
    ("ic10", "icon_512x512@2x.png"),
]

func bigEndian(_ value: UInt32) -> Data {
    var encoded = value.bigEndian
    return Data(bytes: &encoded, count: MemoryLayout<UInt32>.size)
}

var body = Data()
for (type, fileName) in chunks {
    let payload = try Data(contentsOf: iconset.appendingPathComponent(fileName))
    guard let typeData = type.data(using: .ascii), typeData.count == 4 else {
        throw CocoaError(.fileReadCorruptFile)
    }
    body.append(typeData)
    body.append(bigEndian(UInt32(payload.count + 8)))
    body.append(payload)
}

var result = Data("icns".utf8)
result.append(bigEndian(UInt32(body.count + 8)))
result.append(body)
try result.write(to: output, options: .atomic)
