package dev.shinsou.kmp.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import dev.aluo.shinsoux.generated.resources.Res
import dev.aluo.shinsoux.generated.resources.shinsou_icon
import org.jetbrains.compose.resources.painterResource

/**
 * The app icon (the Compose resource drawable/shinsou_icon). R5-1: the resources are this module's,
 * and their generated Res class stays internal; the desktop entry point's window and tray use this.
 */
@Composable
public fun shinsouIconPainter(): Painter = painterResource(Res.drawable.shinsou_icon)
