package tech.musxi.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Three bars in the accent: the playing signal, sized to the index slot it replaces. */
@Composable
internal fun PlayingBars(accent: Color, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf(9.dp, 14.dp, 7.dp).forEach { barHeight ->
            Box(Modifier.width(3.dp).height(barHeight).clip(CircleShape).background(accent))
        }
    }
}
