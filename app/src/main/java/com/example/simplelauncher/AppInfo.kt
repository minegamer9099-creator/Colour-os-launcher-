// Location: app/src/main/java/com/example/simplelauncher/AppInfo.kt
package com.example.simplelauncher

import android.graphics.drawable.Drawable

/** Holds the three things we need to show one app in the list. */
data class AppInfo(
    val label: String,        // Name shown under the icon, e.g. "Camera"
    val packageName: String,  // Unique ID, e.g. "com.android.camera"
    val icon: Drawable        // The app's icon image
)
