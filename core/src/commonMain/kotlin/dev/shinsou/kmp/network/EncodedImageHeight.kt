package dev.shinsou.kmp.network

/** Height of an encoded raster image read from its header, or null when it cannot be read. */
expect fun encodedImageHeight(bytes: ByteArray): Int?
