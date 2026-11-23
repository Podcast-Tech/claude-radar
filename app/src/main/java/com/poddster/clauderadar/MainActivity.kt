package com.poddster.clauderadar

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import android.webkit.WebView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.widthIn
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

private val Orange = Color(0xFFFF8A00)
private val Red = Color(0xFFE5484D)
private val Green = Color(0xFF1FA855)
private val Blue = Color(0xFF3A7BFF)
private val DimBlue = Color(0xFF0E2148)
private val Grey = Color(0xFF8A8F98)

class MainActivity : ComponentActivity() {
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        applyKeepScreenOn()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Blue)) {
                val status by Supa.client.auth.sessionStatus.collectAsState()
                when (status) {
                    is SessionStatus.Authenticated -> {
                        LaunchedEffect(Unit) { RadarService.start(this@MainActivity) }
                        RadarScreen(onKeepScreenOnChanged = { applyKeepScreenOn() })
                    }
                    is SessionStatus.Initializing -> Box(Modifier.fillMaxSize().background(Color.Black))
                    else -> LoginScreen()
                }
            }
        }
    }

    fun applyKeepScreenOn() {
        if (Prefs.keepScreenOn(this)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

@Composable
private fun LoginScreen() {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    fun run(block: suspend () -> Unit) {
        busy = true; msg = null
        scope.launch {
            runCatching { block() }.onFailure { msg = it.message?.lineSequence()?.firstOrNull() ?: "Something went wrong" }
            busy = false
        }
    }

    Column(
        Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Claude Radar", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text("Sign in with the same account you use in the Windows setup.", color = Grey)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            email, { email = it.trim() }, label = { Text("Email") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            password, { password = it }, label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Button(
            enabled = !busy && email.isNotBlank() && password.length >= 6,
            onClick = { run { Supa.client.auth.signInWith(Email) { this.email = email; this.password = password } } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Sign in") }
        OutlinedButton(
            enabled = !busy && email.isNotBlank() && password.length >= 6,
            onClick = {
                run {
                    Supa.client.auth.signUpWith(Email) { this.email = email; this.password = password }
                    if (Supa.client.auth.currentSessionOrNull() == null) {
                        msg = "Account created. Confirm the email Supabase sent you, then tap Sign in."
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Create account") }
        msg?.let { Spacer(Modifier.height(12.dp)); Text(it, color = Orange) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RadarScreen(onKeepScreenOnChanged: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessions by RadarStore.sessions.collectAsState()
    val connected by RadarStore.connected.collectAsState()
    var recording by remember { mutableStateOf(Prefs.recording(ctx)) }
    var keepOn by remember { mutableStateOf(Prefs.keepScreenOn(ctx)) }
    var menu by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<Updater.Release?>(null) }
    var updateProgress by remember { mutableStateOf<Float?>(null) }
    var updateMsg by remember { mutableStateOf<String?>(null) }
    var showChecks by remember { mutableStateOf(false) }
    val checks by produceState(Alerter.checks(ctx)) { while (true) { value = Alerter.checks(ctx); delay(5_000) } }

    LaunchedEffect(Unit) { update = runCatching { Updater.check() }.getOrNull() }

    // GoGo lives in a WebView (assets/gogo/index.html); the app feeds it the chats.
    var web by remember { mutableStateOf<WebView?>(null) }
    var webReady by remember { mutableStateOf(false) }
    val payload = remember(sessions, connected, recording) {
        GoGoJson.encodeToString(GoGoState(sessions, connected, recording))
    }
    LaunchedEffect(webReady, payload) {
        if (webReady) web?.evaluateJavascript("window.gogo && gogo.update($payload)", null)
    }
    LaunchedEffect(webReady) {
        if (webReady) Alerter.flashes.collect { web?.evaluateJavascript("window.gogo && gogo.celebrate()", null) }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF070A12))) {
        AndroidView(
            modifier = Modifier.fillMaxSize().systemBarsPadding(),
            factory = { c ->
                WebView(c).apply {
                    setBackgroundColor(android.graphics.Color.parseColor("#070A12"))
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    overScrollMode = android.view.View.OVER_SCROLL_NEVER
                    addJavascriptInterface(
                        GoGoBridge(
                            onTap = { id ->
                                val s = RadarStore.sessions.value.firstOrNull { it.sessionId == id }
                                scope.launch { RadarStore.markSeen(id) }
                                s?.url?.let { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
                            },
                            onRemove = { id -> scope.launch { RadarStore.remove(id) } },
                            onBoop = { if (!Prefs.recording(ctx)) Chirps.play("boop") },
                            onReady = { webReady = true },
                        ),
                        "Android",
                    )
                    loadUrl("file:///android_asset/gogo/index.html")
                    web = this
                }
            },
        )

        Row(
            Modifier.align(Alignment.TopEnd).systemBarsPadding().padding(top = 2.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = recording,
                onClick = { recording = !recording; Prefs.setRecording(ctx, recording) },
                label = { Text(if (recording) "● REC" else "REC") },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Color(0x22FFFFFF), labelColor = Color.White,
                    selectedContainerColor = Color(0xFFFF6FA8), selectedLabelColor = Color.White,
                ),
            )
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menu", tint = Color.White) }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Test alert") }, onClick = { menu = false; Alerter.test(ctx) })
                    DropdownMenuItem(text = { Text("Alert check") }, onClick = { menu = false; showChecks = true })
                    DropdownMenuItem(text = { Text("Mark all as seen") }, onClick = { menu = false; scope.launch { RadarStore.markAllSeen() } })
                    DropdownMenuItem(text = { Text("Clear seen results") }, onClick = { menu = false; scope.launch { RadarStore.clearDone() } })
                    DropdownMenuItem(
                        text = { Text(if (keepOn) "Let screen sleep" else "Keep screen on") },
                        onClick = { menu = false; keepOn = !keepOn; Prefs.setKeepScreenOn(ctx, keepOn); onKeepScreenOnChanged() },
                    )
                    DropdownMenuItem(text = { Text("Check for updates") }, onClick = {
                        menu = false
                        scope.launch {
                            updateMsg = null
                            val r = runCatching { Updater.check() }
                            update = r.getOrNull()
                            if (update == null) updateMsg = r.exceptionOrNull()?.let { "Couldn't check for updates: no connection?" }
                                ?: Updater.lastCheckNote.ifEmpty { "No update found" }
                        }
                    })
                    DropdownMenuItem(text = { Text("Sign out") }, onClick = {
                        menu = false
                        scope.launch { RadarService.stop(ctx); RadarStore.stop(); Supa.client.auth.signOut() }
                    })
                    DropdownMenuItem(text = { Text("Version ${BuildConfig.VERSION_NAME}", color = Grey) }, onClick = { menu = false })
                }
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).systemBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 56.dp)
                .widthIn(max = 520.dp),
        ) {
            val blocked = checks.filter { !it.ok }
            if (blocked.isNotEmpty()) {
                Banner {
                    Text("Alerts may not reach you: " + blocked.joinToString { it.label.lowercase() },
                        color = Color.White, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showChecks = true }) { Text("Fix", color = Orange) }
                }
            }
            update?.let { rel ->
                Banner {
                    Column(Modifier.weight(1f)) {
                        Text("Update ${rel.version} available", color = Color.White, fontWeight = FontWeight.SemiBold)
                        updateProgress?.let { LinearProgressIndicator(progress = { it }, Modifier.fillMaxWidth().padding(top = 6.dp)) }
                    }
                    TextButton(enabled = updateProgress == null, onClick = {
                        if (!Updater.canInstall(ctx)) { Updater.openInstallPermission(ctx); return@TextButton }
                        scope.launch {
                            updateProgress = 0f
                            runCatching { Updater.downloadAndInstall(ctx, rel) { updateProgress = it } }
                                .onFailure { updateMsg = "Update failed: ${it.message}" }
                            updateProgress = null
                        }
                    }) { Text("Install", color = Color.White) }
                }
            }
            updateMsg?.let { m ->
                LaunchedEffect(m) { delay(6_000); if (updateMsg == m) updateMsg = null }
                Banner { Text(m, color = Color.White, modifier = Modifier.weight(1f)) }
            }
        }
    }

    if (showChecks) {
        AlertDialog(
            onDismissRequest = { showChecks = false },
            title = { Text("Alert check") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    checks.forEach { c ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (c.ok) "✓" else "✗", color = if (c.ok) Green else Red, fontSize = 20.sp,
                                modifier = Modifier.width(28.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.label, fontWeight = FontWeight.SemiBold)
                                Text(c.detail, fontSize = 13.sp, color = Grey)
                            }
                            if (!c.ok) TextButton(onClick = { c.fix(ctx) }) { Text("Fix") }
                        }
                    }
                    Text(if (connected) "Connection: live" else "Connection: reconnecting…", fontSize = 13.sp, color = Grey)
                }
            },
            confirmButton = { TextButton(onClick = { Alerter.test(ctx) }) { Text("Test alert") } },
            dismissButton = { TextButton(onClick = { showChecks = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun Banner(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp)
            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(12.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, content = content,
    )
}

@Composable
private fun SessionRow(s: RadarSession, now: Instant, modifier: Modifier) {
    val (dot, label) = when {
        s.status == "needs_you" && s.unseen -> Orange to "Needs you"
        s.status == "failed" -> Red to "Failed"
        s.status == "finished" && s.unseen -> Green to "Finished"
        s.status == "finished" -> Grey to "Done"
        s.status == "needs_you" -> Orange.copy(alpha = 0.6f) to "Waiting"
        else -> Blue to "Working"
    }
    val ago = runCatching {
        val d = Duration.between(OffsetDateTime.parse(s.updatedAt).toInstant(), now)
        when {
            d.toMinutes() < 1 -> "just now"
            d.toMinutes() < 60 -> "${d.toMinutes()} min ago"
            d.toHours() < 24 -> "${d.toHours()} h ago"
            else -> "${d.toDays()} d ago"
        }
    }.getOrDefault("")
    Row(
        modifier.fillMaxWidth()
            .background(Color.Black.copy(alpha = if (s.alerting) 0.55f else 0.35f), RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 6.dp).size(14.dp).background(dot, CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(s.displayTitle, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(label, s.project?.takeIf { it != s.displayTitle }, s.machine, ago).joinToString(" · "),
                color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            s.message?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
