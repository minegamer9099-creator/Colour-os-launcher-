package com.example.simplelauncher

import android.content.Context

/** Remembers which apps the user put on the home screen (survives app restarts). */
class HomeStore(context: Context) {

    private val prefs = context.getSharedPreferences("home_screen", Context.MODE_PRIVATE)

    /** Saved package names, or null if the user has never had a home screen layout yet. */
    fun savedPackages(): List<String>? =
        prefs.getString(KEY, null)?.split("\n")?.filter { it.isNotBlank() }

    fun save(packages: List<String>) {
        prefs.edit().putString(KEY, packages.joinToString("\n")).apply()
    }

    private companion object {
        const val KEY = "home_apps"
    }
}
