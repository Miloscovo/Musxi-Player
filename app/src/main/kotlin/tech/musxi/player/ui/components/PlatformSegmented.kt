package tech.musxi.player.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import tech.musxi.core.account.Platform
import tech.musxi.player.ui.theme.displayName

/**
 * Platform picker shaped like an iOS segmented control: one pill track, equal segments, and a white
 * thumb that slides to the chosen one.
 *
 * Segments take no rectangular ripple. The thumb moving is the press feedback, and a square flash
 * inside a pill would be the same unfinished look `pressableRow` exists to avoid.
 */
@Composable
fun PlatformSegmented(
    platforms: List<Platform>,
    selected: Platform,
    onSelect: (Platform) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (platforms.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    val index = platforms.indexOf(selected).coerceAtLeast(0)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(cs.surfaceContainerHigh, CircleShape)
            .padding(3.dp)
            .selectableGroup(),
    ) {
        val segment = maxWidth / platforms.size
        val thumbX by animateDpAsState(
            targetValue = segment * index,
            animationSpec = spring(dampingRatio = 0.8f, stiffness = 500f),
            label = "segmentThumb",
        )
        // Read inside the offset lambda, so the slide animates in layout without recomposing.
        Box(
            Modifier
                .offset { IntOffset(thumbX.roundToPx(), 0) }
                .width(segment)
                .fillMaxHeight()
                .shadow(2.dp, CircleShape, clip = false)
                .background(cs.surfaceContainerLowest, CircleShape),
        )
        Row(Modifier.fillMaxSize()) {
            platforms.forEach { platform ->
                Segment(
                    label = platform.displayName,
                    selected = platform == selected,
                    onClick = { onSelect(platform) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun Segment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val color by animateColorAsState(
        targetValue = if (selected) cs.onSurface else cs.onSurfaceVariant,
        label = "segmentText",
    )
    Box(
        modifier
            .fillMaxHeight()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ),
        contentAlignment = Alignment.Center,
    ) {
        OpticallyCentredText(
            label,
            MaterialTheme.typography.labelLarge.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            ),
            color = color,
        )
    }
}
