package ai.droidlm.observation

import ai.droidlm.runtime.AccessibilityEventStreamState
import ai.droidlm.runtime.AccessibilityRuntime
import android.os.SystemClock
import kotlinx.coroutines.delay

data class ObservationFreshnessPolicy(
    val quietWindowMs: Long = 350L,
    val pollIntervalMs: Long = 80L,
    val timeoutMs: Long = 2_500L
)

data class ObservationFreshnessResult(
    val observation: ScreenObservation?,
    val freshness: ObservationFreshness,
    val reason: String,
    val elapsedMs: Long,
    val quietForMs: Long?,
    val eventState: AccessibilityEventStreamState
) {
    val isFresh: Boolean
        get() = freshness == ObservationFreshness.FRESH_AFTER_MUTATION
}

class ObservationFreshnessCoordinator(
    private val accessibilityRuntime: AccessibilityRuntime,
    private val policy: ObservationFreshnessPolicy = ObservationFreshnessPolicy(),
    private val elapsedRealtimeMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val sleeper: suspend (Long) -> Unit = { delay(it) }
) {
    suspend fun awaitFreshObservation(
        previousObservation: ScreenObservation?,
        observe: suspend () -> ScreenObservation?,
        targetVerifier: ((ScreenObservation) -> Boolean)? = null,
        timeoutMs: Long = policy.timeoutMs
    ): ObservationFreshnessResult {
        val startedAt = elapsedRealtimeMs()
        var lastObservation: ScreenObservation? = null
        var lastEventState = accessibilityRuntime.eventState.value
        while (true) {
            val now = elapsedRealtimeMs()
            lastEventState = accessibilityRuntime.eventState.value
            val quietForMs = lastEventState.quietForMs(now)
            val observation = runCatching { observe() }.getOrNull()
            if (observation != null) {
                lastObservation = observation
                val verifierPassed = targetVerifier?.invoke(observation) == true
                val packageChanged = previousObservation != null &&
                    (previousObservation.packageName != observation.packageName || previousObservation.activityName != observation.activityName)
                val screenHashChanged = previousObservation != null && previousObservation.screenHash != observation.screenHash
                when {
                    verifierPassed -> return result(
                        observation,
                        ObservationFreshness.FRESH_AFTER_MUTATION,
                        "target_verifier_passed",
                        startedAt,
                        quietForMs,
                        lastEventState
                    )
                    packageChanged -> return result(
                        observation,
                        ObservationFreshness.FRESH_AFTER_MUTATION,
                        "package_or_activity_changed",
                        startedAt,
                        quietForMs,
                        lastEventState
                    )
                    screenHashChanged -> return result(
                        observation,
                        ObservationFreshness.FRESH_AFTER_MUTATION,
                        "screen_hash_changed",
                        startedAt,
                        quietForMs,
                        lastEventState
                    )
                    quietForMs != null && quietForMs >= policy.quietWindowMs -> return result(
                        observation,
                        if (observation.loadingLikely) ObservationFreshness.LOADING_OR_UNSTABLE else ObservationFreshness.SAME_SCREEN_NO_DELTA,
                        if (observation.loadingLikely) "event_stream_quiet_but_loading" else "event_stream_quiet_no_delta",
                        startedAt,
                        quietForMs,
                        lastEventState
                    )
                }
            } else if (quietForMs != null && quietForMs >= policy.quietWindowMs) {
                return result(
                    null,
                    ObservationFreshness.UNKNOWN,
                    "event_stream_quiet_without_observation",
                    startedAt,
                    quietForMs,
                    lastEventState
                )
            }

            if (now - startedAt >= timeoutMs) {
                val freshness = if (lastObservation?.loadingLikely == true) {
                    ObservationFreshness.LOADING_OR_UNSTABLE
                } else {
                    ObservationFreshness.STALE_AFTER_MUTATION
                }
                val reason = if (lastObservation?.loadingLikely == true) {
                    "timeout_with_loading_observation"
                } else {
                    "timeout_without_fresh_observation"
                }
                return result(lastObservation, freshness, reason, startedAt, quietForMs, lastEventState)
            }

            val remainingMs = timeoutMs - (now - startedAt)
            sleeper(policy.pollIntervalMs.coerceAtMost(remainingMs.coerceAtLeast(1L)))
        }
    }

    private fun result(
        observation: ScreenObservation?,
        freshness: ObservationFreshness,
        reason: String,
        startedAt: Long,
        quietForMs: Long?,
        eventState: AccessibilityEventStreamState
    ): ObservationFreshnessResult {
        val elapsedMs = (elapsedRealtimeMs() - startedAt).coerceAtLeast(0L)
        return ObservationFreshnessResult(
            observation = observation?.copy(freshness = freshness),
            freshness = freshness,
            reason = reason,
            elapsedMs = elapsedMs,
            quietForMs = quietForMs,
            eventState = eventState
        )
    }
}
