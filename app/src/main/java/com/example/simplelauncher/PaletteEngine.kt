// Location: app/src/main/java/com/example/simplelauncher/PaletteEngine.kt
package com.example.simplelauncher

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Reads the user's wallpaper, extracts its dominant + vibrant colours with the Palette API,
 * and turns them into a ready-to-use set of theme colours (ThemeColors).
 *
 * Usage:
 *     val engine = PaletteEngine(context)
 *     engine.start { theme -> /* apply theme colours to your UI */ }
 *     ...
 *     engine.stop()   // in onDestroy
 *
 * It re-extracts automatically when the wallpaper changes.
 *
 * Where the colours come from (tried in this order):
 *   1. Palette API on the real wallpaper bitmap (needs permission to read the wallpaper)
 *   2. Android's own WallpaperColors (Android 8.1+, needs no permission)
 *   3. A default blue theme
 * Watch Logcat for the tag "PaletteEngine" to see which one was used.
 */
class PaletteEngine(context: Context) {

    /** Every colour the launcher UI needs. All values are ARGB ints. */
    data class ThemeColors(
        val accent: Int,        // main highlight colour (vibrant)
        val labelTint: Int,     // app-name text colour
        val hintTint: Int,      // search hint text colour
        val searchBorder: Int,  // search bar outline
        val drawerTop: Int,     // app drawer glass gradient: start colour
        val drawerBottom: Int,  // app drawer glass gradient: end colour
        val drawerStroke: Int   // app drawer edge highlight
    ) {
        companion object {
            /** Blends two themes. t = 0 -> a, t = 1 -> b. Used for smooth colour transitions. */
            fun lerp(a: ThemeColors, b: ThemeColors, t: Float) = ThemeColors(
                ColorUtils.blendARGB(a.accent, b.accent, t),
                ColorUtils.blendARGB(a.labelTint, b.labelTint, t),
                ColorUtils.blendARGB(a.hintTint, b.hintTint, t),
                ColorUtils.blendARGB(a.searchBorder, b.searchBorder, t),
                ColorUtils.blendARGB(a.drawerTop, b.drawerTop, t),
                ColorUtils.blendARGB(a.drawerBottom, b.drawerBottom, t),
                ColorUtils.blendARGB(a.drawerStroke, b.drawerStroke, t)
            )
        }
    }

    companion object {
        private const val TAG = "PaletteEngine"
        private const val MAX_SIDE_PX = 160   // wallpaper is shrunk to this before analysis (fast)

        fun defaultTheme(): ThemeColors = buildTheme(
            dominant = 0xFF4A6FA5.toInt(),
            vibrant = 0xFF5B9DFF.toInt()
        )

        /** Turns two raw colours into the full set of UI colours. */
        private fun buildTheme(dominant: Int, vibrant: Int): ThemeColors {
            val accent = ensureVisible(vibrant)

            // On a bright wallpaper white text would vanish, so switch to dark text
            val wallpaperIsLight = ColorUtils.calculateLuminance(dominant) > 0.55
            val labelBase = if (wallpaperIsLight) 0xFF1C1C1E.toInt() else Color.WHITE

            // Text stays readable (mostly white/dark) with a hint of the accent
            val labelTint = ColorUtils.blendARGB(labelBase, accent, 0.18f)

            return ThemeColors(
                accent = accent,
                labelTint = labelTint,
                hintTint = ColorUtils.setAlphaComponent(labelTint, 0x99),
                searchBorder = ColorUtils.setAlphaComponent(accent, 0xE0),
                // Glass gradient: translucent white tinted with the wallpaper colours
                drawerTop = ColorUtils.setAlphaComponent(
                    ColorUtils.blendARGB(Color.WHITE, accent, 0.35f), 0x59),
                drawerBottom = ColorUtils.setAlphaComponent(
                    ColorUtils.blendARGB(Color.WHITE, dominant, 0.25f), 0x26),
                drawerStroke = ColorUtils.setAlphaComponent(accent, 0x80)
            )
        }

        /** Keeps the accent vivid and not too dark or too pale, so it always shows up on glass. */
        private fun ensureVisible(color: Int): Int {
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(color, hsl)
            hsl[1] = hsl[1].coerceAtLeast(0.45f)       // saturation
            hsl[2] = hsl[2].coerceIn(0.50f, 0.72f)     // lightness
            return ColorUtils.HSLToColor(hsl)
        }
    }

    private val appContext = context.applicationContext
    private val wallpaperManager = WallpaperManager.getInstance(appContext)
    private val worker = Executors.newSingleThreadExecutor()     // heavy work off the main thread
    private val mainHandler = Handler(Looper.getMainLooper())

    private var onThemeReady: ((ThemeColors) -> Unit)? = null
    private var colorsListener: Any? = null
    private var lastWallpaperId = -1
    @Volatile private var stopped = false

    /** Starts watching the wallpaper and delivers the first theme as soon as it is ready. */
    fun start(listener: (ThemeColors) -> Unit) {
        onThemeReady = listener
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) registerColorsListener()
        refresh(force = true)
    }

    fun stop() {
        stopped = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) unregisterColorsListener()
        worker.shutdown()
        onThemeReady = null
    }

    /**
     * Re-reads the wallpaper. With force = false it does nothing unless the wallpaper
     * has actually changed (cheap to call from onResume).
     */
    fun refresh(force: Boolean = false) {
        if (stopped) return
        worker.execute {
            val id = try {
                wallpaperManager.getWallpaperId(WallpaperManager.FLAG_SYSTEM)
            } catch (e: Exception) { -1 }

            if (!force && id != -1 && id == lastWallpaperId) return@execute

            val theme = extract()
            lastWallpaperId = id
            mainHandler.post { if (!stopped) onThemeReady?.invoke(theme) }
        }
    }

    // ------------------------------------------------------------------------------------
    // Wallpaper change notifications (Android 8.1+)
    // ------------------------------------------------------------------------------------

    @RequiresApi(Build.VERSION_CODES.O_MR1)
    private fun registerColorsListener() {
        val l = WallpaperManager.OnColorsChangedListener { _, _ -> refresh(force = true) }
        colorsListener = l
        wallpaperManager.addOnColorsChangedListener(l, mainHandler)
    }

    @RequiresApi(Build.VERSION_CODES.O_MR1)
    private fun unregisterColorsListener() {
        (colorsListener as? WallpaperManager.OnColorsChangedListener)?.let {
            wallpaperManager.removeOnColorsChangedListener(it)
        }
        colorsListener = null
    }

    // ------------------------------------------------------------------------------------
    // Colour extraction
    // ------------------------------------------------------------------------------------

    private fun extract(): ThemeColors {
        // 1) Palette API on the real wallpaper image
        loadWallpaperBitmap()?.let { bitmap ->
            val palette = Palette.from(bitmap).maximumColorCount(24).generate()
            bitmap.recycle()
            Log.d(TAG, "Theme source: Palette API (wallpaper bitmap)")
            return fromPalette(palette)
        }

        // 2) The system's own wallpaper colours
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            fromSystemColors()?.let {
                Log.d(TAG, "Theme source: system WallpaperColors (bitmap not readable)")
                return it
            }
        }

        // 3) Default
        Log.d(TAG, "Theme source: default theme")
        return defaultTheme()
    }

    private fun fromPalette(palette: Palette): ThemeColors {
        // DOMINANT = the colour covering the most pixels (the wallpaper's overall mood)
        val dominant = palette.getDominantColor(0xFF4A6FA5.toInt())

        // VIBRANT = the most eye-catching colour; fall back through similar swatches
        val vibrant = (palette.vibrantSwatch
            ?: palette.lightVibrantSwatch
            ?: palette.darkVibrantSwatch
            ?: palette.mutedSwatch
            ?: palette.dominantSwatch)?.rgb ?: dominant

        return buildTheme(dominant, vibrant)
    }

    @RequiresApi(Build.VERSION_CODES.O_MR1)
    private fun fromSystemColors(): ThemeColors? {
        val colors = wallpaperManager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM) ?: return null
        val primary = colors.primaryColor.toArgb()
        val secondary = colors.secondaryColor?.toArgb() ?: primary
        return buildTheme(dominant = primary, vibrant = secondary)
    }

    /** Loads the current wallpaper as a small bitmap. Returns null if we aren't allowed to read it. */
    private fun loadWallpaperBitmap(): Bitmap? {
        return try {
            val drawable = wallpaperManager.drawable ?: return null
            val iw = drawable.intrinsicWidth.coerceAtLeast(1)
            val ih = drawable.intrinsicHeight.coerceAtLeast(1)
            val scale = MAX_SIDE_PX / max(iw, ih).toFloat()
            val w = (iw * scale).toInt().coerceAtLeast(1)
            val h = (ih * scale).toInt().coerceAtLeast(1)
            // Draws into a normal (software) bitmap, which Palette needs
            drawable.toBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (e: SecurityException) {
            Log.d(TAG, "No permission to read the wallpaper bitmap")
            null
        } catch (e: RuntimeException) {
            Log.d(TAG, "Could not read wallpaper: ${e.message}")
            null
        } catch (e: OutOfMemoryError) {
            Log.d(TAG, "Wallpaper too large to load")
            null
        }
    }
}
