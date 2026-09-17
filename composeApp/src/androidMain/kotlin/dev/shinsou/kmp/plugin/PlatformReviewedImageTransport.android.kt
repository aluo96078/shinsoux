package dev.shinsou.kmp.plugin

// Android's public proxy override is process-wide. It must not redirect unrelated WebViews or
// weaken their isolation; retain the existing pinned native path until an isolated API exists.
public actual fun createPlatformReviewedImageTransport(): PluginHttpTransport? = null
