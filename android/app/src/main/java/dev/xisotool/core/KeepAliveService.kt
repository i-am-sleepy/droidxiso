package dev.xisotool.core

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import dev.xisotool.MainActivity

/**
 * Does nothing except keep the app process alive (with a notification) while a long
 * extraction / build runs, so Android does not kill it when you switch to another app.
 */
class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = buildNotification(this, "XISO Tool", "Working…", 0, 0)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
        return START_NOT_STICKY
    }

    companion object {
        const val CHANNEL = "xiso_ops"
        const val NOTIF_ID = 42

        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, KeepAliveService::class.java))
            } catch (e: Exception) {
                // Not fatal: the operation still runs, it just may be stopped in the background.
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, KeepAliveService::class.java))
            } catch (e: Exception) {
            }
        }

        fun update(ctx: Context, title: String, text: String, done: Long, total: Long) {
            try {
                ctx.getSystemService(NotificationManager::class.java)
                    .notify(NOTIF_ID, buildNotification(ctx, title, text, done, total))
            } catch (e: Exception) {
            }
        }

        private fun buildNotification(ctx: Context, title: String, text: String, done: Long, total: Long): Notification {
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val b = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
            if (total > 0) {
                b.setProgress(1000, ((done.toDouble() / total.toDouble()) * 1000.0).toInt().coerceIn(0, 1000), false)
            } else {
                b.setProgress(0, 0, true)
            }
            return b.build()
        }
    }
}
