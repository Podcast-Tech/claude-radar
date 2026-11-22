package com.poddster.clauderadar

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Self-update from GitHub Releases. Every release is signed with the same key, so it installs over this one. */
object Updater {
    data class Release(val version: String, val code: Int, val apkUrl: String, val notes: String)

    fun codeOf(version: String): Int? = runCatching {
        val (a, b, c) = version.removePrefix("v").split(".").map { it.toInt() }
        a * 10000 + b * 100 + c
    }.getOrNull()

    /** Why the last check found nothing, in words for the user. */
    @Volatile var lastCheckNote: String = ""

    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        lastCheckNote = ""
        val conn = URL("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest")
            .openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        try {
            when (conn.responseCode) {
                200 -> {}
                404 -> { lastCheckNote = "No releases published on GitHub yet"; return@withContext null }
                else -> { lastCheckNote = "Couldn't reach GitHub (${conn.responseCode})"; return@withContext null }
            }
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val tag = json.getString("tag_name")
            val code = codeOf(tag) ?: return@withContext null
            if (code <= BuildConfig.VERSION_CODE) {
                lastCheckNote = "You're on the latest version (${BuildConfig.VERSION_NAME})"
                return@withContext null
            }
            val assets = json.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk") } ?: return@withContext null
            Release(tag.removePrefix("v"), code, apk.getString("browser_download_url"), json.optString("body"))
        } finally {
            conn.disconnect()
        }
    }

    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    suspend fun downloadAndInstall(ctx: Context, rel: Release, progress: (Float) -> Unit) {
        val file = withContext(Dispatchers.IO) {
            val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val out = File(dir, "claude-radar-${rel.version}.apk")
            var url = URL(rel.apkUrl)
            var conn: HttpURLConnection
            // Follow GitHub's redirect to its download host manually (cross-host redirects).
            while (true) {
                conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 20_000
                conn.readTimeout = 60_000
                val code = conn.responseCode
                if (code in 300..399) {
                    url = URL(conn.getHeaderField("Location")); conn.disconnect(); continue
                }
                if (code != 200) error("Download failed ($code)")
                break
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: -1
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) progress(done.toFloat() / total)
                    }
                }
            }
            conn.disconnect()
            out
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
