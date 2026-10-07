package dev.pocketask

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import dev.chrisbanes.haze.rememberHazeState
import kotlin.math.abs
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class NavigationUiTests {
    @Test fun tabsRemainAccessibleAndSwitchSelectionInBothThemes() = runComposeUiTest {
        var screen by mutableStateOf("ask")
        var dark by mutableStateOf(false)
        setContent { MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
            GlassNavigation(screen, { screen = it }, rememberHazeState(), Modifier.width(340.dp))
        } }
        onNodeWithTag("navigation.ask").assertIsSelected()
        for (route in listOf("assets", "history", "settings", "ask")) {
            val tab = onNodeWithTag("navigation.$route")
            tab.assertIsDisplayed().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick()
            tab.assertIsSelected()
            runOnIdle { assertEquals(route, screen) }
            val bounds = tab.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.width >= 48 * density.density && bounds.height >= 48 * density.density)
        }
        runOnIdle { dark = true; screen = "assets" }
        onNodeWithTag("navigation.assets").assertIsSelected()
        onNodeWithTag("navigation.ask").assertIsNotSelected()
    }

    @Test fun anOpenHistoryConversationKeepsHistorySelected() = runComposeUiTest {
        setContent { MaterialTheme {
            GlassNavigation("detail", {}, rememberHazeState(), Modifier.width(340.dp))
        } }
        onNodeWithTag("navigation.history").assertIsSelected()
        onNodeWithTag("navigation.ask").assertIsNotSelected()
        onNodeWithTag("navigation.assets").assertIsNotSelected()
        onNodeWithTag("navigation.settings").assertIsNotSelected()
    }

    @Test fun tappingMovesOneSelectorThroughIntermediatePositions() = runComposeUiTest {
        var screen by mutableStateOf("ask")
        setContent { MaterialTheme { GlassNavigation(screen, { screen = it }, rememberHazeState(), Modifier.width(340.dp)) } }
        val selector = onNodeWithTag("navigation.selector")
        val start = selector.fetchSemanticsNode().boundsInRoot.center.x
        val end = onNodeWithTag("navigation.settings").fetchSemanticsNode().boundsInRoot.center.x
        mainClock.autoAdvance = false
        onNodeWithTag("navigation.settings").performClick()
        mainClock.advanceTimeByFrame(); mainClock.advanceTimeBy(80)
        val moving = selector.fetchSemanticsNode().boundsInRoot.center.x
        assertTrue(moving > start + 1 && moving < end - 1)
        mainClock.advanceTimeBy(1000)
        assertTrue(abs(selector.fetchSemanticsNode().boundsInRoot.center.x - end) < 1.1f)
        onNodeWithTag("navigation.settings").assertIsSelected()
        mainClock.autoAdvance = true
    }

    @Test fun draggingFollowsTheFingerThenSnapsOnceAndInterruptionRestoresTheSelection() = runComposeUiTest {
        var screen by mutableStateOf("ask")
        val changes = mutableListOf<String>()
        var width by mutableStateOf(340.dp)
        setContent { MaterialTheme { GlassNavigation(screen, { changes += it; screen = it }, rememberHazeState(), Modifier.width(width)) } }
        val bar = onNodeWithTag("navigation")
        val ask = onNodeWithTag("navigation.ask").centerWithin(bar)
        val assets = onNodeWithTag("navigation.assets").centerWithin(bar)
        val history = onNodeWithTag("navigation.history").centerWithin(bar)
        val settings = onNodeWithTag("navigation.settings").centerWithin(bar)
        val between = Offset(assets.x + (history.x - assets.x) * .25f, assets.y)
        mainClock.autoAdvance = false
        bar.performTouchInput { down(ask); moveTo(between) }
        mainClock.advanceTimeByFrame()
        val following = onNodeWithTag("navigation.selector").centerWithin(bar)
        assertTrue(abs(following.x - between.x) < 1.1f)
        onNodeWithTag("navigation.ask").assertIsSelected()
        runOnIdle { assertTrue(changes.isEmpty()) }
        bar.performTouchInput { up() }
        mainClock.advanceTimeBy(1000)
        onNodeWithTag("navigation.assets").assertIsSelected()
        assertTrue(abs(onNodeWithTag("navigation.selector").centerWithin(bar).x - assets.x) < 1.1f,
            "After release: selector=${onNodeWithTag("navigation.selector").centerWithin(bar).x}, tab=${assets.x}")
        runOnIdle { assertEquals(listOf("assets"), changes) }
        bar.performTouchInput { down(assets); moveTo(settings) }
        mainClock.advanceTimeByFrame()
        // A layout resize cancels the active pointer-input coroutine. Skiko's
        // synthetic cancel event is a no-op, so exercise a real interruption.
        runOnIdle { width = 320.dp }
        mainClock.advanceTimeByFrame()
        bar.performTouchInput { up() }
        mainClock.advanceTimeBy(1000)
        val resizedAssets = onNodeWithTag("navigation.assets").centerWithin(bar)
        val resizedAsk = onNodeWithTag("navigation.ask").centerWithin(bar)
        onNodeWithTag("navigation.assets").assertIsSelected()
        assertTrue(abs(onNodeWithTag("navigation.selector").centerWithin(bar).x - resizedAssets.x) < 1.1f)
        runOnIdle { assertEquals(listOf("assets"), changes) }
        bar.performTouchInput { swipe(resizedAssets, resizedAsk, durationMillis = 400) }
        mainClock.advanceTimeBy(1000)
        onNodeWithTag("navigation.ask").assertIsSelected()
        runOnIdle { assertEquals(listOf("assets", "ask"), changes) }
        mainClock.autoAdvance = true
    }

    @Test fun draggingInRightToLeftLayoutsUsesTheVisibleTabOrder() = runComposeUiTest {
        var screen by mutableStateOf("ask")
        val changes = mutableListOf<String>()
        setContent { CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { MaterialTheme {
            GlassNavigation(screen, { changes += it; screen = it }, rememberHazeState(), Modifier.width(340.dp))
        } } }
        val bar = onNodeWithTag("navigation")
        val ask = onNodeWithTag("navigation.ask").centerWithin(bar)
        val assets = onNodeWithTag("navigation.assets").centerWithin(bar)
        val settings = onNodeWithTag("navigation.settings").centerWithin(bar)
        assertTrue(ask.x > assets.x && assets.x > settings.x)
        assertTrue(abs(onNodeWithTag("navigation.selector").centerWithin(bar).x - ask.x) < 1.1f)
        bar.performTouchInput { swipe(ask, assets, durationMillis = 500) }
        onNodeWithTag("navigation.assets").assertIsSelected()
        assertTrue(abs(onNodeWithTag("navigation.selector").centerWithin(bar).x - assets.x) < 1.1f)
        bar.performTouchInput { swipe(assets, settings, durationMillis = 500) }
        onNodeWithTag("navigation.settings").assertIsSelected()
        bar.performTouchInput { swipe(settings, ask, durationMillis = 500) }
        onNodeWithTag("navigation.ask").assertIsSelected()
        runOnIdle { assertEquals(listOf("assets", "settings", "ask"), changes) }
    }

    @Test fun shortAndVerticalDragsKeepAnOpenConversationAndOversizedDragsStayInTheBar() = runComposeUiTest {
        var screen by mutableStateOf("detail")
        val changes = mutableListOf<String>()
        setContent { MaterialTheme { GlassNavigation(screen, { changes += it; screen = it }, rememberHazeState(), Modifier.width(340.dp)) } }
        val bar = onNodeWithTag("navigation")
        val history = onNodeWithTag("navigation.history").centerWithin(bar)
        val settings = onNodeWithTag("navigation.settings").centerWithin(bar)
        bar.performTouchInput { swipe(history, history + Offset(25f, 0f), durationMillis = 400) }
        bar.performTouchInput { swipe(history, history + Offset(0f, 50f), durationMillis = 400) }
        runOnIdle { assertEquals("detail", screen); assertTrue(changes.isEmpty()) }
        val width = bar.fetchSemanticsNode().boundsInRoot.width
        bar.performTouchInput { swipe(history, Offset(width + 80f, history.y), durationMillis = 500) }
        onNodeWithTag("navigation.settings").assertIsSelected()
        bar.performTouchInput { swipe(settings, Offset(-80f, settings.y), durationMillis = 500) }
        onNodeWithTag("navigation.ask").assertIsSelected()
        val bounds = bar.fetchSemanticsNode().boundsInRoot
        val selector = onNodeWithTag("navigation.selector").fetchSemanticsNode().boundsInRoot
        assertTrue(selector.left >= bounds.left && selector.right <= bounds.right)
        runOnIdle { assertEquals(listOf("settings", "ask"), changes) }
    }
}

private fun SemanticsNodeInteraction.centerWithin(parent: SemanticsNodeInteraction): Offset =
    fetchSemanticsNode().boundsInRoot.center - parent.fetchSemanticsNode().boundsInRoot.topLeft
