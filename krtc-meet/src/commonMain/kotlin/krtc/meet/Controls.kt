package krtc.meet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import kotlin.math.ceil

/** Colours of the call screen: dark greys with one accent, like the voice chat apps people know. */
internal object MeetColors {
    val Background = Color(0xFF1E1F22)
    val Panel = Color(0xFF2B2D31)
    val Raised = Color(0xFF383A40)
    val RaisedHover = Color(0xFF45474E)
    val Text = Color(0xFFDBDEE1)
    val TextMuted = Color(0xFF949BA4)
    val Accent = Color(0xFF5865F2)
    val Green = Color(0xFF23A55A)
    val Red = Color(0xFFDA373C)
    val RedHover = Color(0xFFE5585C)
    val Yellow = Color(0xFFF0B232)
    val TileBackground = Color(0xFF111214)
    val Scrim = Color(0xB3000000)

    val scheme = darkColorScheme(
        primary = Accent, onPrimary = Color.White,
        primaryContainer = Color(0xFF3C45A5), onPrimaryContainer = Color.White,
        secondaryContainer = Raised, onSecondaryContainer = Text,
        background = Background, onBackground = Text,
        surface = Background, onSurface = Text,
        surfaceVariant = Panel, onSurfaceVariant = TextMuted,
        surfaceContainer = Panel, surfaceContainerHigh = Raised, surfaceContainerHighest = RaisedHover,
        error = Red, onError = Color.White, errorContainer = Color(0xFF5C2326), onErrorContainer = Color(0xFFFFDAD6),
        outline = Color(0xFF4E5058), outlineVariant = Color(0xFF3F4147),
    )

    /** A steady colour per name for avatars without video. */
    fun avatar(name: String): Color {
        val palette = listOf(0xFF5865F2, 0xFF3BA55C, 0xFFEB459E, 0xFFFAA61A, 0xFFED4245, 0xFF1ABC9C, 0xFF9B59B6, 0xFF3498DB)
        return Color(palette[(name.hashCode() and 0x7FFFFFFF) % palette.size])
    }
}

/** Shows [text] above its anchor while the pointer is over it (desktop); nothing on touch screens. */
@Composable
internal fun Hint(text: String, visible: Boolean) {
    if (!visible || text.isEmpty()) return
    Popup(popupPositionProvider = AboveAnchor) {
        Text(
            text, color = Color.White, fontSize = 13.sp, maxLines = 1,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF111214)).padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

private object AboveAnchor : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(0, maxOf(0, windowSize.width - popupContentSize.width))
        val above = anchorBounds.top - popupContentSize.height - 8
        return IntOffset(x, if (above >= 0) above else anchorBounds.bottom + 8)
    }
}

/** How a control bar button looks: plain, switched off (the red slash state), on air, or the leave button. */
internal enum class Tone { NORMAL, OFF, LIVE, DANGER, ACTIVE, GHOST }

/** A round icon button of the control bar, with a hint on hover and an optional counter. */
@Composable
internal fun ControlButton(
    icon: ImageVector,
    hint: String,
    onClick: () -> Unit,
    tone: Tone = Tone.NORMAL,
    enabled: Boolean = true,
    badge: Int = 0,
    size: Dp = 48.dp,
    wide: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background = when (tone) {
        Tone.NORMAL -> if (hovered && enabled) MeetColors.RaisedHover else MeetColors.Raised
        Tone.OFF -> if (hovered) Color(0xFF4A2C2F) else Color(0xFF3B2629)
        Tone.LIVE -> MeetColors.Green
        Tone.DANGER -> if (hovered) MeetColors.RedHover else MeetColors.Red
        Tone.ACTIVE -> MeetColors.Accent
        Tone.GHOST -> if (hovered && enabled) MeetColors.Raised else Color.Transparent
    }
    val tint = when {
        !enabled -> MeetColors.TextMuted.copy(alpha = 0.5f)
        tone == Tone.OFF -> Color(0xFFF23F43)
        tone == Tone.NORMAL || tone == Tone.GHOST -> MeetColors.Text
        else -> Color.White
    }
    Box {
        Box(
            Modifier.size(width = if (wide) size * 1.5f else size, height = size).clip(if (wide) RoundedCornerShape(size / 2) else CircleShape)
                .background(background)
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, hint, Modifier.size(size * 0.5f), tint = tint)
        }
        if (badge > 0) {
            Text(
                if (badge > 99) "99+" else "$badge", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-2).dp).sizeIn(minWidth = 18.dp)
                    .clip(RoundedCornerShape(9.dp)).background(MeetColors.Red).padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
        Hint(hint, hovered)
    }
}

/** A small icon button over video (pin, volume, full screen, stop watching), on a dark round backing. */
@Composable
internal fun TileAction(icon: ImageVector, hint: String, onClick: () -> Unit, label: String? = null, tint: Color = Color.White) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(16.dp)).background(if (hovered) Color(0xE6000000) else MeetColors.Scrim)
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(horizontal = if (label != null) 10.dp else 7.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(icon, hint, Modifier.size(18.dp), tint = tint)
            if (label != null) Text(label, color = Color.White, fontSize = 12.sp, maxLines = 1)
        }
        Hint(hint, hovered)
    }
}

/** The round initial shown instead of video. */
@Composable
internal fun Avatar(name: String, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(MeetColors.avatar(name)), contentAlignment = Alignment.Center) {
        Text(name.trim().take(1).uppercase(), color = Color.White, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Clip)
    }
}

/**
 * Tiles of one aspect ratio laid out to fill the space: the column count that gives the largest tiles, rows centred.
 * When even one column would make them smaller than [minWidth], the grid scrolls instead.
 */
@Composable
internal fun <T> FitGrid(
    items: List<T>,
    modifier: Modifier = Modifier,
    gap: Dp = 8.dp,
    aspect: Float = 16f / 9f,
    minWidth: Dp = 220.dp,
    key: (T) -> Any,
    content: @Composable (T, Modifier) -> Unit,
) {
    BoxWithConstraints(modifier) {
        val n = items.size.coerceAtLeast(1)
        var best = 1
        var bestWidth = 0.dp
        for (cols in 1..n) {
            val rows = ceil(n / cols.toDouble()).toInt()
            val byWidth = (maxWidth - gap * (cols - 1)) / cols
            val byHeight = (maxHeight - gap * (rows - 1)) / rows * aspect
            val w = min(byWidth, byHeight)
            if (w > bestWidth) {
                bestWidth = w
                best = cols
            }
        }
        val scrolls = bestWidth < minWidth
        val cols = if (scrolls) maxOf(1, ((maxWidth + gap) / (minWidth + gap)).toInt()) else best
        val width = if (scrolls) (maxWidth - gap * (cols - 1)) / cols else bestWidth
        Column(
            if (scrolls) Modifier.fillMaxWidth().verticalScroll(rememberScrollState()) else Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(gap, if (scrolls) Alignment.Top else Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items.chunked(cols).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { item ->
                        androidx.compose.runtime.key(key(item)) { content(item, Modifier.size(width, width / aspect)) }
                    }
                }
            }
        }
    }
}
