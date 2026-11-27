package com.fruitblast.app.automation

import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fruitblast.automation.AdUiState
import com.fruitblast.automation.AdAction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the production AndroidNodeInfo adapter with framework-created nodes.
 *
 * Child queries from the app process require API 33+ and an attached View hierarchy.
 * These tests intentionally run on an API 33+ emulator/device; lower API coverage is
 * not inferred from this instrumentation suite.
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityNodeObserverInstrumentationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: AccessibilityNodeFixtureActivity

    @Before
    fun setUp() {
        assumeTrue(
            "Framework child queries from the app process require API 33+",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
        )

        val intent = Intent(
            instrumentation.context,
            AccessibilityNodeFixtureActivity::class.java,
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        activity = instrumentation.startActivitySync(intent) as AccessibilityNodeFixtureActivity
        instrumentation.waitForIdleSync()
    }

    @After
    fun tearDown() {
        if (::activity.isInitialized) {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    @Test
    fun observesFrameworkNodeTreeAndClassifiesCloseControl() {
        val observation = observe()

        assertEquals(AdUiState.PLAYABLE_AD_CANDIDATE, observation.state)
        assertTrue(observation.playableAdVisible)
        assertTrue(observation.closeVisible)
    }

    @Test
    fun nodeBudgetOverflowReturnsUnknownWithoutPartialClassification() {
        val observation = observe(maxNodes = 2)

        assertEquals(AdUiState.UNKNOWN, observation.state)
        assertFalse(observation.playableAdVisible)
        assertFalse(observation.closeVisible)
    }

    @Test
    fun depthBudgetOverflowReturnsUnknownWithoutPartialClassification() {
        val observation = observe(maxDepth = 0)

        assertEquals(AdUiState.UNKNOWN, observation.state)
        assertFalse(observation.playableAdVisible)
    }

    @Test
    fun elapsedTimeBudgetOverflowReturnsUnknownWithoutPartialClassification() {
        val observation = observe(maxDurationNanos = 1L)

        assertEquals(AdUiState.UNKNOWN, observation.state)
        assertFalse(observation.playableAdVisible)
    }

    @Test
    fun nullRootReturnsUnknown() {
        val observation = AccessibilityNodeObserver().observeAndRelease(null)

        assertEquals(AdUiState.UNKNOWN, observation.state)
        assertFalse(observation.playableAdVisible)
    }

    @Test
    fun repeatedObservationsUseFreshFrameworkNodes() {
        val first = observe()
        val second = observe()

        assertEquals(AdUiState.PLAYABLE_AD_CANDIDATE, first.state)
        assertEquals(AdUiState.PLAYABLE_AD_CANDIDATE, second.state)
        assertTrue(first.closeVisible)
        assertTrue(second.closeVisible)
    }


    @Test
    fun dispatcherClicksOnlyUniqueRevalidatedCloseNode() {
        val dispatcher = AccessibilityNodeClickDispatcher(
            observer = AccessibilityNodeObserver(maxDurationNanos = 1_000_000_000L),
            rootProvider = ::freshRoot,
            isTargetForegroundNow = { true },
        )

        assertTrue(dispatcher.dispatch(AdAction.CLOSE))
        instrumentation.waitForIdleSync()
        assertEquals(1, activity.closeClickCount)

        assertFalse(dispatcher.dispatch(AdAction.CLOSE))
        instrumentation.waitForIdleSync()
        assertEquals("Duplicate event must not dispatch the same control twice", 1, activity.closeClickCount)

        dispatcher.resetAfterCorrelatedRedirect()
        assertTrue(dispatcher.dispatch(AdAction.CLOSE))
        instrumentation.waitForIdleSync()
        assertEquals("A correlated redirect must permit one fresh retry after return", 2, activity.closeClickCount)
    }

    @Test
    fun dispatcherRejectsAmbiguousCloseControls() {
        instrumentation.runOnMainSync { activity.addDuplicateCloseControl() }
        instrumentation.waitForIdleSync()

        val dispatcher = AccessibilityNodeClickDispatcher(
            observer = AccessibilityNodeObserver(maxDurationNanos = 1_000_000_000L),
            rootProvider = ::freshRoot,
            isTargetForegroundNow = { true },
        )

        assertFalse(dispatcher.dispatch(AdAction.CLOSE))
        instrumentation.waitForIdleSync()
        assertEquals(0, activity.closeClickCount)
    }

    @Test
    fun dispatcherRejectsStaleOrWrongForegroundBeforeScanning() {
        val dispatcher = AccessibilityNodeClickDispatcher(
            observer = AccessibilityNodeObserver(maxDurationNanos = 1_000_000_000L),
            rootProvider = ::freshRoot,
            isTargetForegroundNow = { false },
        )

        assertFalse(dispatcher.dispatch(AdAction.CLOSE))
        instrumentation.waitForIdleSync()
        assertEquals(0, activity.closeClickCount)
    }

    private fun freshRoot(): AccessibilityNodeInfo? {
        var root: AccessibilityNodeInfo? = null
        instrumentation.runOnMainSync {
            root = activity.fixtureRoot.createAccessibilityNodeInfo()
            root?.setQueryFromAppProcessEnabled(activity.fixtureRoot, true)
        }
        return root
    }

    private fun observe(
        maxNodes: Int = 256,
        maxDepth: Int = 32,
        maxDurationNanos: Long = 1_000_000_000L,
    ) : com.fruitblast.automation.AdUiObservation {
        lateinit var rootNode: AccessibilityNodeInfo
        instrumentation.runOnMainSync {
            rootNode = activity.fixtureRoot.createAccessibilityNodeInfo()
            rootNode.setQueryFromAppProcessEnabled(activity.fixtureRoot, true)
        }

        return AccessibilityNodeObserver(
            maxNodes = maxNodes,
            maxDepth = maxDepth,
            maxDurationNanos = maxDurationNanos,
        ).observeAndRelease(rootNode)
    }
}
