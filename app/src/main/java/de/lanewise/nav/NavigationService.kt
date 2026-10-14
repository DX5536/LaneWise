package de.lanewise.nav

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import de.lanewise.MainActivity
import de.lanewise.R

/**
 * Keeps guidance alive while navigating with the screen off or another app in front: a location foreground service
 * (Android stops GNSS updates to background apps otherwise). It does no work itself — the ViewModel keeps receiving
 * fixes, speaking and re-routing — it only holds the app in the foreground state and shows the next manoeuvre in an
 * ongoing notification. Started with "Let's go" / Demo, stopped when navigation ends.
 */
class NavigationService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stop(); return START_NOT_STICKY }
        val n = notification(this, intent?.getStringExtra(EXTRA_TITLE) ?: "LaneWise", intent?.getStringExtra(EXTRA_TEXT) ?: "",
            intent?.getStringExtra(EXTRA_CHANNEL) ?: "Navigation")
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, n,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        } catch (_: Exception) {
            // No location permission (yet) or not allowed from the background: guidance still runs while visible.
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun stop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val CHANNEL_ID = "navigation"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "de.lanewise.nav.STOP"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_CHANNEL = "channel"

        fun start(ctx: Context, title: String, text: String, channelName: String) {
            val i = Intent(ctx, NavigationService::class.java)
                .putExtra(EXTRA_TITLE, title).putExtra(EXTRA_TEXT, text).putExtra(EXTRA_CHANNEL, channelName)
            runCatching { ContextCompat.startForegroundService(ctx, i) }
        }

        /** Updates the ongoing notification (next manoeuvre); cheap, call when the text changes. */
        fun update(ctx: Context, title: String, text: String, channelName: String) {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            runCatching { nm.notify(NOTIFICATION_ID, notification(ctx, title, text, channelName)) }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.startService(Intent(ctx, NavigationService::class.java).setAction(ACTION_STOP)) }
            ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }

        private fun notification(ctx: Context, title: String, text: String, channelName: String): Notification {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm?.createNotificationChannel(NotificationChannel(CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) })
            }
            val open = PendingIntent.getActivity(ctx, 0,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(open)
                .build()
        }
    }
}
