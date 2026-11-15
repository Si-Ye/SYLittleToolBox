package io.github.siye.littletoolbox

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
internal fun PaletteChoiceRow(selected: Palette, onOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    SettingsRow(
        title = "主题色",
        subtitle = selected.title,
        onClick = onOpen,
        trailing = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PaletteSwatch(selected.colorScheme(dark))
                Icon(
                    painterResource(R.drawable.ic_forward),
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
internal fun PalettePickerPage(selected: Palette, onSelect: (Palette) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    LazyColumn(
        modifier = Modifier.fillMaxSize().selectableGroup(),
        contentPadding = PaddingValues(horizontal = LocalPageGutter.current, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                text = "选中后立即应用。色样左侧为浅色，右侧为深色。",
                modifier = Modifier.padding(bottom = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }
        items(Palette.entries, key = { it.name }) { palette ->
            val checked = selected == palette
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp)
                    .clip(shape)
                    .background(if (checked) colors.secondaryContainer else colors.surfaceContainer)
                    .selectable(
                        selected = checked,
                        role = Role.RadioButton,
                        onClick = { if (!checked) onSelect(palette) },
                    )
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = palette.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (checked) colors.onSecondaryContainer else colors.onSurface,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PaletteSwatch(palette.colorScheme(dark = false))
                    PaletteSwatch(palette.colorScheme(dark = true))
                }
                RadioButton(selected = checked, onClick = null)
            }
        }
    }
}

@Composable
private fun PaletteSwatch(colors: ColorScheme) {
    val shape = RoundedCornerShape(7.dp)
    Row(
        modifier = Modifier
            .size(width = 48.dp, height = 23.dp)
            .clip(shape)
            .border(1.dp, colors.outline, shape),
    ) {
        Box(Modifier.weight(1f).fillMaxHeight().background(colors.surface))
        Box(Modifier.weight(1f).fillMaxHeight().background(colors.secondaryContainer))
        Box(Modifier.weight(1f).fillMaxHeight().background(colors.secondary))
    }
}
