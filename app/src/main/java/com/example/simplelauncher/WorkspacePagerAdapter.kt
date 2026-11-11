package com.example.simplelauncher

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

/** Home screen pages: 4 columns x 4 rows of icons per page, drawn directly on the wallpaper. */
class WorkspacePagerAdapter(
    private val columns: Int = 4,
    private val rows: Int = 4,
    private val onAppClick: (AppInfo, View) -> Unit,
    private val onAppLongClick: ((AppInfo, View) -> Unit)? = null
) : RecyclerView.Adapter<WorkspacePagerAdapter.PageHolder>() {

    private var pages: List<List<AppInfo>> = emptyList()
    private var labelColor: Int = Color.WHITE

    class PageHolder(val grid: RecyclerView) : RecyclerView.ViewHolder(grid)

    @SuppressLint("NotifyDataSetChanged")
    fun submitApps(apps: List<AppInfo>) {
        pages = apps.chunked(columns * rows)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
        val density = parent.resources.displayMetrics.density
        val grid = RecyclerView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            layoutManager = GridLayoutManager(context, columns)
            overScrollMode = View.OVER_SCROLL_NEVER
            setHasFixedSize(true)
            clipToPadding = false
            setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
        }
        return PageHolder(grid)
    }

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        val pageAdapter = AppAdapter(pages[position], onAppClick, onAppLongClick)
        pageAdapter.labelColor = labelColor
        holder.grid.adapter = pageAdapter
    }

    override fun getItemCount(): Int = pages.size

    fun updateLabelColor(pager: ViewPager2, color: Int) {
        labelColor = color
        val pagesRecycler = pager.getChildAt(0) as? RecyclerView ?: return
        for (i in 0 until pagesRecycler.childCount) {
            val grid = pagesRecycler.getChildAt(i) as? RecyclerView ?: continue
            (grid.adapter as? AppAdapter)?.updateLabelColor(grid, color)
        }
    }
}
