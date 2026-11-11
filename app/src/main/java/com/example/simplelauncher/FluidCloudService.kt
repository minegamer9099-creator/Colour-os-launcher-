// Location: app/src/main/java/com/example/simplelauncher/FluidCloudService.kt
package com.example.simplelauncher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.CountDownTimer
import android.os.IBinder
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import java.util.Locale

/**
 * Keeps the Fluid Cloud capsule on screen, on top of every app, at the top centre.
 *
 * It does three jobs:
 *   1. Run as a foreground service so Android doesn't kill it.
 *   2. Add the capsule view to the screen through WindowManager (an "overlay window").
 *   3. Feed the capsule with data: media state and a simple countdown timer.
 */
class FluidCloudService : Service() {

    companion object {
        private const val CHANNEL_ID = "fluid_cloud"
        private const val NOTIFICATION_ID = 1001
        private const val DEFAULT_TIMER_MS = 5 * 60_000L   // 5 minutes
    }

    private lateinit var windowManager: WindowManager
    private var rootView: View? = null
    private var media: MediaMonitor? = null

    // Views
    private lateinit var capsule: FluidCapsuleView
    private lateinit var tvTitle: TextView
    private lateinit var tvArtist: TextView
    private lateinit var imgAlbum: ImageView
    private lateinit var btnPlayPause: ImageButton
    private lateinit var dotMedia: View
    private lateinit var tvTimer: TextView
    private lateinit var tvCollapsedTimer: TextView
    private lateinit var btnTimerToggle: TextView

    // Timer state
    private var countDown: CountDownTimer? = null
    private var timerRemainingMs = DEFAULT_TIMER_MS
    private var timerRunning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must be called quickly after startForegroundService(), so do it first
        startAsForeground()

        // Without the "draw over other apps" permission there is nothing we can do
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (rootView == null) addOverlay()
        return START_STICKY   // ask Android to restart us if it ever kills the service
    }

    // ------------------------------------------------------------------------------------
    // Foreground notification
    // ------------------------------------------------------------------------------------

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Fluid Cloud", NotificationManager.IMPORTANCE_MIN)
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Fluid Cloud is active")
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ------------------------------------------------------------------------------------
    // The overlay window
    // ------------------------------------------------------------------------------------

    private fun addOverlay() {
        val themed = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault)
        val view = LayoutInflater.from(themed).inflate(R.layout.view_fluid_cloud, null)
        rootView = view

        bindViews(view)
        setupMedia()
        setupTimer()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,      // window grows/shrinks with the capsule
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or        // never steal keyboard focus
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or  // touches outside pass through
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (8 * resources.displayMetrics.density).toInt()    // small gap from the top edge
            title = "FluidCloud"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Allow drawing in the camera-cutout area
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        windowManager.addView(view, params)
    }

    private fun bindViews(v: View) {
        capsule = v.findViewById(R.id.capsule)
        tvTitle = v.findViewById(R.id.tvTitle)
        tvArtist = v.findViewById(R.id.tvArtist)
        imgAlbum = v.findViewById(R.id.imgAlbumArt)
        btnPlayPause = v.findViewById(R.id.btnPlayPause)
        dotMedia = v.findViewById(R.id.dotMedia)
        tvTimer = v.findViewById(R.id.tvTimer)
        tvCollapsedTimer = v.findViewById(R.id.tvCollapsedTimer)
        btnTimerToggle = v.findViewById(R.id.btnTimerToggle)
    }

    // ------------------------------------------------------------------------------------
    // Media
    // ------------------------------------------------------------------------------------

    private fun setupMedia() {
        val monitor = MediaMonitor(this) { state -> updateMediaUi(state) }
        media = monitor

        rootView!!.findViewById<View>(R.id.btnPrev).setOnClickListener { monitor.previous() }
        rootView!!.findViewById<View>(R.id.btnNext).setOnClickListener { monitor.next() }
        btnPlayPause.setOnClickListener { monitor.playPause() }

        // If media access isn't granted yet, tapping the title opens the settings screen for it
        val openAccessIfNeeded = View.OnClickListener {
            if (!monitor.hasAccess()) {
                startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            capsule.collapse()
        }
        tvTitle.setOnClickListener(openAccessIfNeeded)
        tvArtist.setOnClickListener(openAccessIfNeeded)

        // Each time the capsule opens, try to connect (the user may have just granted access)
        // start() does nothing if already connected; if it connects just now, it pushes the
        // current song straight into updateMediaUi().
        capsule.onExpandedChanged = { expanded ->
            if (expanded) monitor.start()
        }

        monitor.start()
        updateMediaUi(MediaMonitor.MediaState())
    }

    private fun updateMediaUi(state: MediaMonitor.MediaState) {
        val hasAccess = media?.hasAccess() == true
        tvTitle.text = when {
            state.title != null -> state.title
            !hasAccess -> "Enable media controls"
            else -> "Nothing playing"
        }
        tvArtist.text = when {
            state.title != null -> state.artist ?: ""
            !hasAccess -> "Tap here to allow media access"
            else -> "Play something to control it here"
        }
        imgAlbum.setImageBitmap(state.art)
        btnPlayPause.setImageResource(
            if (state.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
        // Collapsed capsule shows a small green dot while music is playing
        dotMedia.visibility = if (state.isPlaying) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------------------------
    // Timer
    // ------------------------------------------------------------------------------------

    private fun setupTimer() {
        btnTimerToggle.setOnClickListener {
            if (timerRunning) pauseTimer() else startTimer()
        }
        rootView!!.findViewById<View>(R.id.btnTimerAdd).setOnClickListener {
            timerRemainingMs += 60_000L
            if (timerRunning) startTimer()      // restart with the new total
            updateTimerUi()
        }
        updateTimerUi()
    }

    private fun startTimer() {
        countDown?.cancel()
        timerRunning = true
        countDown = object : CountDownTimer(timerRemainingMs, 1000L) {
            override fun onTick(msLeft: Long) {
                timerRemainingMs = msLeft
                updateTimerUi()
            }

            override fun onFinish() {
                timerRunning = false
                timerRemainingMs = DEFAULT_TIMER_MS
                updateTimerUi()
                capsule.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                capsule.expand()                 // pop open so the user notices it finished
            }
        }.start()
        updateTimerUi()
    }

    private fun pauseTimer() {
        countDown?.cancel()
        timerRunning = false
        updateTimerUi()
    }

    private fun updateTimerUi() {
        val text = formatTime(timerRemainingMs)
        tvTimer.text = text
        btnTimerToggle.text = if (timerRunning) "Pause" else "Start"

        // Collapsed capsule shows the countdown only while it is running
        tvCollapsedTimer.text = text
        tvCollapsedTimer.visibility = if (timerRunning) View.VISIBLE else View.GONE
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = (ms + 999) / 1000
        return String.format(Locale.getDefault(), "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
    }

    // ------------------------------------------------------------------------------------

    override fun onDestroy() {
        super.onDestroy()
        countDown?.cancel()
        media?.stop()
        rootView?.let { windowManager.removeView(it) }   // always remove the overlay
        rootView = null
    }
}
