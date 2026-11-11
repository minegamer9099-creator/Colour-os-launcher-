// Location: app/src/main/java/com/example/simplelauncher/MediaMonitor.kt
package com.example.simplelauncher

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.view.KeyEvent
import androidx.core.app.NotificationManagerCompat

/**
 * Finds the media app that is currently playing (Spotify, YouTube Music, etc.),
 * reports its title / artist / artwork / play state, and sends play/pause/next/previous.
 *
 * Reading other apps' media sessions requires "Notification access" (see FluidNotificationListener).
 * Without it we can still SEND play/pause/next/previous using media key events,
 * but we can't SHOW what is playing.
 */
class MediaMonitor(
    private val context: Context,
    private val onStateChanged: (MediaState) -> Unit
) {

    data class MediaState(
        val title: String? = null,
        val artist: String? = null,
        val art: Bitmap? = null,
        val isPlaying: Boolean = false
    )

    private val sessionManager =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val listenerComponent = ComponentName(context, FluidNotificationListener::class.java)

    private var controller: MediaController? = null
    private var started = false

    // Called when a media app starts or stops a session
    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> attach(pick(list)) }

    // Called when the song or play state changes
    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() {
            attach(pick(try { sessionManager.getActiveSessions(listenerComponent) } catch (e: SecurityException) { null }))
        }
    }

    fun hasAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /** Returns true if we are now connected to media sessions. Safe to call repeatedly. */
    fun start(): Boolean {
        if (started) return true
        if (!hasAccess()) return false
        return try {
            sessionManager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent)
            started = true
            attach(pick(sessionManager.getActiveSessions(listenerComponent)))
            true
        } catch (e: SecurityException) {
            false
        }
    }

    fun stop() {
        controller?.unregisterCallback(controllerCallback)
        controller = null
        if (started) {
            sessionManager.removeOnActiveSessionsChangedListener(sessionsListener)
            started = false
        }
    }

    // Prefer the session that is actually playing right now
    private fun pick(list: List<MediaController>?): MediaController? =
        list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list?.firstOrNull()

    private fun attach(newController: MediaController?) {
        controller?.unregisterCallback(controllerCallback)
        controller = newController
        controller?.registerCallback(controllerCallback)
        publish()
    }

    private fun publish() {
        val meta = controller?.metadata
        onStateChanged(
            MediaState(
                title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE),
                artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                art = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
                isPlaying = controller?.playbackState?.state == PlaybackState.STATE_PLAYING
            )
        )
    }

    // ---- Controls ----

    fun playPause() {
        val c = controller
        if (c == null) {
            sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        } else if (c.playbackState?.state == PlaybackState.STATE_PLAYING) {
            c.transportControls.pause()
        } else {
            c.transportControls.play()
        }
    }

    fun next() = controller?.transportControls?.skipToNext() ?: sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)

    fun previous() = controller?.transportControls?.skipToPrevious() ?: sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)

    /** Fallback: acts like pressing a headset button. */
    private fun sendMediaKey(keyCode: Int) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }
}
