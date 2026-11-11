// Location: app/src/main/java/com/example/simplelauncher/FluidNotificationListener.kt
package com.example.simplelauncher

import android.service.notification.NotificationListenerService

/**
 * Intentionally empty.
 *
 * Android only lets an app read other apps' media sessions if the app has an enabled
 * NotificationListenerService. This class exists purely to satisfy that rule.
 * We never read any notifications.
 */
class FluidNotificationListener : NotificationListenerService()
