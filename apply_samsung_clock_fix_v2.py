#!/usr/bin/env python3
"""Apply Samsung Clock timer support to Expressive Cutout 0.3.0-beta/dev.
Run at the repository root: python3 apply_samsung_clock_fix_v2.py
The script fails rather than editing a repository whose structure has changed.
"""
from pathlib import Path
import sys

ROOT = Path.cwd()
PACKAGE = ROOT / 'app/src/main/java/com/ekoehler/expressivecutout'
PARSER = PACKAGE / 'events/TimerNotificationParser.kt'
LISTENER = PACKAGE / 'service/CutoutNotificationListenerService.kt'
COMPAT = PACKAGE / 'events/SamsungClockTimerCompat.kt'
GRADLE = ROOT / 'app/build.gradle.kts'

COMPAT_SOURCE = '''package com.ekoehler.expressivecutout.events

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
    private const val PAUSED_TIME = """\\b(\\d{1,3}):([0-5]\\d)(?::([0-5]\\d))?\\b"""

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
'''


def replace_once(source: str, before: str, after: str, filename: str) -> str:
    count = source.count(before)
    if count != 1:
        raise ValueError(f'{filename}: expected exactly one matching block, found {count}')
    return source.replace(before, after, 1)


def prepare():
    original_parser = PARSER.read_text()
    original_listener = LISTENER.read_text()
    original_gradle = GRADLE.read_text()
    if COMPAT.exists():
        raise ValueError('SamsungClockTimerCompat.kt already exists; do not overwrite')

    parser = replace_once(
        original_parser,
        'import android.app.Notification\n',
        'import android.app.Notification\nimport android.content.Context\nimport com.ekoehler.expressivecutout.core.RunningTimer\n',
        str(PARSER),
    )
    parser = replace_once(
        parser,
        '''object TimerNotificationParser {

    /**
     * Whether this notification is a countdown timer''',
        '''object TimerNotificationParser {

    /** Reads any supported timer format, including Samsung Clock's OEM RemoteViews. */
    fun tryParse(
        sbn: StatusBarNotification,
        context: Context,
        previous: RunningTimer? = null,
    ): ParsedTimer? =
        if (isTimer(sbn)) parse(sbn) else SamsungClockTimerCompat.tryParse(sbn, context, previous)

    /**
     * Whether this notification is a countdown timer''',
        str(PARSER),
    )

    listener = replace_once(
        original_listener,
        'import com.ekoehler.expressivecutout.events.CallNotificationParser\n',
        'import com.ekoehler.expressivecutout.events.CallNotificationParser\nimport com.ekoehler.expressivecutout.events.ParsedTimer\n',
        str(LISTENER),
    )
    listener = replace_once(
        listener,
        '''        observeBehaviour()
        seedMediaArt()
''',
        '''        observeBehaviour()
        seedMediaArt()
        seedActiveTimers()
''',
        str(LISTENER),
    )
    listener = replace_once(
        listener,
        '''    /**
     * Clears the published state when the framework unbinds the listener''',
        '''    /** Rebuilds an already-running timer after the notification listener reconnects. */
    private fun seedActiveTimers() {
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        active.sortedBy { it.postTime }.forEach { sbn ->
            val timer = TimerNotificationParser.tryParse(sbn, this) ?: return@forEach
            handleTimer(sbn, timer)
        }
    }

    /**
     * Clears the published state when the framework unbinds the listener''',
        str(LISTENER),
    )
    listener = replace_once(
        listener,
        '''        if (TimerNotificationParser.isTimer(notification)) {
            handleTimer(notification)
            return
        }
''',
        '''        val previous = if (notification.key == currentTimerKey) RunningTimerBus.state.value else null
        val timer = TimerNotificationParser.tryParse(notification, this, previous)
        if (timer != null) {
            handleTimer(notification, timer)
            return
        }
''',
        str(LISTENER),
    )
    listener = replace_once(
        listener,
        '''    private fun handleTimer(sbn: StatusBarNotification) {
        val timer = TimerNotificationParser.parse(sbn)
''',
        '''    private fun handleTimer(sbn: StatusBarNotification, timer: ParsedTimer) {
''',
        str(LISTENER),
    )
    gradle = replace_once(
        original_gradle,
        '''    buildTypes {
        release {''',
        '''    buildTypes {
        debug {
            applicationIdSuffix = ".samsungtest"
        }
        release {''',
        str(GRADLE),
    )
    return [(PARSER, parser), (LISTENER, listener), (GRADLE, gradle), (COMPAT, COMPAT_SOURCE)]


def main():
    try:
        planned = prepare()
    except (OSError, ValueError) as exc:
        print(f'ERROR: {exc}', file=sys.stderr)
        return 1
    if '--check' in sys.argv:
        print('PASS: all expected source anchors were found; no files changed')
        return 0
    for path, content in planned:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        print(f'UPDATED: {path.relative_to(ROOT)}')
    print('Source changes applied. Build and device validation still required.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
