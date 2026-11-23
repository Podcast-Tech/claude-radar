package com.poddster.clauderadar

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/**
 * GoGo's voice: short synthesized chirps, generated on the phone (nothing to license).
 * Works with the screen off because it doesn't depend on the companion screen being open.
 */
object Chirps {
    private const val RATE = 22050

    private enum class Wave { SINE, TRIANGLE, SQUARE }

    private data class Note(val freq: Double, val dur: Double, val slide: Double = 1.25, val wave: Wave = Wave.SINE, val vol: Double = .5)

    private fun seq(gap: Double, vararg notes: Note) = notes.toList() to gap

    private val sounds = mapOf(
        "boop" to seq(0.0, Note(620.0, .10, 1.6)),
        "needs_you" to seq(.05, Note(880.0, .08, 1.15, Wave.TRIANGLE), Note(1320.0, .08, 1.15, Wave.TRIANGLE),
            Note(880.0, .08, 1.15, Wave.TRIANGLE), Note(1320.0, .08, 1.15, Wave.TRIANGLE)),
        "finished" to seq(.015, Note(784.0, .09, 1.02, Wave.TRIANGLE), Note(988.0, .09, 1.02, Wave.TRIANGLE),
            Note(1175.0, .09, 1.02, Wave.TRIANGLE), Note(1568.0, .12, 1.02, Wave.TRIANGLE)),
        "failed" to seq(.02, Note(520.0, .14, .8, Wave.SQUARE, .22), Note(390.0, .14, .8, Wave.SQUARE, .22),
            Note(260.0, .18, .8, Wave.SQUARE, .22)),
    )

    private val cache = HashMap<String, ShortArray>()

    private fun render(name: String): ShortArray = cache.getOrPut(name) {
        val (notes, gap) = sounds[name] ?: sounds.getValue("boop")
        val out = ArrayList<Short>()
        for (n in notes) {
            val len = (n.dur * RATE).toInt()
            var phase = 0.0
            val k = ln(n.slide) / n.dur // exponential pitch slide
            for (i in 0 until len) {
                val t = i.toDouble() / RATE
                val f = n.freq * exp(k * t)
                phase += 2 * PI * f / RATE
                val p = (phase / (2 * PI)) % 1.0
                val s = when (n.wave) {
                    Wave.SINE -> sin(phase)
                    Wave.TRIANGLE -> 4 * kotlin.math.abs(p - .5) - 1
                    Wave.SQUARE -> if (p < .5) 1.0 else -1.0
                }
                // quick attack, smooth decay: no clicks
                val env = minOf(1.0, t / .012) * exp(-4.5 * t / n.dur)
                out.add((s * env * n.vol * Short.MAX_VALUE).toInt().toShort())
            }
            repeat((gap * RATE).toInt()) { out.add(0) }
        }
        out.toShortArray()
    }

    fun play(name: String) {
        runCatching {
            val pcm = render(name)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            track.write(pcm, 0, pcm.size)
            track.setNotificationMarkerPosition(pcm.size)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack) { t.release() }
                override fun onPeriodicNotification(t: AudioTrack) {}
            })
            track.play()
        }
    }
}
