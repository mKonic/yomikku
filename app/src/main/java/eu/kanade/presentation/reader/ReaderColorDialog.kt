package eu.kanade.presentation.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.github.skydoves.colorpicker.compose.BrightnessSlider
import com.github.skydoves.colorpicker.compose.HsvColorPicker
import com.github.skydoves.colorpicker.compose.rememberColorPickerController
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import tachiyomi.core.common.preference.Preference
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

/** The custom theme's two colours, each a row with a swatch that opens a picker. */
@Composable
fun ReaderCustomColorRows(preferences: ReaderPreferences) {
    val background by preferences.customBackground().collectAsState()
    val foreground by preferences.customForeground().collectAsState()
    ColorRow(KMR.strings.reader_theme_custom_background, preferences.customBackground(), true, background, foreground)
    ColorRow(KMR.strings.reader_theme_custom_text, preferences.customForeground(), false, background, foreground)
}

@Composable
private fun ColorRow(
    label: StringResource,
    preference: Preference<Int>,
    isBackground: Boolean,
    background: Int,
    foreground: Int,
) {
    var open by remember { mutableStateOf(false) }
    val value by preference.collectAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { open = true }
            .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(label), modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Color(value))
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
        )
    }
    if (open) {
        ReaderColorDialog(
            title = label,
            initial = Color(value),
            background = Color(background),
            foreground = Color(foreground),
            editingBackground = isBackground,
            onDismissRequest = { open = false },
            onConfirm = {
                preference.set(it.toArgb())
                open = false
            },
        )
    }
}

/** A colour wheel with brightness, a hex field, and a line of text drawn in the colours being chosen. */
@OptIn(ExperimentalStdlibApi::class)
@Composable
private fun ReaderColorDialog(
    title: StringResource,
    initial: Color,
    background: Color,
    foreground: Color,
    editingBackground: Boolean,
    onDismissRequest: () -> Unit,
    onConfirm: (Color) -> Unit,
) {
    val controller = rememberColorPickerController()
    var color by remember { mutableStateOf(initial) }
    var hex by remember { mutableStateOf(initial.toArgb().toHexString().takeLast(6).uppercase()) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
                HsvColorPicker(
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    controller = controller,
                    initialColor = initial,
                    onColorChanged = { envelope ->
                        if (envelope.fromUser) {
                            color = envelope.color
                            hex = envelope.hexCode.takeLast(6).uppercase()
                        }
                    },
                )
                BrightnessSlider(
                    modifier = Modifier.fillMaxWidth().height(24.dp),
                    controller = controller,
                    borderRadius = 12.dp,
                    initialColor = initial,
                )
                OutlinedTextField(
                    value = hex,
                    onValueChange = { text ->
                        hex = text.removePrefix("#").take(6).uppercase()
                        hex.takeIf { it.length == 6 }?.toLongOrNull(16)?.let {
                            color = Color(0xFF000000 or it)
                            controller.selectByColor(color, fromUser = false)
                        }
                    },
                    prefix = { Text("#") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(KMR.strings.reader_theme_custom_preview),
                    color = if (editingBackground) foreground else color,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(if (editingBackground) color else background)
                        .padding(MaterialTheme.padding.medium),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(color.copy(alpha = 1f)) }) { Text(stringResource(MR.strings.action_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_cancel)) }
        },
    )
}
