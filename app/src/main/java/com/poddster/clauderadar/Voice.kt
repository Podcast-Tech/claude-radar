package com.poddster.clauderadar

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.ZipInputStream

/** GoGo's voice: the phone's built-in text-to-speech, tuned high and quick so she sounds cute. */
object Speaker {
    private var tts: TextToSpeech? = null
    private var ready = false
    var onSpeakingChanged: ((Boolean) -> Unit)? = null

    fun init(ctx: Context) {
        if (tts != null) return
        tts = TextToSpeech(ctx.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            tts?.apply {
                language = Locale.US
                setPitch(1.6f)
                setSpeechRate(1.08f)
                setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { onSpeakingChanged?.invoke(true) }
                    override fun onDone(id: String?) { onSpeakingChanged?.invoke(false) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { onSpeakingChanged?.invoke(false) }
                })
            }
        }
    }

    fun speak(text: String) {
        val clean = text.replace(Regex("[^\\p{L}\\p{N}\\p{P}\\s]"), "").trim() // drop emoji
        if (!ready || clean.isEmpty()) return
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, Bundle(), "gogo-${System.nanoTime()}")
    }

    fun stop() { tts?.stop() }
}

/**
 * GoGo's ears: offline speech recognition (Vosk). Listens for "Hey GoGo", then for one question.
 * Audio never leaves the phone.
 */
object Ears {
    enum class State { OFF, DOWNLOADING, WAKE, LISTENING }

    interface Listener {
        fun onState(state: State, progress: Float)
        fun onWake()
        fun onHeard(text: String)
    }

    private const val TAG = "GoGoEars"
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    private const val RATE = 16000f
    // Phrases that wake GoGo. "[unk]" soaks up everything else so it isn't forced into a match.
    private const val WAKE_GRAMMAR = """["hey go go", "hi go go", "okay go go", "hello go go", "go go", "[unk]"]"""

    var listener: Listener? = null
    private var model: Model? = null
    private var service: SpeechService? = null
    private var wantWake = false
    var state = State.OFF
        private set

    private fun set(s: State, p: Float = 0f) { state = s; listener?.onState(s, p) }

    fun modelDir(ctx: Context) = File(ctx.filesDir, "vosk-model")
    fun hasModel(ctx: Context) = File(modelDir(ctx), "am").exists() || File(modelDir(ctx), "conf").exists()

    /** Downloads (once) and loads the speech model, then runs [then]. */
    fun ensureModel(ctx: Context, scope: CoroutineScope, then: () -> Unit) {
        if (model != null) { then(); return }
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    if (!hasModel(ctx)) {
                        withContext(Dispatchers.Main) { set(State.DOWNLOADING, 0f) }
                        download(ctx) { p -> scope.launch(Dispatchers.Main) { set(State.DOWNLOADING, p) } }
                    }
                    model = Model(modelDir(ctx).absolutePath)
                    true
                }.getOrElse { Log.w(TAG, "model failed", it); false }
            }
            if (ok) then() else set(State.OFF)
        }
    }

    private fun download(ctx: Context, progress: (Float) -> Unit) {
        val tmp = File(ctx.cacheDir, "vosk-model.zip")
        var url = URL(MODEL_URL)
        var conn: HttpURLConnection
        while (true) {
            conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20_000; conn.readTimeout = 60_000
            val code = conn.responseCode
            if (code in 300..399) { url = URL(url, conn.getHeaderField("Location")); conn.disconnect(); continue }
            if (code != 200) error("download failed ($code)")
            break
        }
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024); var done = 0L; var last = 0L
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n); done += n
                    if (total > 0 && done - last > 500_000) { last = done; progress(done.toFloat() / total) }
                }
            }
        }
        // Unzip, dropping the top-level folder name.
        val dest = modelDir(ctx); dest.deleteRecursively(); dest.mkdirs()
        ZipInputStream(tmp.inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val rel = e.name.substringAfter('/', "")
                if (rel.isEmpty() || rel.contains("..")) continue
                val f = File(dest, rel)
                if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { zip.copyTo(it) } }
            }
        }
        tmp.delete()
    }

    private fun restart(recognizer: Recognizer, timeoutMs: Int, onText: (String, Boolean) -> Unit) {
        service?.let { runCatching { it.stop(); it.shutdown() } }
        service = SpeechService(recognizer, RATE).also { s ->
            s.startListening(object : RecognitionListener {
                override fun onPartialResult(h: String?) { h?.let { onText(textOf(it, "partial"), false) } }
                override fun onResult(h: String?) { h?.let { onText(textOf(it, "text"), true) } }
                override fun onFinalResult(h: String?) { h?.let { onText(textOf(it, "text"), true) } }
                override fun onError(e: Exception?) { Log.w(TAG, "listen error", e); stop() }
                override fun onTimeout() { if (state == State.LISTENING) { listener?.onHeard(""); resumeWake() } }
            }, timeoutMs)
        }
    }

    private fun textOf(json: String, key: String) = runCatching { JSONObject(json).optString(key) }.getOrDefault("").trim()

    /** Wait for "Hey GoGo". */
    fun startWake() {
        val m = model ?: return
        wantWake = true
        restart(Recognizer(m, RATE, WAKE_GRAMMAR), -1) { text, _ ->
            if (state == State.WAKE && text.contains("go go")) {
                listener?.onWake()
                startCommand()
            }
        }
        set(State.WAKE)
    }

    /** Listen for one question (after the wake word, or from the mic button). */
    fun startCommand() {
        val m = model ?: return
        set(State.LISTENING)
        restart(Recognizer(m, RATE), 8000) { text, final ->
            if (final && state == State.LISTENING) {
                if (text.isNotBlank()) { listener?.onHeard(text); resumeWake() }
            }
        }
    }

    /** Back to waiting for the wake word (if that's switched on), otherwise stop. */
    fun resumeWake() { if (wantWake) startWake() else stop() }

    fun pause() { service?.setPause(true) }
    fun unpause() { service?.setPause(false) }

    fun stop() {
        wantWake = false
        service?.let { runCatching { it.stop(); it.shutdown() } }
        service = null
        set(State.OFF)
    }
}
