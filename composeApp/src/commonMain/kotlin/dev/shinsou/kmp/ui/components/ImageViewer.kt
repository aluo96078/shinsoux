package dev.shinsou.kmp.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.i18n.text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Saves picture bytes where the platform keeps pictures; null where saving is unavailable. */
public val LocalImageSaver = staticCompositionLocalOf<(suspend (suggestedName: String, bytes: ByteArray) -> Boolean)?> {
    null
}

private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f

/**
 * Full-screen viewer for one already-loaded picture: pinch or double-tap to zoom, drag to pan
 * while zoomed, and save it when the host offers [LocalImageSaver].
 */
@Composable
public fun ImageViewerDialog(
    bytes: ByteArray,
    suggestedName: String,
    contentDescription: String?,
    onDismiss: () -> Unit,
) {
    val strings = LocalShinsouStrings.current
    val context = LocalPlatformContext.current
    val saver = LocalImageSaver.current
    val scope = rememberCoroutineScope()
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    // Keeps the picture covering the screen edge to edge while zoomed: no panning into emptiness.
    fun clamp(value: Offset, zoom: Float): Offset {
        val maxX = viewport.width * (zoom - 1f) / 2f
        val maxY = viewport.height * (zoom - 1f) / 2f
        return Offset(value.x.coerceIn(-maxX, maxX), value.y.coerceIn(-maxY, maxY))
    }

    val transformable = rememberTransformableState { zoom, pan, _ ->
        val nextScale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
        scale = nextScale
        offset = if (nextScale <= 1f) Offset.Zero else clamp(offset + pan, nextScale)
    }
    val shownScale by animateFloatAsState(scale, label = "image-viewer-zoom")
    val shownOffset by animateOffsetAsState(offset, label = "image-viewer-pan")

    LaunchedEffect(message) {
        if (message != null) {
            delay(2_500)
            message = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onSizeChanged { viewport = it },
        ) {
            AsyncImage(
                model = remember(bytes) { ImageRequest.Builder(context).data(bytes).build() },
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = { tap ->
                                if (scale > 1f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    // Zoom in around the tapped point.
                                    val center = Offset(size.width / 2f, size.height / 2f)
                                    scale = DOUBLE_TAP_ZOOM
                                    offset = clamp((center - tap) * (DOUBLE_TAP_ZOOM - 1f), DOUBLE_TAP_ZOOM)
                                }
                            },
                        )
                    }
                    .transformable(transformable)
                    .graphicsLayer {
                        scaleX = shownScale
                        scaleY = shownScale
                        translationX = shownOffset.x
                        translationY = shownOffset.y
                    },
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .safeDrawingPadding()
                    .padding(8.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ViewerButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = strings.text("Close"), tint = Color.White)
                }
                if (saver != null) {
                    ViewerButton(
                        enabled = !saving,
                        onClick = {
                            saving = true
                            scope.launch {
                                val saved = try {
                                    saver(suggestedName, bytes)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Throwable) {
                                    false
                                }
                                saving = false
                                message = strings.text(if (saved) "Image saved" else "Could not save the image")
                            }
                        },
                    ) {
                        if (saving) {
                            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.padding(10.dp))
                        } else {
                            Icon(Icons.Outlined.Download, contentDescription = strings.text("Save image"), tint = Color.White)
                        }
                    }
                }
            }
            message?.let { text ->
                Surface(
                    color = Color.Black.copy(alpha = 0.78f),
                    contentColor = Color.White,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .safeDrawingPadding()
                        .padding(bottom = 32.dp),
                ) {
                    Text(text, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
                }
            }
        }
    }
}

@Composable
private fun ViewerButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Surface(color = Color.Black.copy(alpha = 0.45f), shape = androidx.compose.foundation.shape.CircleShape) {
        IconButton(onClick = onClick, enabled = enabled) { content() }
    }
}

/** A file name for a picture taken from [url], keeping its extension when it has a usual one. */
public fun imageFileNameFor(url: String, fallbackStem: String = "image"): String {
    val last = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
    val safe = last.filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }.take(80)
    val extension = safe.substringAfterLast('.', "").lowercase()
    return when {
        safe.isNotEmpty() && extension in setOf("jpg", "jpeg", "png", "webp", "gif") -> "shinsou-$safe"
        safe.isNotEmpty() -> "shinsou-${safe.substringBeforeLast('.')}.jpg"
        else -> "shinsou-$fallbackStem.jpg"
    }
}
