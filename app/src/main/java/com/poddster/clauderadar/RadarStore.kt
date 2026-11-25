package com.poddster.clauderadar

import android.util.Log
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class RadarSession(
    @SerialName("session_id") val sessionId: String,
    val source: String = "code",
    val machine: String? = null,
    val title: String? = null,
    val project: String? = null,
    val status: String,
    val message: String? = null,
    val url: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("seen_at") val seenAt: String? = null,
) {
    val unseen get() = seenAt == null
    val alerting get() = unseen && (status == "needs_you" || status == "finished" || status == "failed")
    val displayTitle get() = title?.takeIf { it.isNotBlank() } ?: project ?: "Claude chat"
}

/** Overall state that drives the screen colour. */
enum class Overall { NEEDS_YOU, FAILED, FINISHED, WORKING, IDLE }

fun overallOf(list: List<RadarSession>): Overall = when {
    list.any { it.unseen && it.status == "needs_you" } -> Overall.NEEDS_YOU
    list.any { it.unseen && it.status == "failed" } -> Overall.FAILED
    list.any { it.unseen && it.status == "finished" } -> Overall.FINISHED
    list.any { it.status == "working" } -> Overall.WORKING
    else -> Overall.IDLE
}

data class Alert(val session: RadarSession)

object RadarStore {
    private const val TAG = "RadarStore"
    private const val COLUMNS =
        "session_id,source,machine,title,project,status,message,url,started_at,updated_at,seen_at"

    private val _sessions = MutableStateFlow<List<RadarSession>>(emptyList())
    val sessions = _sessions.asStateFlow()
    private val _connected = MutableStateFlow(false)
    val connected = _connected.asStateFlow()
    private val _alerts = MutableSharedFlow<Alert>(extraBufferCapacity = 32)
    val alerts = _alerts.asSharedFlow()

    private var job: Job? = null
    private var channel: RealtimeChannel? = null
    private var loadedOnce = false
    private var rtUp = false     // realtime subscription is up
    private var lastOk = 0L      // last successful refresh
    /** "Live" = data is arriving: realtime is up, or the last check worked recently. */
    private fun updateConnected() { _connected.value = rtUp || System.currentTimeMillis() - lastOk < 45_000 }
    private val mutex = Mutex()

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            val ch = Supa.client.channel("radar-sessions")
            channel = ch
            ch.postgresChangeFlow<PostgresAction>(schema = "public") { table = "radar_sessions" }
                .onEach { refresh() }
                .launchIn(this)
            ch.status.onEach {
                val up = it == RealtimeChannel.Status.SUBSCRIBED
                val was = rtUp
                rtUp = up
                if (up && !was) refresh() // catch up after a reconnect
                updateConnected()
            }.launchIn(this)
            runCatching { ch.subscribe() }.onFailure { Log.w(TAG, "subscribe failed", it) }
            // Safety net in case a realtime event is missed.
            // Every minute normally, every 15 s whenever realtime is down.
            while (true) {
                refresh()
                updateConnected()
                delay(if (rtUp) 60_000 else 15_000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        val ch = channel
        channel = null
        loadedOnce = false
        rtUp = false
        lastOk = 0L
        _sessions.value = emptyList()
        _connected.value = false
        if (ch != null) CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { ch.unsubscribe() }
        }
    }

    suspend fun refresh() = mutex.withLock {
        val fresh = runCatching {
            Supa.client.from("radar_sessions")
                .select(Columns.raw(COLUMNS)) { order("updated_at", Order.DESCENDING) }
                .decodeList<RadarSession>()
                .filter { it.status != "ended" }
        }.getOrElse {
            Log.w(TAG, "refresh failed", it)
            return@withLock
        }
        val before = _sessions.value.associateBy { it.sessionId }
        if (loadedOnce) {
            for (s in fresh) {
                val old = before[s.sessionId]
                val changed = old == null || old.status != s.status || (old.seenAt != null && s.seenAt == null)
                if (s.alerting && changed) _alerts.tryEmit(Alert(s))
            }
        }
        loadedOnce = true
        lastOk = System.currentTimeMillis()
        _sessions.value = fresh
        updateConnected()
    }

    suspend fun markSeen(id: String) {
        val now = Instant.now().toString()
        _sessions.value = _sessions.value.map { if (it.sessionId == id) it.copy(seenAt = now) else it }
        runCatching {
            Supa.client.from("radar_sessions").update({ set("seen_at", now) }) {
                filter { eq("session_id", id) }
            }
        }.onFailure { Log.w(TAG, "markSeen failed", it) }
    }

    suspend fun markAllSeen() {
        _sessions.value.filter { it.unseen && it.status != "working" }.forEach { markSeen(it.sessionId) }
    }

    /** Remove finished/failed chats you've already looked at. */
    suspend fun clearDone() {
        val ids = _sessions.value
            .filter { !it.unseen && (it.status == "finished" || it.status == "failed") }
            .map { it.sessionId }
        if (ids.isEmpty()) return
        _sessions.value = _sessions.value.filterNot { it.sessionId in ids }
        runCatching {
            Supa.client.from("radar_sessions").delete { filter { isIn("session_id", ids) } }
        }.onFailure { Log.w(TAG, "clearDone failed", it) }
    }

    suspend fun remove(id: String) {
        _sessions.value = _sessions.value.filterNot { it.sessionId == id }
        runCatching {
            Supa.client.from("radar_sessions").delete { filter { eq("session_id", id) } }
        }
    }
}
