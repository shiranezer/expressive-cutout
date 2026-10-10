package com.ekoehler.expressivecutout.events

import kotlinx.coroutines.delay

/**
 * Whether media registration should be active. An enabled notification-listener grant is enough
 * even while Android has not bound its service; a live binding also proves access on OEMs whose
 * settings query lags behind the callback.
 */
internal fun shouldRegisterMediaSessions(listenerBound: Boolean, accessGranted: Boolean): Boolean =
    listenerBound || accessGranted

/**
 * Runs a bounded series of media-session registration attempts. The first delay is normally zero;
 * later delays let Android finish binding a newly enabled notification listener without leaving
 * playback dead until the accessibility service restarts.
 */
internal suspend fun retryMediaSessionRegistration(
    retryDelaysMs: List<Long>,
    pause: suspend (Long) -> Unit = { delay(it) },
    attempt: (Int) -> Boolean,
): Boolean {
    require(retryDelaysMs.isNotEmpty())
    require(retryDelaysMs.all { it >= 0L })

    retryDelaysMs.forEachIndexed { index, delayMs ->
        if (delayMs > 0L) pause(delayMs)
        if (attempt(index + 1)) return true
    }
    return false
}
