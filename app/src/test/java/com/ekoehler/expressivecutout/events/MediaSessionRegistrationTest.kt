package com.ekoehler.expressivecutout.events

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests the bounded retry policy used while attaching to Android's active media sessions. */
class MediaSessionRegistrationTest {

    /** Enabled access registers media even when the notification-listener service is stale. */
    @Test
    fun enabledAccessDoesNotRequireALiveListenerBinding() {
        assertTrue(shouldRegisterMediaSessions(listenerBound = false, accessGranted = true))
    }

    /** A live listener binding remains sufficient if the settings query temporarily lags. */
    @Test
    fun liveBindingProvesAccess() {
        assertTrue(shouldRegisterMediaSessions(listenerBound = true, accessGranted = false))
    }

    /** Registration stays off only when neither the grant nor a binding is present. */
    @Test
    fun missingGrantAndBindingDoNotRegister() {
        assertFalse(shouldRegisterMediaSessions(listenerBound = false, accessGranted = false))
    }

    /** A successful first attempt does not wait or run later attempts. */
    @Test
    fun succeedsImmediatelyWithoutWaiting() {
        runBlocking {
            val pauses = mutableListOf<Long>()
            val attempts = mutableListOf<Int>()

            val registered = retryMediaSessionRegistration(
                retryDelaysMs = listOf(0L, 250L, 1_000L),
                pause = { pauses.add(it) },
                attempt = { number -> attempts.add(number) },
            )

            assertTrue(registered)
            assertEquals(listOf(1), attempts)
            assertTrue(pauses.isEmpty())
        }
    }

    /** A transient framework race is retried according to the supplied delay plan. */
    @Test
    fun retriesAfterTransientFailuresUntilRegistrationSucceeds() {
        runBlocking {
            val pauses = mutableListOf<Long>()
            val attempts = mutableListOf<Int>()

            val registered = retryMediaSessionRegistration(
                retryDelaysMs = listOf(0L, 250L, 1_000L, 3_000L),
                pause = { pauses.add(it) },
                attempt = { number ->
                    attempts.add(number)
                    number == 3
                },
            )

            assertTrue(registered)
            assertEquals(listOf(1, 2, 3), attempts)
            assertEquals(listOf(250L, 1_000L), pauses)
        }
    }

    /** Permanent failure stops at the end of the bounded plan instead of looping forever. */
    @Test
    fun stopsAfterTheBoundedAttemptPlanIsExhausted() {
        runBlocking {
            val pauses = mutableListOf<Long>()
            var attempts = 0

            val registered = retryMediaSessionRegistration(
                retryDelaysMs = listOf(0L, 100L, 500L),
                pause = { pauses.add(it) },
                attempt = {
                    attempts += 1
                    false
                },
            )

            assertFalse(registered)
            assertEquals(3, attempts)
            assertEquals(listOf(100L, 500L), pauses)
        }
    }

    /** An empty plan is rejected because it could never make an initial registration attempt. */
    @Test(expected = IllegalArgumentException::class)
    fun rejectsAnEmptyAttemptPlan() {
        runBlocking {
            retryMediaSessionRegistration(emptyList()) { true }
        }
    }
}
