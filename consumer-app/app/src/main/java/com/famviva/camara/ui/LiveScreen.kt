package com.famviva.camara.ui

import android.app.Activity
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView
import com.famviva.camara.R
import com.famviva.camara.data.CameraConfigStore
import kotlinx.coroutines.delay

private enum class LiveStatus { CONNECTING, PLAYING, ERROR }

// Filter logcat by this tag to diagnose live playback (e.g. the 2K/HD freeze):  adb logcat -s LiveRTSP
private const val LIVE_TAG = "LiveRTSP"

/**
 * In-app ring buffer of live-player diagnostics, so the user can read/share them from the app itself
 * (no adb/logcat needed). Everything also goes to logcat under [LIVE_TAG]. Small and bounded.
 */
object LiveLog {
    private const val CAP = 400
    val lines = mutableStateListOf<String>()
    private val fmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    fun add(msg: String) {
        lines.add("${java.time.LocalTime.now().format(fmt)}  $msg")
        while (lines.size > CAP) lines.removeAt(0)
        Log.i(LIVE_TAG, msg)
    }

    fun dump(): String = lines.joinToString("\n")
    fun clear() = lines.clear()
}

private fun playbackStateName(state: Int): String = when (state) {
    Player.STATE_IDLE -> "IDLE"
    Player.STATE_BUFFERING -> "BUFFERING"
    Player.STATE_READY -> "READY"
    Player.STATE_ENDED -> "ENDED"
    else -> "UNKNOWN($state)"
}

// A live RTSP connection can stall silently (buffering forever). If it hasn't started within this
// window we tear it down and reconnect automatically, up to a few times, instead of leaving the user
// on a frozen frame having to back out and re-enter.
private const val WATCHDOG_MS = 9_000L
private const val MAX_AUTO_RETRIES = 3

/**
 * Live view of the camera over RTSP (Media3). Works on the camera's local network: the app opens its
 * own RTSP connection straight to the camera (the camera tolerates this alongside the NVR's two
 * connections), so it doesn't depend on the NVR phone being up. Falls back to a setup prompt until
 * the camera's connection details have been entered.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(nav: androidx.navigation.NavHostController) {
    val context = LocalContext.current
    val cfg = remember { CameraConfigStore(context) }
    var hd by rememberSaveable { mutableStateOf(false) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    val url = remember(hd) { cfg.rtspUrl(hd) }

    // Hide the system bars while in fullscreen (restored automatically when leaving or on dispose).
    ImmersiveMode(enabled = fullscreen && url != null)

    when {
        url == null -> Scaffold(
            topBar = { LiveTopBar(stringResource(R.string.live_title), nav, showSettings = true) },
        ) { pad -> Box(Modifier.fillMaxSize().padding(pad)) { LiveSetupPrompt { nav.navigate("camera_settings") } } }

        fullscreen -> Box(Modifier.fillMaxSize().background(Color.Black)) {
            RtspLivePlayer(url, hd, onQuality = { hd = it }, fullscreen = true, onToggleFullscreen = { fullscreen = false })
        }

        else -> Scaffold(
            topBar = { LiveTopBar(stringResource(R.string.live_title), nav, showSettings = true) },
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) {
                RtspLivePlayer(url, hd, onQuality = { hd = it }, fullscreen = false, onToggleFullscreen = { fullscreen = true })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiveTopBar(title: String, nav: androidx.navigation.NavHostController, showSettings: Boolean) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = { nav.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
        },
        actions = {
            IconButton(onClick = { nav.navigate("live_logs") }) {
                Icon(Icons.Filled.BugReport, contentDescription = stringResource(R.string.live_logs_title))
            }
            if (showSettings) {
                IconButton(onClick = { nav.navigate("camera_settings") }) {
                    Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.camera_settings_title))
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primary,
            titleContentColor = MaterialTheme.colorScheme.onPrimary,
            navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
            actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}

@Composable
private fun LiveSetupPrompt(onSetup: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.live_configure_prompt),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onSetup) { Text(stringResource(R.string.live_configure_button)) }
    }
}

/** Toggles the system bars for an immersive fullscreen video. */
@Composable
private fun ImmersiveMode(enabled: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val window = (view.context as? Activity)?.window ?: return
    DisposableEffect(enabled) {
        val controller = WindowCompat.getInsetsController(window, view)
        if (enabled) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/**
 * The RTSP surface + on-video controls (quality SD/HD, mute, fullscreen) with connection-state
 * handling: a spinner while connecting, an automatic reconnect if it stalls (see [WATCHDOG_MS]), and
 * a manual retry once the auto-retries are exhausted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RtspLivePlayer(
    url: String,
    hd: Boolean,
    onQuality: (Boolean) -> Unit,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
) {
    val context = LocalContext.current
    var muted by rememberSaveable { mutableStateOf(true) }
    // retryKey/attempts reset when the URL changes (quality switch); status resets on each attempt.
    var retryKey by remember(url) { mutableIntStateOf(0) }
    var attempts by remember(url) { mutableIntStateOf(0) }
    var status by remember(url, retryKey) { mutableStateOf(LiveStatus.CONNECTING) }

    // Human-readable label for logs (never log the URL — it carries credentials).
    val quality = if (hd) "HD/ch0(2K)" else "SD/ch1(360p)"

    val player = remember(url, retryKey) {
        LiveLog.add("creating player quality=$quality attempt=$retryKey")
        // Small buffers biased toward low latency — this is a live feed, not a VOD file.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1_000, 5_000, 500, 1_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        ExoPlayer.Builder(context).setLoadControl(loadControl).build().apply {
            val source = RtspMediaSource.Factory()
                .setForceUseRtpTcp(true)               // TCP transport: reliable through Wi-Fi/NAT
                .setTimeoutMs(8_000)                   // fail fast instead of hanging -> triggers reconnect
                .createMediaSource(MediaItem.fromUri(url))
            setMediaSource(source)
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    LiveLog.add("[$quality] state=${playbackStateName(playbackState)}")
                    if (playbackState == Player.STATE_READY) status = LiveStatus.PLAYING
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    LiveLog.add("[$quality] isPlaying=$isPlaying")
                    if (isPlaying) status = LiveStatus.PLAYING
                }
                override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                    LiveLog.add("[$quality] videoSize=${videoSize.width}x${videoSize.height}")
                }
                override fun onRenderedFirstFrame() {
                    LiveLog.add("[$quality] first frame rendered")
                }
                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                    tracks.groups.forEach { g ->
                        for (i in 0 until g.length) {
                            val f = g.getTrackFormat(i)
                            LiveLog.add("[$quality] track mime=${f.sampleMimeType} ${f.width}x${f.height} selected=${g.isTrackSelected(i)} supported=${g.isTrackSupported(i)}")
                        }
                    }
                }
                override fun onPlayerError(e: PlaybackException) {
                    LiveLog.add("[$quality] ERROR code=${e.errorCodeName} msg=${e.message} cause=${e.cause}")
                    if (attempts < MAX_AUTO_RETRIES) { attempts++; retryKey++ } else status = LiveStatus.ERROR
                }
            })
            prepare()
            playWhenReady = true
        }
    }
    LaunchedEffect(player, muted) { player.volume = if (muted) 0f else 1f }
    DisposableEffect(player) { onDispose { player.release() } }

    // Watchdog: if it's still connecting after the window, reconnect (or give up to a manual retry).
    LaunchedEffect(url, retryKey) {
        delay(WATCHDOG_MS)
        if (status == LiveStatus.CONNECTING) {
            LiveLog.add("[$quality] watchdog: still CONNECTING after ${WATCHDOG_MS}ms, attempts=$attempts")
            if (attempts < MAX_AUTO_RETRIES) { attempts++; retryKey++ } else status = LiveStatus.ERROR
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                    keepScreenOn = true
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
        )

        when (status) {
            LiveStatus.CONNECTING -> Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(color = Color.White)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.live_connecting), color = Color.White)
            }
            LiveStatus.ERROR -> Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    stringResource(R.string.live_error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { LiveLog.add("[$quality] manual retry"); attempts = 0; retryKey++ }) { Text(stringResource(R.string.live_retry)) }
            }
            LiveStatus.PLAYING -> Unit
        }

        // On-video control bar over a scrim so it stays visible/findable on any frame.
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.4f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = !hd,
                onClick = { onQuality(false) },
                label = { Text(stringResource(R.string.live_quality_sd)) },
            )
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = hd,
                onClick = { onQuality(true) },
                label = { Text(stringResource(R.string.live_quality_hd)) },
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { muted = !muted }) {
                Icon(
                    if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = stringResource(if (muted) R.string.live_unmute else R.string.live_mute),
                    tint = Color.White,
                )
            }
            IconButton(onClick = onToggleFullscreen) {
                Icon(
                    if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                    contentDescription = stringResource(if (fullscreen) R.string.live_fullscreen_exit else R.string.live_fullscreen_enter),
                    tint = Color.White,
                )
            }
        }
    }
}

/** In-app viewer for the live-player diagnostics, with Share (e.g. to WhatsApp) and Clear. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveLogScreen(nav: androidx.navigation.NavHostController) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.live_logs_title)) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    androidx.compose.material3.TextButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, LiveLog.dump())
                        }
                        context.startActivity(Intent.createChooser(send, context.getString(R.string.log_share)))
                    }) { Text(stringResource(R.string.log_share)) }
                    androidx.compose.material3.TextButton(onClick = { LiveLog.clear() }) {
                        Text(stringResource(R.string.log_clear))
                    }
                },
            )
        },
    ) { pad ->
        if (LiveLog.lines.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.log_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            SelectionContainer {
                Column(
                    Modifier.fillMaxSize().padding(pad).padding(12.dp).verticalScroll(rememberScrollState()),
                ) {
                    LiveLog.lines.forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

/** One-time (editable) form for the camera's RTSP connection details, saved encrypted on-device. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraSettingsScreen(nav: androidx.navigation.NavHostController) {
    val context = LocalContext.current
    val cfg = remember { CameraConfigStore(context) }

    var host by rememberSaveable { mutableStateOf(cfg.host) }
    var port by rememberSaveable { mutableStateOf(if (cfg.port > 0) cfg.port.toString() else "554") }
    var user by rememberSaveable { mutableStateOf(cfg.user) }
    var password by rememberSaveable { mutableStateOf(cfg.password) }
    var showPassword by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.camera_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
        ) {
            Text(
                stringResource(R.string.camera_settings_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text(stringResource(R.string.camera_field_host)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = port,
                onValueChange = { new -> port = new.filter { it.isDigit() }.take(5) },
                label = { Text(stringResource(R.string.camera_field_port)) },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text(stringResource(R.string.camera_field_user)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.camera_field_pass)) },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    val label = if (showPassword) R.string.camera_pass_hide else R.string.camera_pass_show
                    androidx.compose.material3.TextButton(onClick = { showPassword = !showPassword }) {
                        Text(stringResource(label))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        if (host.isBlank()) {
                            Toast.makeText(context, context.getString(R.string.camera_host_required), Toast.LENGTH_SHORT).show()
                        } else {
                            cfg.save(host, port.toIntOrNull() ?: 554, user, password)
                            Toast.makeText(context, context.getString(R.string.camera_saved_toast), Toast.LENGTH_SHORT).show()
                            nav.popBackStack()
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.camera_save)) }
                OutlinedButton(
                    onClick = { cfg.clear(); host = ""; port = "554"; user = ""; password = "" },
                ) { Text(stringResource(R.string.camera_clear)) }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.camera_remote_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
