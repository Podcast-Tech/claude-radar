package com.poddster.clauderadar

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime

object Supa {
    val client by lazy {
        createSupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY) {
            install(Auth)
            install(Postgrest)
            install(Realtime)
        }
    }
}

object Channels {
    const val NEEDS_YOU = "needs_you"
    const val FINISHED = "finished"
    const val SILENT = "silent"
    const val SERVICE = "service"
}

object Prefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("radar", Context.MODE_PRIVATE)
    fun recording(ctx: Context) = p(ctx).getBoolean("recording", false)
    fun setRecording(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("recording", v).apply()
    fun keepScreenOn(ctx: Context) = p(ctx).getBoolean("keep_screen_on", true)
    fun setKeepScreenOn(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("keep_screen_on", v).apply()
}

class RadarApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(Channels.NEEDS_YOU, "Needs you", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "A chat is waiting for your answer or permission"
                    enableVibration(false) // we vibrate ourselves so recording mode can silence it
                },
                NotificationChannel(Channels.FINISHED, "Finished", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "A chat finished its task"
                    enableVibration(false)
                },
                NotificationChannel(Channels.SILENT, "Recording mode", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Updates while recording mode is on (no sound)"
                    setSound(null, null)
                    enableVibration(false)
                },
                NotificationChannel(Channels.SERVICE, "Live connection", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Keeps the radar connected"
                    setShowBadge(false)
                },
            )
        )
    }
}
