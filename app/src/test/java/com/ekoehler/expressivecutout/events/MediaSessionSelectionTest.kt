package com.ekoehler.expressivecutout.events

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests which active sessions may compete to become the music tile's primary controller. */
class MediaSessionSelectionTest {

    /** An ordinary enabled player remains eligible. */
    @Test
    fun ordinaryPlayerIsEligible() {
        assertTrue(isEligibleMusicSession(isDisabled = false, isAssistant = false))
    }

    /** A user-muted player cannot drive the music tile. */
    @Test
    fun disabledPlayerIsRejected() {
        assertFalse(isEligibleMusicSession(isDisabled = true, isAssistant = false))
    }

    /** Assistant sessions cannot mask an ordinary local player. */
    @Test
    fun assistantSessionIsRejected() {
        assertFalse(isEligibleMusicSession(isDisabled = false, isAssistant = true))
    }
}
