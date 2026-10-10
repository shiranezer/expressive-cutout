package com.ekoehler.expressivecutout.events

/**
 * Whether an active media controller may drive the music tile. Muted apps stay out, and assistant
 * sessions always stay out because the assistant tile owns them through its separate event path.
 */
internal fun isEligibleMusicSession(isDisabled: Boolean, isAssistant: Boolean): Boolean =
    !isDisabled && !isAssistant
