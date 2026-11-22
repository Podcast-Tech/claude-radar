package com.poddster.clauderadar

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Keeps the realtime connection alive in the background and turns status changes into alerts. */
class RadarService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nm by lazy { getSystemService(NotificationManager::class.java) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val n = ongoing("Connecting…")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ONGOING_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ONGOING_ID, n)
        }
        scope.launch {
            val status = Supa.client.auth.sessionStatus.first { it !is SessionStatus.Initializing }
            if (status !is SessionStatus.Authenticated) {
                stopSelf()
                return@launch
            }
            RadarStore.start(scope)
        }
        scope.launch {
            RadarStore.alerts.collect { Alerter.alert(this@RadarService, it.session) }
        }
        scope.launch {
            combine(RadarStore.sessions, RadarStore.connected) { s, c -> s to c }.collect { (list, up) ->
                nm.notify(ONGOING_ID, ongoing(summary(list, up)))
                // Clear alert notifications for chats you've already seen.
                list.filter { !it.alerting }.forEach { nm.cancel(it.sessionId.hashCode()) }
            }
        }
        scope.launch {
            while (true) {
                runCatching { Updater.check() }.getOrNull()?.let { notifyUpdate(it) }
                delay(6 * 60 * 60 * 1000L)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun summary(list: List<RadarSession>, up: Boolean): String {
        if (!up) return "Reconnecting…"
        val need = list.count { it.unseen && it.status == "needs_you" }
        val done = list.count { it.unseen && it.status == "finished" }
        val working = list.count { it.status == "working" }
        val parts = buildList {
            if (need > 0) add("$need need you")
            if (done > 0) add("$done finished")
            if (working > 0) add("$working working")
        }
        return parts.joinToString(" · ").ifEmpty { "Nothing running" } +
            if (Prefs.recording(this)) " · REC" else ""
    }

    private fun ongoing(text: String): Notification =
        NotificationCompat.Builder(this, Channels.SERVICE)
            .setSmallIcon(R.drawable.ic_stat_radar)
            .setContentTitle("Claude Radar")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .build()

    private fun notifyUpdate(rel: Updater.Release) {
        val n = NotificationCompat.Builder(this, Channels.FINISHED)
            .setSmallIcon(R.drawable.ic_stat_radar)
            .setContentTitle("Claude Radar ${rel.version} is available")
            .setContentText("Open the app to install the update")
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        nm.notify(UPDATE_ID, n)
    }

    companion object {
        private const val ONGOING_ID = 1
        private const val UPDATE_ID = 2

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, RadarService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, RadarService::class.java))
        }
    }
}

class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        RadarService.start(context)
    }
}
