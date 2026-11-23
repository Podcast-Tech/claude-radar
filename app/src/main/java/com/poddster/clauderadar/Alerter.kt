package com.poddster.clauderadar

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Turns a status change into something you notice: a pop-up notification, our own sound
 * and vibration (so it doesn't depend on per-channel sound settings), and an in-app flash.
 */
object Alerter {
    private val _flashes = MutableSharedFlow<RadarSession>(extraBufferCapacity = 8)
    /** The open app listens to this to flash the screen. */
    val flashes = _flashes.asSharedFlow()

    fun alert(ctx: Context, s: RadarSession) {
        val recording = Prefs.recording(ctx)
        val urgent = s.status == "needs_you" || s.status == "failed"
        val verb = when (s.status) {
            "needs_you" -> "needs you"
            "failed" -> "failed"
            else -> "finished"
        }
        val channel = when {
            recording -> Channels.SILENT
            urgent -> Channels.NEEDS_YOU
            else -> Channels.FINISHED
        }
        val open = PendingIntent.getActivity(
            ctx, s.sessionId.hashCode(),
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_SESSION, s.sessionId)
                .putExtra(MainActivity.EXTRA_OPEN_URL, s.url),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_radar)
            .setContentTitle("${s.displayTitle} $verb")
            .setContentText(s.message ?: listOfNotNull(s.project, s.machine).joinToString(" · "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(s.message ?: ""))
            .setPriority(if (recording) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(s.sessionId.hashCode(), n) }
        _flashes.tryEmit(s)
        if (!recording) {
            Chirps.play(s.status) // GoGo's chirp for this kind of alert
            vibrate(ctx, urgent)
        }
    }

    fun test(ctx: Context) = alert(
        ctx,
        RadarSession(
            sessionId = "local-test", status = "finished", title = "Test alert",
            message = "If you heard and felt this, alerts work.", machine = "This phone",
            updatedAt = java.time.OffsetDateTime.now().toString(),
        ),
    )

    private fun vibrate(ctx: Context, urgent: Boolean) {
        runCatching {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
                ctx.getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION") ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            val pattern = if (urgent) longArrayOf(0, 300, 150, 300, 150, 500) else longArrayOf(0, 250, 120, 250)
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
    }

    /** Things on the phone that can silently block alerts. */
    data class Check(val label: String, val ok: Boolean, val detail: String, val fix: (Context) -> Unit)

    fun checks(ctx: Context): List<Check> {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val am = ctx.getSystemService(AudioManager::class.java)
        val pm = ctx.getSystemService(PowerManager::class.java)
        val notifSettings: (Context) -> Unit = {
            it.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, it.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        val channelsOk = listOf(Channels.NEEDS_YOU, Channels.FINISHED).all {
            (nm.getNotificationChannel(it)?.importance ?: 0) >= NotificationManager.IMPORTANCE_DEFAULT
        }
        val vol = am.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION)
        val ringer = when (am.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "silent"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate only"
            else -> "normal"
        }
        val dnd = nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL
        val batteryOk = pm.isIgnoringBatteryOptimizations(ctx.packageName)
        return listOf(
            Check("Notifications", NotificationManagerCompat.from(ctx).areNotificationsEnabled() && channelsOk,
                if (channelsOk) "Allowed" else "Some alert types are turned off", notifSettings),
            Check("Sound", am.ringerMode == AudioManager.RINGER_MODE_NORMAL && vol > 0,
                "Phone is $ringer, notification volume $vol/$maxVol") {
                it.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            Check("Do Not Disturb", !dnd, if (dnd) "On: alerts may be hidden" else "Off") {
                it.startActivity(Intent(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            Check("Background", batteryOk, if (batteryOk) "Unrestricted" else "Battery-optimised: Android may stop the radar") {
                @Suppress("BatteryLife")
                it.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${it.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
        )
    }
}
