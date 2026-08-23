package dev.shinsou.kmp.plugin.events

import dev.shinsou.kmp.domain.model.SourceKey
import kotlin.test.Test
import kotlin.test.assertEquals

class ExactSourceRefreshInvalidationsTest {
    @Test
    fun oldArtifactCloseCannotClearReplacementGeneration() {
        val invalidations = ExactSourceRefreshInvalidations()
        val source = SourceKey(packageId = "pkg.test", sourceId = "source")
        val old = ExactPluginSourceTarget(
            PluginArtifactIdentity("pkg.test", "1.0.0", 1, "a".repeat(64)), source,
        )
        val replacement = ExactPluginSourceTarget(
            PluginArtifactIdentity("pkg.test", "2.0.0", 2, "b".repeat(64)), source,
        )
        invalidations.invalidate(old)
        invalidations.invalidate(replacement)
        invalidations.clear(old)
        assertEquals(1L, invalidations.generations.value[source])
        invalidations.clear(replacement)
        assertEquals(null, invalidations.generations.value[source])
    }
}
