package dev.pocketask

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import dev.chrisbanes.haze.*
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.hazeGlass
import kotlin.math.roundToInt

private val NavigationShape = RoundedCornerShape(32.dp)
private val SelectorShape = RoundedCornerShape(26.dp)
private val destinations = listOf(
    Triple("ask", "Ask", lineIcon("Chat") {
        moveTo(6f, 4f); lineTo(18f, 4f); quadTo(21f, 4f, 21f, 7f)
        lineTo(21f, 15f); quadTo(21f, 18f, 18f, 18f)
        lineTo(8f, 18f); lineTo(3f, 21f); lineTo(3f, 7f); quadTo(3f, 4f, 6f, 4f); close()
        moveTo(8f, 9f); lineTo(16f, 9f); moveTo(8f, 13f); lineTo(13f, 13f)
    }),
    Triple("assets", "Assets", lineIcon("Assets") {
        moveTo(3f, 7f); lineTo(3f, 6f); quadTo(3f, 4f, 5f, 4f)
        lineTo(10f, 4f); lineTo(12f, 7f); lineTo(19f, 7f); quadTo(21f, 7f, 21f, 9f)
        lineTo(21f, 18f); quadTo(21f, 20f, 19f, 20f)
        lineTo(5f, 20f); quadTo(3f, 20f, 3f, 18f); lineTo(3f, 7f); close()
        moveTo(3f, 9f); lineTo(21f, 9f)
    }),
    Triple("history", "History", lineIcon("History") {
        moveTo(3f, 12f); curveTo(3f, 7f, 7f, 3f, 12f, 3f)
        curveTo(17f, 3f, 21f, 7f, 21f, 12f); curveTo(21f, 17f, 17f, 21f, 12f, 21f)
        curveTo(8f, 21f, 5f, 19f, 3.5f, 16f)
        moveTo(3f, 7f); lineTo(3f, 12f); lineTo(8f, 12f)
        moveTo(12f, 7f); lineTo(12f, 12f); lineTo(16f, 14f)
    }),
    Triple("settings", "Settings", lineIcon("Settings") {
        moveTo(3f, 6f); lineTo(21f, 6f)
        moveTo(3f, 12f); lineTo(21f, 12f)
        moveTo(3f, 18f); lineTo(21f, 18f)
        moveTo(8f, 4f); lineTo(8f, 8f)
        moveTo(16f, 10f); lineTo(16f, 14f)
        moveTo(10f, 16f); lineTo(10f, 20f)
    }),
)

@OptIn(ExperimentalHazeApi::class)
@Composable
internal fun GlassNavigation(screen: String, onSelect: (String) -> Unit, haze: HazeState, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val style = remember(colors.surface) {
        GlassStyle.regular.then {
            shape(NavigationShape)
            backgroundColor(colors.surface)
            tint(colors.surface.copy(alpha = 0.55f))
        }
    }
    val selectorStyle = remember(style) { style.then { shape(SelectorShape) } }
    val glassInput = remember(haze) {
        HazeInput.Sources(haze, retention = HazeSourceRetention.ClearWhenUnavailable)
    }
    val selectedIndex = destinations.indexOfFirst { it.first == screen || (it.first == "history" && screen == "detail") }.coerceAtLeast(0)
    var dragging by remember { mutableStateOf<Float?>(null) }
    val position by animateFloatAsState(dragging ?: selectedIndex.toFloat(),
        animationSpec = if (dragging != null) snap() else spring(dampingRatio = 1f, stiffness = 550f))
    val currentSelection by rememberUpdatedState(selectedIndex)
    val select by rememberUpdatedState(onSelect)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current
    BoxWithConstraints(modifier.widthIn(max = 360.dp).fillMaxWidth().height(64.dp)
        .shadow(12.dp, NavigationShape, clip = false)
        .hazeGlass(glassInput, style)
        .clip(NavigationShape).selectableGroup().testTag("navigation")) {
        val tabWidth = (maxWidth - 12.dp - 4.dp * (destinations.size - 1)) / destinations.size
        val step = with(density) { (tabWidth + 4.dp).toPx() }
        val center = with(density) { (6.dp + tabWidth / 2).toPx() }
        fun fingerPosition(x: Float): Float {
            val physical = (x - center) / step
            return (if (rtl) destinations.lastIndex - physical else physical).coerceIn(0f, destinations.lastIndex.toFloat())
        }
        Box(Modifier.fillMaxSize().pointerInput(step, center, rtl) {
            try { detectHorizontalDragGestures(
                onDragStart = { dragging = fingerPosition(it.x) },
                onHorizontalDrag = { change, _ -> change.consume(); dragging = fingerPosition(change.position.x) },
                onDragEnd = {
                    val target = dragging?.roundToInt() ?: currentSelection
                    dragging = null
                    if (target != currentSelection) select(destinations[target].first)
                },
                onDragCancel = { dragging = null }
            ) } finally { dragging = null }
        }.padding(6.dp)) {
            val logical = (dragging ?: position).coerceIn(0f, destinations.lastIndex.toFloat())
            val physical = if (rtl) destinations.lastIndex - logical else logical
            Box(Modifier.align(AbsoluteAlignment.TopLeft).absoluteOffset { IntOffset((physical * step).roundToInt(), 0) }
                .width(tabWidth).fillMaxHeight()
                .hazeGlass(glassInput, selectorStyle).clip(SelectorShape).testTag("navigation.selector"))
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                destinations.forEachIndexed { index, (route, label, icon) ->
                    val selected = selectedIndex == index
                    val preview = (dragging?.roundToInt() ?: selectedIndex) == index
                    val tint by animateColorAsState(if (preview) colors.primary else colors.onSurfaceVariant)
                    Column(Modifier.weight(1f).fillMaxHeight().clip(SelectorShape)
                        .selectable(selected, role = Role.Tab, onClick = { if (screen != route) onSelect(route) })
                        .testTag("navigation.$route"), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)) {
                        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                        Text(label, color = tint, fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
        }
    }
}

private fun lineIcon(name: String, path: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = path)
    }.build()
