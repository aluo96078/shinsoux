package dev.shinsou.kmp.migration.shuyue

import dev.shinsou.kmp.domain.model.SourceKey

/**
 * The sources of the former ShuYue novel packages, which are now ordinary Shinsou packages
 * (ADR-0002 A7b). A rewritten package keeps each source's package id, string source id and
 * numeric id, so library rows, reading progress and the saved login (stored under the numeric
 * id) bind to it unchanged:
 *
 * | package         | sourceId             | legacyLongId         |
 * |-----------------|----------------------|----------------------|
 * | zh.wenku8       | zh.wenku8            | -9110000000000001    |
 * | zh.wenku8.api   | zh.wenku8.api        | -9110000000000002    |
 * | zh.biquge.tw    | zh.biquge.tw         | -9110000000000003    |
 * | zh.bilimanga    | zh.bilimanga.novel   | -9110000000000004    |
 * | zh.bilimanga    | zh.bilimanga.manga   | -9110000000000005    |
 */
internal object ShuYueLegacySources {
    private val sources: List<SourceKey> = listOf(
        SourceKey(packageId = "zh.wenku8", sourceId = "zh.wenku8", legacyLongId = -9_110_000_000_000_001L),
        SourceKey(packageId = "zh.wenku8.api", sourceId = "zh.wenku8.api", legacyLongId = -9_110_000_000_000_002L),
        SourceKey(packageId = "zh.biquge.tw", sourceId = "zh.biquge.tw", legacyLongId = -9_110_000_000_000_003L),
        SourceKey(packageId = "zh.bilimanga", sourceId = "zh.bilimanga.novel", legacyLongId = -9_110_000_000_000_004L),
        SourceKey(packageId = "zh.bilimanga", sourceId = "zh.bilimanga.manga", legacyLongId = -9_110_000_000_000_005L),
    )

    /** The source a ShuYue v1 backup's source id belongs to; null keeps it on the inert compatibility package. */
    fun sourceKeyForLegacySourceId(sourceId: String): SourceKey? = sources.singleOrNull { it.sourceId == sourceId }
}
