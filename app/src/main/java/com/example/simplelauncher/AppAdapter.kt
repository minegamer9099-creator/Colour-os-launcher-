package com.example.simplelauncher

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Icon grid used by the home pages and by the app drawer. */
class AppAdapter(
    private val allApps: List<AppInfo>,
    private val onAppClick: (AppInfo, View) -> Unit,
    private val onAppLongClick: ((AppInfo, View) -> Unit)? = null
) : RecyclerView.Adapter<AppAdapter.AppViewHolder>() {

    private var shownApps: List<AppInfo> = allApps

    /** Colour of the app names (set by the Palette Engine). */
    var labelColor: Int = Color.WHITE

    class AppViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.appIcon)
        val label: TextView = view.findViewById(R.id.appLabel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val app = shownApps[position]
        bindIcon(holder.icon, app.icon)
        holder.label.text = app.label
        holder.label.setTextColor(labelColor)

        attachPressEffect(holder.itemView)
        holder.itemView.setOnClickListener { onAppClick(app, holder.icon) }
        holder.itemView.setOnLongClickListener {
            onAppLongClick?.invoke(app, holder.icon)
            onAppLongClick != null
        }
    }

    override fun getItemCount(): Int = shownApps.size

    // ---- Search / alphabet ----

    @SuppressLint("NotifyDataSetChanged")
    fun filter(query: String) {
        val text = query.trim()
        shownApps = if (text.isEmpty()) allApps
        else allApps.filter { it.label.contains(text, ignoreCase = true) }
        notifyDataSetChanged()
    }

    fun firstMatch(): AppInfo? = shownApps.firstOrNull()

    /** Position of the first app that starts with this letter ('#' = digits and symbols). */
    fun indexOfLetter(letter: Char): Int = shownApps.indexOfFirst { bucket(it.label) == letter }

    private fun bucket(label: String): Char {
        val c = label.trim().firstOrNull()?.uppercaseChar() ?: '#'
        return if (c in 'A'..'Z') c else '#'
    }

    // ---- Theme ----

    fun updateLabelColor(recyclerView: RecyclerView, color: Int) {
        labelColor = color
        for (i in 0 until recyclerView.childCount) {
            val holder = recyclerView.getChildViewHolder(recyclerView.getChildAt(i)) as? AppViewHolder
            holder?.label?.setTextColor(color)
        }
    }

    companion object {
        /** Shows an icon; old-style (non-adaptive) icons get padding so they sit inside the rounded tile. */
        fun bindIcon(imageView: ImageView, icon: Drawable) {
            imageView.setImageDrawable(icon)
            val adaptive = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && icon is AdaptiveIconDrawable
            val pad = if (adaptive) 0 else (8 * imageView.resources.displayMetrics.density).toInt()
            imageView.setPadding(pad, pad, pad, pad)
        }

        /** Shrinks the view when touched and springs it back when released. */
        @SuppressLint("ClickableViewAccessibility")
        fun attachPressEffect(view: View) {
            view.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN ->
                        v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(100).setInterpolator(null).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        v.animate().scaleX(1f).scaleY(1f).setDuration(250)
                            .setInterpolator(OvershootInterpolator(3f)).start()
                }
                false
            }
        }
    }
}
