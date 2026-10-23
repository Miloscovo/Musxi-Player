package tech.musxi.player.ui.player

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp

/**
 * The PC player's compact character badge, adapted to a 48dp Android touch target.
 *
 * The character is an icon: it keeps the same scale as the heart at large system font sizes.
 * Its actual system-font outline is centred in both axes, independent of ascent, descent and
 * font padding. Centring a Text's line box left the visible character slightly off centre.
 */
@Composable
internal fun TranslationChipButton(
    translation: TranslationUi,
    onToggle: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    onActiveColor: Color = MaterialTheme.colorScheme.onPrimary,
) {
    if (translation.chip == TranslationChip.HIDDEN) return
    val cs = MaterialTheme.colorScheme
    val warning = translation.chip == TranslationChip.WARNING
    val retryOnly = warning && !translation.hasText
    val inert = translation.chip == TranslationChip.NONE
    val checked = translation.chip == TranslationChip.ON || (warning && translation.hasText)
    val enabled = !inert && (!retryOnly || translation.canRefresh)
    val description = when {
        inert -> "暂无可用译文"
        retryOnly -> "译文获取失败"
        warning -> "翻译已开启，译文更新失败"
        checked -> "翻译已开启"
        else -> "翻译已关闭"
    }
    val clickLabel = when {
        retryOnly -> "重试译文"
        checked -> "关闭翻译"
        else -> "打开翻译"
    }
    Box(
        modifier.size(48.dp)
            .clip(CircleShape)
            .combinedClickable(
                enabled = enabled,
                role = if (retryOnly) Role.Button else Role.Switch,
                onClickLabel = clickLabel,
                onClick = if (retryOnly) onRefresh else onToggle,
                onLongClickLabel = if (translation.canRefresh && !retryOnly) "刷新译文" else null,
                onLongClick = if (translation.canRefresh && !retryOnly) onRefresh else null,
            )
            .semantics {
                contentDescription = "翻译"
                stateDescription = description
                if (!retryOnly) {
                    toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val glyphPx = with(density) { 13.dp.toPx() }
        val glyph = remember(glyphPx) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                textSize = glyphPx
            }
            val path = Path()
            paint.getTextPath("译", 0, 1, 0f, 0f, path)
            val bounds = RectF()
            path.computeBounds(bounds, true)
            path.asComposePath() to bounds
        }
        val ink = when {
            inert -> cs.onSurfaceVariant.copy(alpha = 0.35f)
            checked -> onActiveColor
            else -> cs.onSurfaceVariant.copy(alpha = 0.9f)
        }
        Canvas(Modifier.size(width = 24.dp, height = 21.dp)) {
            val radius = 6.dp.toPx()
            if (checked) {
                drawRoundRect(activeColor, cornerRadius = CornerRadius(radius))
            } else {
                // Inset the stroke so the outer contour matches the filled badge exactly.
                val stroke = 1.2.dp.toPx()
                drawRoundRect(
                    color = ink,
                    topLeft = Offset(stroke / 2f, stroke / 2f),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(radius - stroke / 2f),
                    style = Stroke(stroke),
                )
            }
            translate(
                left = size.width / 2f - glyph.second.centerX(),
                top = size.height / 2f - glyph.second.centerY(),
            ) {
                drawPath(glyph.first, ink)
            }
            if (warning) {
                drawCircle(
                    color = cs.error,
                    radius = 1.5.dp.toPx(),
                    center = Offset(size.width - 2.dp.toPx(), 2.dp.toPx()),
                )
            }
        }
    }
}
