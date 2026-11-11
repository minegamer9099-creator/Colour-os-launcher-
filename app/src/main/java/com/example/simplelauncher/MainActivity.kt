package com.example.simplelauncher

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityOptionsCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import java.util.function.Consumer
import kotlin.math.abs

/**
 * ColorOS-style launcher:
 *   HOME   = clock, icon pages on the wallpaper (3D swipe), page dots, dock, search pill
 *   DRAWER = swipe up: blurred glass drawer with A-Z scroller and search
 * Plus: wallpaper-based colours, Fluid Cloud capsule, launch zoom + blur animation.
 */
class MainActivity : Activity() {

    private companion object {
        const val REQ_READ_WALLPAPER = 101
        const val DRAWER_BLUR_PX = 60
        const val LAUNCH_BLUR_PX = 40
        const val DEFAULT_HOME_APPS = 8
        val PREFERRED_HOME_PACKAGES = listOf(
            "com.android.settings", "com.android.vending", "com.google.android.apps.photos",
            "com.google.android.gm", "com.google.android.youtube", "com.instagram.android",
            "com.facebook.katana", "com.google.android.apps.maps"
        )
    }

    // ---- Views ----
    private lateinit var homeLayer: View
    private lateinit var clockTime: TextView
    private lateinit var clockDate: TextView
    private lateinit var workspacePager: ViewPager2
    private lateinit var pageDots: LinearLayout
    private lateinit var dock: LinearLayout
    private lateinit var homeSearchPill: View
    private lateinit var drawerLayer: View
    private lateinit var drawerGrid: RecyclerView
    private lateinit var drawerSearch: EditText
    private lateinit var alphabetIndex: AlphabetIndexView

    // ---- Data ----
    private lateinit var homeStore: HomeStore
    private lateinit var workspaceAdapter: WorkspacePagerAdapter
    private lateinit var drawerAdapter: AppAdapter
    private var allApps: List<AppInfo> = emptyList()
    private var dockApps: List<AppInfo> = emptyList()
    private var homePackages: MutableList<String> = mutableListOf()
    private var selectedPage = 0

    // ---- State ----
    private var drawerOpen = false
    private var isAppLaunched = false
    private var askedOverlayPermission = false
    private lateinit var drawerSpring: SpringAnimation

    // ---- Blur ----
    private var blurListener: Consumer<Boolean>? = null
    private var blurAllowed = false
    private var currentBlur = 0
    private var blurAnimator: ValueAnimator? = null

    // ---- Palette Engine ----
    private lateinit var paletteEngine: PaletteEngine
    private val drawerBackground = GradientDrawable()
    private lateinit var searchBackground: GradientDrawable
    private var shownTheme = PaletteEngine.defaultTheme()
    private var themeAnimator: ValueAnimator? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // =====================================================================================
    // Lifecycle
    // =====================================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        window.attributes = window.attributes.apply { preferredRefreshRate = 120f }

        val root = findViewById<View>(R.id.rootLayout)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val i = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(i.left, i.top, i.right, i.bottom)
            insets
        }

        homeLayer = findViewById(R.id.homeLayer)
        clockTime = findViewById(R.id.clockTime)
        clockDate = findViewById(R.id.clockDate)
        workspacePager = findViewById(R.id.workspacePager)
        pageDots = findViewById(R.id.pageDots)
        dock = findViewById(R.id.dock)
        homeSearchPill = findViewById(R.id.homeSearchPill)
        drawerLayer = findViewById(R.id.drawerLayer)
        drawerGrid = findViewById(R.id.drawerGrid)
        drawerSearch = findViewById(R.id.drawerSearch)
        alphabetIndex = findViewById(R.id.alphabetIndex)

        homeStore = HomeStore(this)
        allApps = loadInstalledApps()

        setupHome()
        setupDrawer()
        setupPaletteEngine()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setupGlassBlur()
    }

    override fun onResume() {
        super.onResume()

        if (isAppLaunched) {
            isAppLaunched = false
            // Coming back from an app: the home screen zooms in softly and the blur fades out
            homeLayer.scaleX = 1.08f
            homeLayer.scaleY = 1.08f
            homeLayer.alpha = 0f
            homeLayer.animate()
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(300).setInterpolator(DecelerateInterpolator(1.5f)).start()
            animateBlur(0)
        }

        paletteEngine.refresh()
        ensureFluidCloudRunning()
    }

    /** Pressing Home while already on the launcher: close the drawer, or go to the first page. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            if (drawerOpen) closeDrawer() else workspacePager.setCurrentItem(0, true)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (drawerOpen) closeDrawer()
        // otherwise: a launcher is the home screen, do nothing
    }

    override fun onDestroy() {
        super.onDestroy()
        themeAnimator?.cancel()
        blurAnimator?.cancel()
        paletteEngine.stop()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            blurListener?.let { windowManager.removeCrossWindowBlurEnabledListener(it) }
        }
    }

    // =====================================================================================
    // Swipe-up / swipe-down gestures
    // =====================================================================================

    private val flingDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                // Only fast, mostly vertical flings count
                if (abs(velocityY) < 1500f || abs(velocityY) < 2f * abs(velocityX)) return false
                if (velocityY < 0 && !drawerOpen) {
                    openDrawer()
                } else if (velocityY > 0 && drawerOpen && !drawerGrid.canScrollVertically(-1)) {
                    closeDrawer()
                }
                return false
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        flingDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    // =====================================================================================
    // Home screen
    // =====================================================================================

    private fun setupHome() {
        dockApps = resolveDockApps()
        populateDock()
        initHomePackages()

        workspaceAdapter = WorkspacePagerAdapter(
            onAppClick = { app, v -> launchApp(app, v) },
            onAppLongClick = { app, v -> showAppMenu(app, v, fromHome = true) }
        )
        workspacePager.adapter = workspaceAdapter

        // 3D curved depth transition between home pages
        DepthCurvePageTransformer().installOn(workspacePager)

        workspacePager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = updateDots(position)
        })

        homeSearchPill.setOnClickListener { openDrawer(focusSearch = true) }

        refreshHome()
    }

    /** The default Phone / Messages / Browser / Camera apps, found through Android itself. */
    @Suppress("DEPRECATION")
    private fun resolveDockApps(): List<AppInfo> {
        val byPackage = allApps.associateBy { it.packageName }
        val intents = listOf(
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:")),
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER),
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        )
        return intents
            .mapNotNull { packageManager.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName }
            .distinct()
            .mapNotNull { byPackage[it] }
    }

    private fun populateDock() {
        dock.removeAllViews()
        dock.visibility = if (dockApps.isEmpty()) View.GONE else View.VISIBLE
        for (app in dockApps) {
            val cell = LayoutInflater.from(this).inflate(R.layout.item_app, dock, false)
            cell.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            cell.findViewById<TextView>(R.id.appLabel).visibility = View.GONE   // dock has no names
            val icon = cell.findViewById<ImageView>(R.id.appIcon)
            AppAdapter.bindIcon(icon, app.icon)
            AppAdapter.attachPressEffect(cell)
            cell.setOnClickListener { launchApp(app, icon) }
            cell.setOnLongClickListener { showAppMenu(app, icon, fromHome = false); true }
            dock.addView(cell)
        }
    }

    /** Loads the saved home layout, or builds a sensible first one. */
    private fun initHomePackages() {
        val saved = homeStore.savedPackages()
        if (saved != null) {
            homePackages = saved.toMutableList()
            return
        }
        val installed = allApps.map { it.packageName }.toSet()
        val dockPackages = dockApps.map { it.packageName }.toSet()
        val defaults = PREFERRED_HOME_PACKAGES
            .filter { it in installed && it !in dockPackages }
            .toMutableList()
        for (app in allApps) {
            if (defaults.size >= DEFAULT_HOME_APPS) break
            if (app.packageName !in defaults && app.packageName !in dockPackages) defaults.add(app.packageName)
        }
        homePackages = defaults
        homeStore.save(defaults)
    }

    private fun refreshHome() {
        val byPackage = allApps.associateBy { it.packageName }
        workspaceAdapter.submitApps(homePackages.mapNotNull { byPackage[it] })
        rebuildDots()
    }

    private fun addToHome(app: AppInfo) {
        if (app.packageName in homePackages) return
        homePackages.add(app.packageName)
        homeStore.save(homePackages)
        refreshHome()
        Toast.makeText(this, "Added to home screen", Toast.LENGTH_SHORT).show()
    }

    private fun removeFromHome(app: AppInfo) {
        homePackages.remove(app.packageName)
        homeStore.save(homePackages)
        refreshHome()
    }

    // ---- Page dots ----

    private fun rebuildDots() {
        pageDots.removeAllViews()
        val count = workspaceAdapter.itemCount
        pageDots.visibility = if (count > 1) View.VISIBLE else View.INVISIBLE
        repeat(count) {
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply {
                    marginStart = dp(4); marginEnd = dp(4)
                }
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            }
            pageDots.addView(dot)
        }
        updateDots(workspacePager.currentItem.coerceAtMost((count - 1).coerceAtLeast(0)))
    }

    private fun updateDots(selected: Int) {
        selectedPage = selected
        for (i in 0 until pageDots.childCount) {
            val d = pageDots.getChildAt(i).background as GradientDrawable
            d.setColor(if (i == selected) shownTheme.accent else 0x66FFFFFF)
        }
    }

    // ---- Long-press menu ----

    private fun showAppMenu(app: AppInfo, anchor: View, fromHome: Boolean) {
        val menu = PopupMenu(this, anchor)
        if (fromHome) {
            menu.menu.add(0, 1, 0, "Remove from home screen")
        } else if (app.packageName !in homePackages) {
            menu.menu.add(0, 2, 0, "Add to home screen")
        }
        menu.menu.add(0, 3, 1, "App info")
        menu.menu.add(0, 4, 2, "Uninstall")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> removeFromHome(app)
                2 -> addToHome(app)
                3 -> startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}"))
                )
                4 -> startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}")))
            }
            true
        }
        menu.show()
    }

    // =====================================================================================
    // App drawer
    // =====================================================================================

    private fun setupDrawer() {
        drawerAdapter = AppAdapter(
            allApps,
            onAppClick = { app, v -> launchApp(app, v) },
            onAppLongClick = { app, v -> showAppMenu(app, v, fromHome = false) }
        )
        drawerGrid.layoutManager = GridLayoutManager(this, 4)
        drawerGrid.adapter = drawerAdapter
        drawerGrid.overScrollMode = View.OVER_SCROLL_NEVER

        drawerSearch.doAfterTextChanged { drawerAdapter.filter(it?.toString().orEmpty()) }
        drawerSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                drawerAdapter.firstMatch()?.let { launchApp(it, drawerSearch) }
                true
            } else false
        }

        alphabetIndex.onLetterSelected = { letter ->
            val index = drawerAdapter.indexOfLetter(letter)
            if (index >= 0) {
                (drawerGrid.layoutManager as GridLayoutManager).scrollToPositionWithOffset(index, 0)
            }
        }

        drawerBackground.orientation = GradientDrawable.Orientation.TOP_BOTTOM
        drawerLayer.background = drawerBackground

        // The drawer slides up on a spring (soft, slightly elastic)
        drawerSpring = SpringAnimation(drawerLayer, DynamicAnimation.TRANSLATION_Y, 0f).apply {
            spring.setStiffness(380f)
            spring.setDampingRatio(0.88f)
            addUpdateListener { _, value, _ -> onDrawerProgress(value) }
            addEndListener { _, _, _, _ ->
                if (!drawerOpen) drawerLayer.visibility = View.INVISIBLE
            }
        }

        // Start parked below the screen
        drawerLayer.post { drawerLayer.translationY = drawerLayer.height.toFloat() }
    }

    fun openDrawer(focusSearch: Boolean = false) {
        if (drawerOpen) return
        drawerOpen = true
        drawerLayer.visibility = View.VISIBLE
        drawerSpring.animateToFinalPosition(0f)
        if (focusSearch) {
            drawerSearch.postDelayed({
                drawerSearch.requestFocus()
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(drawerSearch, InputMethodManager.SHOW_IMPLICIT)
            }, 250)
        }
    }

    fun closeDrawer(animated: Boolean = true) {
        if (!drawerOpen) return
        drawerOpen = false
        hideKeyboard()
        drawerSearch.clearFocus()
        val h = drawerLayer.height.toFloat()
        if (animated) {
            drawerSpring.animateToFinalPosition(h)
        } else {
            drawerSpring.cancel()
            drawerLayer.translationY = h
            drawerLayer.visibility = View.INVISIBLE
            onDrawerProgress(h)
        }
        if (drawerSearch.text.isNotEmpty()) drawerSearch.setText("")   // fresh list next time
    }

    /** Called on every frame of the drawer slide: the home screen recedes and the wallpaper blurs. */
    private fun onDrawerProgress(translationY: Float) {
        val h = drawerLayer.height.coerceAtLeast(1).toFloat()
        val p = (1f - translationY / h).coerceIn(0f, 1f)     // 0 = closed, 1 = fully open

        homeLayer.alpha = 1f - p
        val s = 1f - 0.06f * p
        homeLayer.scaleX = s
        homeLayer.scaleY = s

        // Blur in steps of 4px so we don't ask the window manager for a new value on every pixel
        val r = ((p * DRAWER_BLUR_PX) / 4).toInt() * 4
        if (r != currentBlur) setBlur(r)
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(drawerSearch.windowToken, 0)
    }

    // =====================================================================================
    // Launching apps
    // =====================================================================================

    @Suppress("DEPRECATION")
    private fun loadInstalledApps(): List<AppInfo> {
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        return packageManager.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != packageName }
            .map {
                AppInfo(
                    label = it.loadLabel(packageManager).toString(),
                    packageName = it.activityInfo.packageName,
                    icon = it.loadIcon(packageManager)
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    private fun launchApp(app: AppInfo, sourceView: View) {
        val launchIntent = packageManager.getLaunchIntentForPackage(app.packageName) ?: return
        hideKeyboard()

        // Zoom out of the exact icon that was tapped
        val options = ActivityOptionsCompat.makeScaleUpAnimation(
            sourceView, 0, 0, sourceView.width, sourceView.height
        )
        startActivity(launchIntent, options.toBundle())
        isAppLaunched = true

        if (drawerOpen) closeDrawer(animated = false)

        // "Animation blur": home recedes and the wallpaper blurs while the app opens
        homeLayer.animate()
            .scaleX(0.92f).scaleY(0.92f).alpha(0f)
            .setDuration(250).setInterpolator(DecelerateInterpolator()).start()
        animateBlur(LAUNCH_BLUR_PX)
    }

    // =====================================================================================
    // Window blur (Android 12+)
    // =====================================================================================

    @RequiresApi(Build.VERSION_CODES.S)
    private fun setupGlassBlur() {
        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        val listener = Consumer<Boolean> { allowed ->
            blurAllowed = allowed
            applyBlur(if (allowed) currentBlur else 0)
        }
        blurListener = listener
        windowManager.addCrossWindowBlurEnabledListener(mainExecutor, listener)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun applyBlur(radius: Int) {
        val params = window.attributes
        params.blurBehindRadius = radius
        window.attributes = params
    }

    private fun setBlur(radius: Int) {
        currentBlur = radius
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) applyBlur(if (blurAllowed) radius else 0)
    }

    private fun animateBlur(target: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        blurAnimator?.cancel()
        blurAnimator = ValueAnimator.ofInt(currentBlur, target).apply {
            duration = 280
            addUpdateListener { setBlur(it.animatedValue as Int) }
            start()
        }
    }

    // =====================================================================================
    // Palette Engine (wallpaper colours)
    // =====================================================================================

    private fun setupPaletteEngine() {
        searchBackground = drawerSearch.background.mutate() as GradientDrawable
        applyTheme(shownTheme)

        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.O_MR1..Build.VERSION_CODES.S_V2 &&
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQ_READ_WALLPAPER)
        }

        paletteEngine = PaletteEngine(this)
        paletteEngine.start { newTheme -> animateTheme(newTheme) }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_READ_WALLPAPER) paletteEngine.refresh(force = true)
    }

    private fun animateTheme(target: PaletteEngine.ThemeColors) {
        val from = shownTheme
        themeAnimator?.cancel()
        themeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 500
            addUpdateListener { applyTheme(PaletteEngine.ThemeColors.lerp(from, target, it.animatedFraction)) }
            start()
        }
    }

    private fun applyTheme(c: PaletteEngine.ThemeColors) {
        shownTheme = c

        // Home: names and clock follow the wallpaper (dark text on bright wallpapers)
        workspaceAdapter.updateLabelColor(workspacePager, c.labelTint)
        val clockColor = ColorUtils.setAlphaComponent(c.labelTint, 0xCC)
        clockTime.setTextColor(clockColor)
        clockDate.setTextColor(clockColor)

        // Drawer: always a dark tinted glass, so its text is always light
        val drawerText = ColorUtils.blendARGB(Color.WHITE, c.accent, 0.18f)
        drawerAdapter.updateLabelColor(drawerGrid, drawerText)
        alphabetIndex.letterColor = drawerText
        drawerBackground.colors = intArrayOf(
            ColorUtils.setAlphaComponent(ColorUtils.blendARGB(Color.BLACK, c.accent, 0.30f), 0xC8),
            ColorUtils.setAlphaComponent(ColorUtils.blendARGB(Color.BLACK, c.accent, 0.12f), 0xE0)
        )
        searchBackground.setStroke(dp(2), c.searchBorder)
        drawerSearch.setTextColor(drawerText)
        drawerSearch.setHintTextColor(ColorUtils.setAlphaComponent(drawerText, 0x99))
        drawerSearch.compoundDrawableTintList = android.content.res.ColorStateList.valueOf(c.accent)

        updateDots(selectedPage)
    }

    // =====================================================================================
    // Fluid Cloud
    // =====================================================================================

    private fun ensureFluidCloudRunning() {
        if (!Settings.canDrawOverlays(this)) {
            if (!askedOverlayPermission) {
                askedOverlayPermission = true
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            }
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
        startForegroundService(Intent(this, FluidCloudService::class.java))
    }
}
