package dev.shinsou.kmp.manga

/**
 * The languages manga pages can be read in, and what each needs (docs/research/manga-multilingual.md):
 * Japanese is read by manga-ocr ([MangaModelCatalog.VISION]); Chinese and English by PaddleOCR,
 * from packs downloaded when a work in them is translated.
 */
public object MangaLanguages {
    /** The source languages, in the order they are offered. */
    public val SOURCES: List<String> = listOf("ja", "zh-Hant", "zh-Hans", "en")

    public fun readable(language: String): Boolean = language in SOURCES

    /** The PaddleOCR recogniser [language] is read by (a key of [MangaModelPaths.ocr]); null for manga-ocr. */
    public fun recognizer(language: String): String? = when (language) {
        "zh-Hant", "zh-Hans" -> "zh"
        "en" -> "en"
        else -> null
    }

    /** How [language]'s manga is read: Japanese right to left, the others left to right. */
    public fun direction(language: String): ReadingDirection =
        if (language == "ja") ReadingDirection.RIGHT_TO_LEFT else ReadingDirection.LEFT_TO_RIGHT

    /**
     * The packs a chapter from [from] into [to] needs besides [MangaModelCatalog.VISION]: the
     * recogniser for [from], and the font for [to] where [MangaModelCatalog.VISION]'s lacks characters.
     */
    public fun packs(from: String, to: String): List<ModelPack> = buildList {
        when (recognizer(from)) {
            "zh" -> { add(MangaModelCatalog.OCR_LINES); add(MangaModelCatalog.OCR_ZH) }
            "en" -> { add(MangaModelCatalog.OCR_LINES); add(MangaModelCatalog.OCR_EN) }
        }
        if (to == "zh-Hans") add(MangaModelCatalog.FONT_SC)
    }
}
