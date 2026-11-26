package com.jarvis.assistant.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jarvis.assistant.JarvisApplication

/**
 * Restores hands-free "Jarvis" wake-word listening after reboot when enabled in settings.
 */
class JarvisBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        val app = context.applicationContext as? JarvisApplication ?: return
        val settings = app.container.settingsStore.current()
        if (settings.wakeWordEnabled) {
            JarvisWakeWordService.syncWithSettings(context, settings)
        }
    }
}
