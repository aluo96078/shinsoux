package dev.shinsou.kmp.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals

/**
 * R2-6 (docs/architecture/0006): the app icon's source is the Compose resource. Android copies it
 * at build time; Xcode compiles the asset catalog before Gradle runs, so the iOS copy is committed
 * and has to stay the same picture.
 */
class AppIconCopiesTest {
    @Test
    fun theIosIconIsTheSourceIcon() {
        val source = File("src/commonMain/composeResources/drawable/shinsou_icon.png").readBytes()
        val ios = File("../iosApp/Shinsou/Assets.xcassets/AppIcon.appiconset/AppIcon.png").readBytes()
        assertContentEquals(source, ios, "Copy the icon again: cp composeApp/src/commonMain/composeResources/drawable/shinsou_icon.png iosApp/Shinsou/Assets.xcassets/AppIcon.appiconset/AppIcon.png")
    }
}
