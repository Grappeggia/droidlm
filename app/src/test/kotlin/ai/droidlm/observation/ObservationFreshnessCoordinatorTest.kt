package ai.droidlm.observation

import ai.droidlm.portal.PortalState
import ai.droidlm.runtime.AccessibilityRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservationFreshnessCoordinatorTest {
    @Test
    fun returnsFreshWhenScreenHashChanges() = runBlocking {
        val clock = FakeClock(1_000L)
        val runtime = AccessibilityRuntime()
        runtime.recordEvent(observedAt = 1_000L)
        val coordinator = coordinator(runtime, clock)
        val previous = observation(screenHash = "before")
        val current = observation(screenHash = "after")

        val result = coordinator.awaitFreshObservation(previous, observe = { current })

        assertEquals(ObservationFreshness.FRESH_AFTER_MUTATION, result.freshness)
        assertEquals("screen_hash_changed", result.reason)
        assertTrue(result.isFresh)
        assertEquals(ObservationFreshness.FRESH_AFTER_MUTATION, result.observation?.freshness)
    }

    @Test
    fun returnsFreshWhenPackageChanges() = runBlocking {
        val clock = FakeClock(1_000L)
        val runtime = AccessibilityRuntime()
        runtime.recordEvent(observedAt = 1_000L)
        val coordinator = coordinator(runtime, clock)
        val previous = observation(packageName = "com.before", screenHash = "same")
        val current = observation(packageName = "com.after", screenHash = "same")

        val result = coordinator.awaitFreshObservation(previous, observe = { current })

        assertEquals(ObservationFreshness.FRESH_AFTER_MUTATION, result.freshness)
        assertEquals("package_or_activity_changed", result.reason)
    }

    @Test
    fun returnsFreshWhenTargetVerifierPasses() = runBlocking {
        val clock = FakeClock(1_000L)
        val runtime = AccessibilityRuntime()
        runtime.recordEvent(observedAt = 1_000L)
        val coordinator = coordinator(runtime, clock)
        val previous = observation(screenHash = "same")
        val current = observation(screenHash = "same", windowTitle = "Ready")

        val result = coordinator.awaitFreshObservation(
            previousObservation = previous,
            observe = { current },
            targetVerifier = { it.windowTitle == "Ready" }
        )

        assertEquals(ObservationFreshness.FRESH_AFTER_MUTATION, result.freshness)
        assertEquals("target_verifier_passed", result.reason)
    }

    @Test
    fun returnsSameScreenWhenEventStreamIsQuietWithoutDelta() = runBlocking {
        val clock = FakeClock(1_500L)
        val runtime = AccessibilityRuntime()
        runtime.recordEvent(observedAt = 1_000L)
        val coordinator = coordinator(runtime, clock)
        val previous = observation(screenHash = "same")
        val current = observation(screenHash = "same")

        val result = coordinator.awaitFreshObservation(previous, observe = { current })

        assertEquals(ObservationFreshness.SAME_SCREEN_NO_DELTA, result.freshness)
        assertEquals("event_stream_quiet_no_delta", result.reason)
        assertEquals(500L, result.quietForMs)
        assertFalse(result.isFresh)
    }

    @Test
    fun returnsUnstableWhenQuietObservationIsLoading() = runBlocking {
        val clock = FakeClock(1_500L)
        val runtime = AccessibilityRuntime()
        runtime.recordEvent(observedAt = 1_000L)
        val coordinator = coordinator(runtime, clock)
        val previous = observation(screenHash = "same")
        val current = observation(screenHash = "same", loadingLikely = true)

        val result = coordinator.awaitFreshObservation(previous, observe = { current })

        assertEquals(ObservationFreshness.LOADING_OR_UNSTABLE, result.freshness)
        assertEquals("event_stream_quiet_but_loading", result.reason)
        assertEquals(ObservationFreshness.LOADING_OR_UNSTABLE, result.observation?.freshness)
    }

    @Test
    fun timesOutAsStaleWhenEventsDoNotSettle() = runBlocking {
        val clock = FakeClock(1_000L)
        val runtime = AccessibilityRuntime()
        val coordinator = coordinator(runtime, clock, timeoutMs = 250L)
        val previous = observation(screenHash = "same")
        val current = observation(screenHash = "same")
        var observed = 0

        val result = coordinator.awaitFreshObservation(previous, observe = {
            runtime.recordEvent(observedAt = clock.now)
            observed += 1
            current
        })

        assertEquals(ObservationFreshness.STALE_AFTER_MUTATION, result.freshness)
        assertEquals("timeout_without_fresh_observation", result.reason)
        assertTrue(result.elapsedMs >= 250L)
        assertTrue(observed > 1)
    }

    @Test
    fun quietWithoutObservationReturnsUnknown() = runBlocking {
        val clock = FakeClock(1_500L)
        val runtime = AccessibilityRuntime()
        runtime.recordEvent(observedAt = 1_000L)
        val coordinator = coordinator(runtime, clock)

        val result = coordinator.awaitFreshObservation(previousObservation = null, observe = { null })

        assertEquals(ObservationFreshness.UNKNOWN, result.freshness)
        assertEquals("event_stream_quiet_without_observation", result.reason)
        assertNull(result.observation)
        assertNotNull(result.eventState.lastEvent)
    }

    private fun coordinator(
        runtime: AccessibilityRuntime,
        clock: FakeClock,
        timeoutMs: Long = 1_000L
    ): ObservationFreshnessCoordinator = ObservationFreshnessCoordinator(
        accessibilityRuntime = runtime,
        policy = ObservationFreshnessPolicy(quietWindowMs = 350L, pollIntervalMs = 50L, timeoutMs = timeoutMs),
        elapsedRealtimeMs = { clock.now },
        sleeper = { clock.advanceBy(it) }
    )

    private fun AccessibilityRuntime.recordEvent(observedAt: Long) {
        recordAccessibilityEvent(
            eventType = 1,
            packageName = "com.example",
            className = "ExampleActivity",
            contentChangeTypes = null,
            windowChangeTypes = null,
            eventTimeMs = observedAt,
            observedAtElapsedMs = observedAt
        )
    }

    private fun observation(
        packageName: String = "com.example",
        activityName: String? = "ExampleActivity",
        windowTitle: String? = null,
        screenHash: String = "hash",
        loadingLikely: Boolean = false,
        freshness: ObservationFreshness = ObservationFreshness.UNKNOWN
    ): ScreenObservation = ScreenObservation(
        observationId = "obs-$screenHash-$packageName-$activityName-$loadingLikely",
        timestampMs = 1_000L,
        packageName = packageName,
        activityName = activityName,
        windowTitle = windowTitle,
        screenHash = screenHash,
        keyboardVisible = false,
        dialogVisible = false,
        loadingLikely = loadingLikely,
        nodes = emptyList(),
        ocrBlocks = emptyList(),
        artifactContext = null,
        priorActionDelta = null,
        confidence = ObservationConfidence(0.8, listOf("test")),
        freshness = freshness
    )

    private class FakeClock(var now: Long) {
        fun advanceBy(ms: Long) {
            now += ms.coerceAtLeast(0L)
        }
    }
}
