package com.poddster.clauderadar

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the GoGo screen needs to know. Field names match the JSON the page reads. */
@Serializable
data class GoGoState(val sessions: List<RadarSession>, val connected: Boolean, val recording: Boolean)

val GoGoJson = Json { encodeDefaults = true; explicitNulls = true }

/** Calls from the GoGo page back into the app (always handled on the main thread). */
class GoGoBridge(
    private val onSeen: (String) -> Unit,
    private val onOpen: (String) -> Unit,
    private val onSeenAll: () -> Unit,
    private val onRemove: (String) -> Unit,
    private val onSpeak: (String) -> Unit,
    private val onListen: () -> Unit,
    private val onRecording: (Boolean) -> Unit,
    private val onBoop: () -> Unit,
    private val onReady: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    @JavascriptInterface fun seen(id: String) { main.post { onSeen(id) } }
    @JavascriptInterface fun open(id: String) { main.post { onOpen(id) } }
    @JavascriptInterface fun seenAll() { main.post { onSeenAll() } }
    @JavascriptInterface fun remove(id: String) { main.post { onRemove(id) } }
    @JavascriptInterface fun speak(text: String) { main.post { onSpeak(text) } }
    @JavascriptInterface fun listen() { main.post { onListen() } }
    @JavascriptInterface fun setRecording(on: Boolean) { main.post { onRecording(on) } }
    @JavascriptInterface fun boop() { main.post { onBoop() } }
    @JavascriptInterface fun ready() { main.post { onReady() } }
}
