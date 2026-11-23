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
    // v2: pop-up importance; the sound is played by the app itself (see Alerter).
    const val NEEDS_YOU = "needs_you_v2"
    const val FINISHED = "finished_v2"
    const val SILENT = "silent"
    const val SERVICE = "service"
}

object Prefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("radar", Context.MODE_PRIVATE)
    fun recording(ctx: Context) = p(ctx).getBoolean("recording", false)
    fun setRecording(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("recording", v).apply()
    fun keepScreenOn(ctx: Context) = p(ctx).getBoolean("keep_screen_on", true)
    fun setKeepScreenOn(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("keep_screen_on", v).apply()
    /** Minutes after which an unseen "finished" result clears itself (0 = never). */
    fun autoRestMinutes(ctx: Context) = p(ctx).getInt("auto_rest_min", 10)
    fun setAutoRestMinutes(ctx: Context, v: Int) = p(ctx).edit().putInt("auto_rest_min", v).apply()
}

class RadarApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        listOf("needs_you", "finished").forEach { nm.deleteNotificationChannel(it) } // 1.0.0 channels
        fun alertChannel(id: String, name: String, desc: String) =
            NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
                description = desc
                setSound(null, null)   // the app plays the sound, so recording mode can silence it
                enableVibration(false) // same for vibration
            }
        nm.createNotificationChannels(
            listOf(
                alertChannel(Channels.NEEDS_YOU, "Needs you", "A chat is waiting for your answer or permission"),
                alertChannel(Channels.FINISHED, "Finished", "A chat finished its task"),
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
