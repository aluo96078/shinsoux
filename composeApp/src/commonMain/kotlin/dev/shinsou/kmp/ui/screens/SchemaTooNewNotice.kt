package dev.shinsou.kmp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.dp
import dev.shinsou.kmp.content.SchemaTooNewException
import dev.shinsou.kmp.ui.i18n.shinsouStringsFor
import dev.shinsou.kmp.ui.i18n.text
import dev.shinsou.kmp.ui.theme.ShinsouTheme

/**
 * Shown instead of the app when the content database was written by a newer Shinsou X (R3-1):
 * nothing is opened, so the newer data stays as it was. The settings are not read either, so the
 * system language is used.
 */
@Composable
fun SchemaTooNewNotice(error: SchemaTooNewException) {
    val strings = shinsouStringsFor(Locale.current.toLanguageTag())
    ShinsouTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(strings.text("Update Shinsou X"), style = MaterialTheme.typography.headlineSmall)
                Text(
                    strings.text(
                        "This library was last opened by a newer version of Shinsou X ({0} data, format {1}; this version reads up to {2}). Install the newest version to keep it intact; nothing has been changed.",
                        error.component,
                        error.found,
                        error.supported,
                    ),
                    modifier = Modifier.widthIn(max = 480.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}
