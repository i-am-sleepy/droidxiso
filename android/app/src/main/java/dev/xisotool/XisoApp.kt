package dev.xisotool

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dev.xisotool.core.KeepAliveService
import dev.xisotool.data.Prefs

class XisoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                KeepAliveService.CHANNEL,
                "Running operations",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Shown while an ISO is being extracted, built or edited" },
        )
    }

    companion object {
        lateinit var instance: XisoApp
            private set
    }
}
