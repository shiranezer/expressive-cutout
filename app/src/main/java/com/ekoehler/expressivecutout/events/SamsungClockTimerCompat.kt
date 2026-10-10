package com.ekoehler.expressivecutout.events

import android.app.Notification
import android.content.Context
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.RemoteViews
import com.ekoehler.expressivecutout.core.CutoutSignal
import com.ekoehler.expressivecutout.core.RunningTimer
import java.util.Locale

/**
 * Decodes Samsung Clock's proprietary timer notification through Android's public view APIs.
 * Paused notifications may not contain a running count-down Chronometer; their action state
 * and the previous timer snapshot provide a safe way to freeze the displayed time.
 */
internal object SamsungClockTimerCompat {
    private const val TAG = "SamsungClockTimer"
    private const val CLOCK_PACKAGE = "com.sec.android.app.clockpackage"
    private const val TIMER_CHANNEL = "notification_channel_timer"
    private const val REMOTE_CHRONOMETER = "android.ongoingActivityNoti.chronometerRemoteView"
    private const val PAUSED_TIME = """\b(\d{1,3}):([0-5]\d)(?::([0-5]\d))?\b"""

    /** Returns a Samsung timer state, or null if this notification cannot be safely read. */
    fun tryParse(
        sbn: StatusBarNotification,
        context: Context,
        previous: RunningTimer? = null,
    ): ParsedTimer? {
        val notification = sbn.notification
        if (sbn.packageName != CLOCK_PACKAGE || notification.channelId != TIMER_CHANNEL) return null
        if (notification.flags and Notification.FLAG_ONGOING_EVENT == 0) return null
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val extras = notification.extras ?: return null

        val actions = notification.actions.orEmpty().mapNotNull { action ->
            val label = action.title?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val intent = action.actionIntent ?: return@mapNotNull null
            CutoutSignal.Notification.Action(label, intent)
        }
        val hasPause = notification.actions.orEmpty().any { action ->
            action.title?.toString()?.lowercase(Locale.ROOT)?.let { title ->
                title.contains("pause") || title.contains("השהה")
            } == true
        }
        val hasResume = notification.actions.orEmpty().any { action ->
            action.title?.toString()?.lowercase(Locale.ROOT)?.let { title ->
                title.contains("resume") || title.contains("continue") || title.contains("המשך")
            } == true
        }
        if (hasPause == hasResume) {
            Log.d(TAG, "Samsung timer ignored: Pause/Resume action state unavailable")
            return null
        }

        val label = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?.takeIf { it.isNotBlank() }
        val elapsedNow = SystemClock.elapsedRealtime()
        @Suppress("DEPRECATION")
        val remote = runCatching { extras.getParcelable<RemoteViews>(REMOTE_CHRONOMETER) }
            .getOrNull()
        val root = remote?.let {
            runCatching { it.apply(context, FrameLayout(context)) }
                .onFailure { error -> Log.w(TAG, "Samsung timer RemoteViews inflation failed", error) }
                .getOrNull()
        }
        val chronometers = mutableListOf<Chronometer>()
        if (root != null) collectChronometers(root, chronometers)
        val uniqueChronometer = chronometers.singleOrNull()
        val base = uniqueChronometer?.base?.takeIf { it > 0L }
        val frozenText = uniqueChronometer?.text?.toString()
        val runningBase = chronometers.singleOrNull { it.isCountDown && it.base > 0L }?.base
        chronometers.forEach { it.stop() }

        if (hasResume) {
            // Samsung may turn off the countdown view on Pause; freeze the last known deadline.
            val frozen = parseDisplayedTime(frozenText)?.takeIf { it > 0L }
                ?: previous?.endElapsedRealtimeMs?.let { (it - elapsedNow).coerceAtLeast(0L) }
                ?: previous?.pausedRemainingMs
                ?: base?.let { (it - elapsedNow).coerceAtLeast(0L) }
                ?: return null
            return ParsedTimer(
                endElapsedRealtimeMs = null,
                pausedRemainingMs = frozen,
                label = label,
                actions = actions,
            )
        }

        val deadline = runningBase ?: return null
        return ParsedTimer(
            endElapsedRealtimeMs = deadline,
            pausedRemainingMs = null,
            label = label,
            actions = actions,
        )
    }

    /** Finds all Chronometers in a detached RemoteViews hierarchy. */
    private fun collectChronometers(view: View, result: MutableList<Chronometer>) {
        if (view is Chronometer) result.add(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) collectChronometers(view.getChildAt(i), result)
        }
    }

    /** Parses a paused timer's remaining-time text as M:SS or H:MM:SS. */
    private fun parseDisplayedTime(text: String?): Long? {
        val parts = Regex(PAUSED_TIME).find(text.orEmpty())?.value?.split(':') ?: return null
        return parts.fold(0L) { total, part -> total * 60L + part.toLong() } * 1_000L
    }
}
