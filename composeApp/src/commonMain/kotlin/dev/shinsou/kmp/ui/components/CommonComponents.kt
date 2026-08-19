package dev.shinsou.kmp.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.i18n.text

@Composable
fun CoverImage(
    title: String,
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    selected: Boolean = false,
    headers: Map<String, String> = emptyMap(),
) {
    val strings = LocalShinsouStrings.current
    val platformContext = LocalPlatformContext.current
    val shape = RoundedCornerShape(10.dp)
    var imageError by remember(url, headers) { mutableStateOf(false) }
    val imageRequest = remember(url, headers, platformContext) {
        val networkHeaders = NetworkHeaders.Builder().apply {
            headers.forEach { (name, value) -> set(name, value) }
        }.build()
        ImageRequest.Builder(platformContext)
            .data(url)
            .httpHeaders(networkHeaders)
            .build()
    }
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(
                if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape)
                else Modifier,
            ),
    ) {
        if (url.isNullOrBlank() || imageError) {
            CoverPlaceholder(title = title)
        } else {
            AsyncImage(
                model = imageRequest,
                contentDescription = strings.text("Cover: {0}", title),
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
                onLoading = { imageError = false },
                onSuccess = { imageError = false },
                onError = { imageError = true },
            )
        }
        if (selected) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
            )
        }
    }
}

@Composable
private fun CoverPlaceholder(title: String) {
    val colors = listOf(
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.secondaryContainer,
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(colors)),
        contentAlignment = Alignment.Center,
    ) {
        if (title.isNotBlank()) {
            Text(
                text = title.take(1).uppercase(),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
            )
        } else {
            Icon(Icons.Outlined.BrokenImage, contentDescription = null)
        }
    }
}

@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    compact: Boolean = false,
    onSubmit: (() -> Unit)? = null,
) {
    val strings = LocalShinsouStrings.current
    val resolvedPlaceholder = placeholder ?: strings.search
    val shape = RoundedCornerShape(if (compact) 8.dp else 12.dp)
    Surface(
        modifier = modifier.height(if (compact) 34.dp else 42.dp),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.78f),
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Outlined.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(
                        resolvedPlaceholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onPreviewKeyEvent { event ->
                            if (
                                onSubmit != null &&
                                event.type == KeyEventType.KeyDown &&
                                (event.key == Key.Enter || event.key == Key.NumPadEnter)
                            ) {
                                onSubmit()
                                true
                            } else {
                                false
                            }
                        },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSubmit?.invoke() }),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                )
            }
            AnimatedVisibility(value.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = strings.text("Clear search"),
                    modifier = Modifier
                        .size(18.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onValueChange("") },
                )
            }
        }
    }
}

@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        actions()
    }
}

@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(72.dp),
        ) {
            Box(contentAlignment = Alignment.Center) { icon() }
        }
        Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

@Composable
fun CountBadge(
    count: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    BadgedBox(
        modifier = modifier,
        badge = {
            if (count > 0) {
                Badge { Text(if (count > 99) "99+" else count.toString()) }
            }
        },
        content = { content() },
    )
}

@Composable
fun LoadingScrim(visible: Boolean, label: String? = null) {
    val strings = LocalShinsouStrings.current
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.28f)),
            contentAlignment = Alignment.Center,
        ) {
            Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 8.dp) {
                Row(
                    Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                    Text(label ?: strings.text("Loading"), fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
fun HairlineDivider(modifier: Modifier = Modifier) {
    Divider(modifier, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
}

@Composable
fun ExpandIcon(expanded: Boolean, size: Dp = 20.dp) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "expand")
    Icon(
        imageVector = Icons.Outlined.Close,
        contentDescription = null,
        modifier = Modifier.size(size).rotate(rotation + 45f),
    )
}
