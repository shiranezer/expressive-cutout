package com.ekoehler.expressivecutout.events

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import com.ekoehler.expressivecutout.core.CutoutSignal
import com.ekoehler.expressivecutout.core.DynamicTile
import com.ekoehler.expressivecutout.core.IslandEventBus
import com.ekoehler.expressivecutout.core.MediaProgress
import com.ekoehler.expressivecutout.core.MediaTransport
import com.ekoehler.expressivecutout.core.NowPlaying
import com.ekoehler.expressivecutout.core.NowPlayingBus
import com.ekoehler.expressivecutout.data.AppPreferences
import com.ekoehler.expressivecutout.data.DynamicTilePreferences
import com.ekoehler.expressivecutout.overlay.loadImageBitmapOrNull
import com.ekoehler.expressivecutout.overlay.toArtImageBitmap
import com.ekoehler.expressivecutout.permissions.Permissions
import com.ekoehler.expressivecutout.service.CutoutNotificationListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Watches the device's active media sessions and drives the music tile. It keeps [NowPlayingBus]
 * in sync with the current session (title, artist, album art, play/pause state and a transport
 * handle) and republishes a [CutoutSignal.Music] whenever playback starts or the track changes, so
 * the island pops up. Access to media sessions is granted by the app's already-required
 * notification-listener access — no extra permission is needed. Like [SystemEventMonitor], all
 * registration is dynamic and lives and dies with the hosting service.
 */
class MediaPlaybackMonitor(private val context: Context) {

    private val sessionManager = context.getSystemService<MediaSessionManager>()
    private val listenerComponent = ComponentName(context, CutoutNotificationListenerService::class.java)
    private val dynamicTilePreferences = DynamicTilePreferences(context)
    private val appPreferences = AppPreferences(context)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** Controllers we're currently watching, paired with the callback registered on each. */
    private val watched = mutableMapOf<MediaController, MediaController.Callback>()

    /** Enabled state of dynamic tiles */
    private var tileEnabled: Map<DynamicTile, Boolean> = emptyMap()

    /** Packages the user muted on the Apps screen; their sessions are ignored outright. */
    private var disabledApps: Set<String> = emptySet()

    /** The track last surfaced as a "show" signal, so we don't re-pop on every state tick. */
    private var lastShownKey: String? = null

    /** The pending "show" emission, held for [SHOW_DEBOUNCE_MS] so a start settles into one pop. */
    private var showJob: Job? = null

    /** Whether the active-sessions listener is registered with Android. */
    private var registered = false

    /** The bounded registration retry currently in flight, if any. */
    private var registrationJob: Job? = null

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            rebind(controllers.orEmpty())
        }

    /**
     * Begins watching the active media sessions and the tile's own enabled flag. Registration waits
     * for notification access when it has not been granted yet.
     */
    fun start() {
        val manager = sessionManager
        if (manager == null) {
            Log.w(TAG, "MediaSessionManager is unavailable; music monitoring cannot start")
            return
        }
        scope.launch {
            dynamicTilePreferences.enabled.collect { enabled ->
                tileEnabled = enabled
                sync()
            }
        }
        // Muting an app mid-playback should drop its tile straight away, so re-sync on every change.
        scope.launch {
            appPreferences.disabledPackages.collect { disabled ->
                disabledApps = disabled
                sync()
            }
        }
        scope.launch {
            CutoutNotificationListenerService.bound.collect { bound ->
                val accessGranted = Permissions.isNotificationAccessGranted(context)
                Log.i(TAG, "Notification listener bound=$bound accessGranted=$accessGranted")
                if (shouldRegisterMediaSessions(bound, accessGranted)) {
                    requestRegistration(manager)
                } else {
                    registrationJob?.cancel()
                    registrationJob = null
                    unregister(manager)
                }
            }
        }
    }

    /**
     * Unregisters every session callback and clears the published state, so a disabled tile leaves
     * nothing behind on the island.
     */
    fun stop() {
        registrationJob?.cancel()
        registrationJob = null
        sessionManager?.let(::unregister)
        scope.coroutineContext.cancelChildren()
        clearPendingShow()
        NowPlayingBus.update(null)
    }

    /**
     * Starts one bounded retry sequence. A listener can be enabled while its service is unbound, and
     * Android still authorises media-session access in that state, so
     * [CutoutNotificationListenerService.bound] is a retry signal rather than an access gate.
     */
    private fun requestRegistration(manager: MediaSessionManager) {
        if (registered || registrationJob?.isActive == true) return
        registrationJob = scope.launch {
            val success = retryMediaSessionRegistration(REGISTRATION_RETRY_DELAYS_MS) { attempt ->
                register(manager, attempt)
            }
            if (!success) {
                Log.w(TAG, "Media session registration exhausted ${REGISTRATION_RETRY_DELAYS_MS.size} attempts")
            }
        }
    }

    /** Tries to register once, cleaning up a partially-added listener before a later retry. */
    private fun register(manager: MediaSessionManager, attempt: Int): Boolean {
        if (registered) return true
        return runCatching {
            manager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent)
            val controllers = manager.getActiveSessions(listenerComponent)
            registered = true
            rebind(controllers)
            Log.i(
                TAG,
                "Media sessions registered on attempt=$attempt active=${controllers.size} " +
                    "packages=${controllers.joinToString { it.packageName }}",
            )
            true
        }.onFailure { error ->
            registered = false
            runCatching { manager.removeOnActiveSessionsChangedListener(sessionsListener) }
            Log.w(TAG, "Media session registration attempt=$attempt failed", error)
        }.getOrDefault(false)
    }

    private fun unregister(manager: MediaSessionManager) {
        if (!registered) return
        runCatching { manager.removeOnActiveSessionsChangedListener(sessionsListener) }
            .onFailure { Log.w(TAG, "Failed to unregister media session listener", it) }
        registered = false
        watched.forEach { (controller, callback) -> controller.unregisterCallback(callback) }
        watched.clear()
        NowPlayingBus.update(null)
        clearPendingShow()
        Log.i(TAG, "Media sessions unregistered")
    }

    /** Forgets the surfaced track and drops any pop still waiting to fire. */
    private fun clearPendingShow() {
        showJob?.cancel()
        showJob = null
        lastShownKey = null
    }

    /** Attach callbacks to newly active sessions and detach ones that have gone away. */
    private fun rebind(controllers: List<MediaController>) {
        Log.d(
            TAG,
            "Active sessions changed count=${controllers.size} states=" +
                controllers.joinToString { "${it.packageName}:${it.playbackState?.state ?: "none"}" },
        )
        val current = controllers.toSet()
        watched.keys.filter { it !in current }.toList().forEach(::detach)

        controllers.filter { it !in watched }.forEach { controller ->
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = sync()
                override fun onMetadataChanged(metadata: MediaMetadata?) = sync()
                override fun onSessionDestroyed() = detach(controller)
            }
            watched[controller] = callback
            controller.registerCallback(callback)
        }
        sync()
    }

    /** Stops watching one controller and re-syncs, for a session that has gone away. */
    private fun detach(controller: MediaController) {
        watched.remove(controller)?.let { controller.unregisterCallback(it) }
        sync()
    }

    /**
     * Whether the package is a voice assistant. Assistants publish a media session for their own
     * chime, which would otherwise pop a music tile for a sound the user never started.
     */
    private fun isAssistantPackage(packageName: String): Boolean {
        val pkg = packageName.lowercase()
        return pkg == "com.google.android.googlequicksearchbox" ||
            pkg == "com.google.android.apps.googleassistant" ||
            pkg == "com.google.android.apps.bard" ||
            pkg == "com.samsung.android.bixby.agent" ||
            pkg == "com.samsung.android.bixby.service" ||
            pkg == "com.amazon.dee.app" ||
            pkg == "com.openai.chatgpt" ||
            pkg == "com.microsoft.copilot" ||
            pkg.contains("assistant") ||
            pkg.contains("bixby") ||
            pkg.contains("gemini")
    }

    /**
     * Recompute the surfaced session: prefer one that's actually playing, else any active one.
     * Publishes its live state to [NowPlayingBus] and pops the island when a new track starts.
     */
    private fun sync() {
        val disabledControllers = watched.keys.filter { it.packageName in disabledApps }
        val assistantControllers = watched.keys.filter { isAssistantPackage(it.packageName) }
        val validControllers = watched.keys.filter { controller ->
            isEligibleMusicSession(
                isDisabled = controller in disabledControllers,
                isAssistant = controller in assistantControllers,
            )
        }

        val primary = validControllers.firstOrNull { it.isPlaying } ?: validControllers.firstOrNull()
        Log.d(
            TAG,
            "Session selection watched=${watched.size} eligible=${validControllers.size} " +
                "disabled=${disabledControllers.joinToString { it.packageName }} " +
                "assistants=${assistantControllers.joinToString { it.packageName }} " +
                "musicTileEnabled=${tileEnabled[DynamicTile.MUSIC] != false} " +
                "primary=${primary?.packageName ?: "none"} state=${primary?.playbackState?.state ?: "none"}",
        )
        if (primary == null) {
            NowPlayingBus.update(null)
            clearPendingShow()
            return
        }

        val playing = primary.isPlaying
        val metadata = primary.metadata

        val rawTitle = metadata?.getText(MediaMetadata.METADATA_KEY_TITLE)?.toString()
            ?: metadata?.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)?.toString()
        val rawArtist = metadata?.getText(MediaMetadata.METADATA_KEY_ARTIST)?.toString()
            ?: metadata?.getText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)?.toString()
            ?: metadata?.getText(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)?.toString()
            ?: metadata?.getText(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION)?.toString()
            ?: metadata?.getText(MediaMetadata.METADATA_KEY_ALBUM)?.toString()
            ?: metadata?.getText(MediaMetadata.METADATA_KEY_AUTHOR)?.toString()

        var title = rawTitle
        var artist = rawArtist

        if (isAssistantPackage(primary.packageName)) {
            // Assistant sessions are handled exclusively via NotificationListenerService
            NowPlayingBus.update(null)
            clearPendingShow()
            return
        }

        val trackArt = metadata?.trackArt()
        val albumBackgroundArt = metadata?.albumBackgroundArt() ?: trackArt

        NowPlayingBus.update(
            NowPlaying(
                packageName = primary.packageName,
                title = title,
                artist = artist,
                albumArt = trackArt,
                albumBackgroundArt = albumBackgroundArt,
                isPlaying = playing,
                transport = ControllerTransport(primary),
                progress = primary.progress(metadata, playing),
            ),
        )

        // Pop the island when a fresh track begins playing; reset when paused so a resume re-pops.
        if (!playing) {
            clearPendingShow()
            return
        }
        val key = "${primary.packageName}|$title|$artist"
        if (key == lastShownKey) return
        lastShownKey = key
        Log.i(
            TAG,
            "Scheduling music signal package=${primary.packageName} " +
                "hasTitle=${title != null} hasArtist=${artist != null}",
        )
        // Held briefly rather than emitted here: players routinely report STATE_PLAYING a tick or two
        // before publishing the track, so the same start arrives first as "no metadata" and then as
        // the real title — two different keys, which read as two tracks starting and would leave the
        // island showing the same tile twice. Waiting for the metadata to settle collapses that into
        // one pop carrying the final track, while a genuine track change is still its own pop.
        val signal = CutoutSignal.Music(
            packageName = primary.packageName,
            title = title,
            artist = artist,
            contentIntent = primary.sessionActivity,
        )
        showJob?.cancel()
        showJob = scope.launch {
            delay(SHOW_DEBOUNCE_MS)
            Log.i(TAG, "Emitting music signal package=${signal.packageName}")
            IslandEventBus.emit(signal)
        }
    }

    private val MediaController.isPlaying: Boolean
        get() = playbackState?.state == PlaybackState.STATE_PLAYING

    /**
     * The session's position anchor. [PlaybackState.getPosition] is a sample taken at
     * [PlaybackState.getLastPositionUpdateTime], not a live figure — it is passed through as-is and
     * the tile extrapolates. Players that publish no duration, or a negative one for a live stream,
     * yield a null length and an indeterminate bar. A state carrying [PlaybackState.PLAYBACK_POSITION_UNKNOWN]
     * gives no anchor at all.
     */
    private fun MediaController.progress(metadata: MediaMetadata?, playing: Boolean): MediaProgress? {
        val state = playbackState ?: return null
        if (state.position == PlaybackState.PLAYBACK_POSITION_UNKNOWN) return null

        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0L }
        // A paused session keeps its position but must not creep; a player reporting a nonsense
        // speed while playing is treated as ordinary 1x rather than freezing the bar.
        val speed = if (playing) state.playbackSpeed.takeIf { it > 0f } ?: 1f else 0f
        return MediaProgress(
            positionMs = state.position.coerceAtLeast(0L),
            durationMs = duration,
            speed = speed,
            anchorUptimeMs = state.lastPositionUpdateTime.takeIf { it > 0L } ?: SystemClock.elapsedRealtime(),
        )
    }

    /**
     * The cover the session itself carries: a bitmap if the player published one, else a URI we can
     * read locally. A player pointing at a remote CDN (Spotify) yields null here and the tile falls
     * back to the cover lifted off its media notification — see
     * [com.ekoehler.expressivecutout.core.MediaArtBus].
     */
    private fun MediaMetadata.trackArt(): ImageBitmap? = (
        getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        )?.toArtImageBitmap()
        ?: trackArtUri()?.loadImageBitmapOrNull(context)
        ?: albumArtUri()?.loadImageBitmapOrNull(context)

    private fun MediaMetadata.albumBackgroundArt(): ImageBitmap? = (
        getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        )?.toArtImageBitmap()
        ?: albumArtUri()?.loadImageBitmapOrNull(context)

    private fun MediaMetadata.trackArtUri(): Uri? = listOf(
        MediaMetadata.METADATA_KEY_ART_URI,
        MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
        MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
    ).firstNotNullOfOrNull { key -> getString(key)?.takeIf { it.isNotBlank() } }
        ?.let { runCatching { it.toUri() }.getOrNull() }

    private fun MediaMetadata.albumArtUri(): Uri? = getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { it.toUri() }.getOrNull() }

    /** Bridges the tile's transport buttons to the active session's controls. */
    private class ControllerTransport(private val controller: MediaController) : MediaTransport {
        override fun previous() {
            runCatching { controller.transportControls.skipToPrevious() }
        }

        override fun playPause() {
            runCatching {
                if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                    controller.transportControls.pause()
                } else {
                    controller.transportControls.play()
                }
            }
        }

        override fun next() {
            runCatching { controller.transportControls.skipToNext() }
        }
    }

    private companion object {
        const val TAG = "MediaPlaybackMonitor"

        /**
         * How long a new track is held before it pops the island, letting a session that reports its
         * playback state and its metadata in separate ticks settle into a single signal. Short enough
         * that a real track change still feels immediate.
         */
        const val SHOW_DEBOUNCE_MS = 250L

        /** Registration attempts: immediate, then three short retries for framework bind races. */
        val REGISTRATION_RETRY_DELAYS_MS = listOf(0L, 250L, 1_000L, 3_000L)
    }
}
