package dev.shinsou.kmp.app

import dev.shinsou.kmp.content.PlatformContentInfrastructure
import dev.shinsou.kmp.content.SchemaComponent
import dev.shinsou.kmp.content.SchemaTooNewException
import dev.shinsou.kmp.content.checkBeforeOpening
import dev.shinsou.kmp.content.contentFoundationSchemaComponents
import dev.shinsou.kmp.sync.v3.SyncV3Schema
import dev.shinsou.kmp.translation.TranslationMemorySchema

/**
 * Every table group in the content database whose newer format this version must refuse; the
 * search index is left out because it is rebuilt instead.
 */
internal fun contentSchemaComponents(): List<SchemaComponent> =
    contentFoundationSchemaComponents() + listOf(
        SyncV3Schema.component,
        TranslationMemorySchema.component,
    )

/**
 * What the app checks before anything opens the stores: no table group of the content database is
 * in a format newer than this version reads; when opening it upgrades one, the file is copied
 * first (R3-1).
 */
fun PlatformContentInfrastructure.checkBeforeOpening(): SchemaTooNewException? =
    checkBeforeOpening(contentSchemaComponents())
