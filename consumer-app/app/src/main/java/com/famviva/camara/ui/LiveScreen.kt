package com.famviva.camara.ui

import android.app.Activity
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView
import com.famviva.camara.MainViewModel
import com.famviva.camara.R
import com.famviva.camara.data.CameraConfig
import com.famviva.camara.data.CameraConfigStore
import com.famviva.camara.data.isValidCameraId
import com.famviva.camara.media.ClipActions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class LiveStatus { CONNECTING, PLAYING, ERROR }

/** Placeholder route argument for "add a new camera" in the camera editor. */
const val NEW_CAMERA = "__new__"

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
 * Live view over RTSP (Media3). Works on the cameras' local network (or over Tailscale): the app opens
 * its own RTSP connection straight to each camera (a camera tolerates this alongside the NVR's two
 * connections), so it doesn't depend on the NVR phone being up.
 *
 * - No camera configured: a setup prompt.
 * - ONE camera (or [cameraId] given): the full single-camera player, exactly as before multi-camera.
 * - TWO OR MORE: a grid of light SD tiles; tapping one opens it full screen, where HD is available.
 *   Tiles stay on the 360p sub-stream on purpose: N x 2K would swamp the 2.4 GHz link the NVR records
 *   over, and over Tailscale every stream is relayed by the NVR phone's slow uplink.
 */
@Composable
fun LiveScreen(
    nav: androidx.navigation.NavHostController,
    cameraId: String? = null,
    labelOf: (String) -> String = { it },
    enabledOf: (String) -> Boolean = { true },
) {
    val context = LocalContext.current
    val cameras = remember { CameraConfigStore(context).cameras() }
    if (cameras.isEmpty()) {
        Scaffold(
            topBar = { LiveTopBar(stringResource(R.string.live_title), nav, showSettings = true) },
        ) { pad -> Box(Modifier.fillMaxSize().padding(pad)) { LiveSetupPrompt { nav.navigate("camera_settings") } } }
        return
    }
    val single = cameraId?.let { id -> cameras.firstOrNull { it.id == id } } ?: cameras.singleOrNull()
    if (single == null) {
        LiveGrid(cameras, nav, labelOf, enabledOf)
    } else {
        SingleCameraLive(single, nav, title = if (cameras.size > 1) labelOf(single.id) else null)
    }
}

/** The full player for one camera (quality, mute, snapshot, fullscreen). [title] names the camera
 *  when several exist; null keeps the plain "Live" title of a single-camera install. */
@Composable
private fun SingleCameraLive(cfg: CameraConfig, nav: androidx.navigation.NavHostController, title: String?) {
    var hd by rememberSaveable { mutableStateOf(false) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    val url = remember(cfg, hd) { cfg.rtspUrl(hd) }

    // Hide the system bars while in fullscreen (restored automatically when leaving or on dispose).
    ImmersiveMode(enabled = fullscreen)

    // ONE stable player position for the whole screen — it must survive both the quality switch and
    // the fullscreen toggle. Recreating the player (or moving it in the tree) breaks the video Surface
    // handoff: the new player reaches READY but never renders a frame -> frozen (the bug the logs
    // showed). So the chrome is drawn as overlays on top, and fullscreen only toggles them.
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        RtspLivePlayer(
            url = url,
            hd = hd,
            fullscreen = fullscreen,
            onQuality = { hd = it },
            onToggleFullscreen = { fullscreen = !fullscreen },
        )
        if (!fullscreen) LiveOverlayTopBar(nav, title ?: stringResource(R.string.live_title))
    }
}

/** 1 or 2 cameras stack full-width (portrait phone); 3-4 go 2x2. */
@Composable
private fun LiveGrid(
    cameras: List<CameraConfig>,
    nav: androidx.navigation.NavHostController,
    labelOf: (String) -> String,
    enabledOf: (String) -> Boolean,
) {
    val cols = if (cameras.size <= 2) 1 else 2
    Scaffold(
        topBar = { LiveTopBar(stringResource(R.string.live_title), nav, showSettings = true) },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(8.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            cameras.chunked(cols).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { cam ->
                        // A camera switched off in the app gets no RTSP connection from here either.
                        if (enabledOf(cam.id)) {
                            LiveTile(cam, labelOf(cam.id), Modifier.weight(1f)) { nav.navigate("live/${cam.id}") }
                        } else {
                            DisabledTile(labelOf(cam.id), Modifier.weight(1f))
                        }
                    }
                    repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Text(
                stringResource(R.string.live_grid_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

/**
 * One muted SD tile of the grid, with its OWN small player. Same hard-won rules as the full player:
 * "playing" means a frame was actually rendered, a watchdog reconnects a silent stall a few times, and
 * backgrounding the app FREES the camera (a zombie viewer competes with the NVR's recording sessions).
 * After the retry budget the tile says so and waits for a tap, instead of hammering an unplugged camera.
 */
@Composable
private fun LiveTile(cfg: CameraConfig, label: String, modifier: Modifier, onOpen: () -> Unit) {
    val context = LocalContext.current
    val tag = "${cfg.id} SD"
    var status by remember { mutableStateOf(LiveStatus.CONNECTING) }
    var retryKey by remember { mutableIntStateOf(0) }
    var attempts by remember { mutableIntStateOf(0) }
    var stoppedInBackground by remember { mutableStateOf(false) }
    val url = remember(cfg) { cfg.rtspUrl(hd = false) }

    val player = remember {
        LiveLog.add("[$tag] creating tile player")
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1_000, 5_000, 500, 1_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        ExoPlayer.Builder(context).setLoadControl(loadControl).build().apply {
            volume = 0f
            addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    LiveLog.add("[$tag] first frame rendered")
                    status = LiveStatus.PLAYING
                }
                override fun onPlayerError(e: PlaybackException) {
                    LiveLog.add("[$tag] ERROR code=${e.errorCodeName} msg=${e.message}")
                    if (stoppedInBackground) return
                    if (attempts < MAX_AUTO_RETRIES) { attempts++; retryKey++ } else status = LiveStatus.ERROR
                }
            })
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    LiveLog.add("[$tag] app backgrounded: releasing the RTSP session")
                    stoppedInBackground = true
                    player.stop()
                }
                Lifecycle.Event.ON_START -> if (stoppedInBackground) {
                    stoppedInBackground = false
                    attempts = 0
                    retryKey++
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(url, retryKey) {
        if (stoppedInBackground) return@LaunchedEffect
        status = LiveStatus.CONNECTING
        LiveLog.add("[$tag] loading attempt=$retryKey")
        val source = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)
            .setTimeoutMs(8_000)
            .createMediaSource(MediaItem.fromUri(url))
        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true
    }
    LaunchedEffect(url, retryKey) {
        delay(WATCHDOG_MS)
        if (status == LiveStatus.CONNECTING && !stoppedInBackground) {
            LiveLog.add("[$tag] watchdog: no frame after ${WATCHDOG_MS}ms, attempts=$attempts")
            if (attempts < MAX_AUTO_RETRIES) { attempts++; retryKey++ } else status = LiveStatus.ERROR
        }
    }

    Box(
        modifier
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black)
            .clickable {
                if (status == LiveStatus.ERROR) {
                    LiveLog.add("[$tag] manual retry"); attempts = 0; retryKey++
                } else {
                    onOpen()
                }
            },
    ) {
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
            LiveStatus.CONNECTING -> CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
            LiveStatus.ERROR -> Text(
                stringResource(R.string.live_tile_offline),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.Center).padding(16.dp),
            )
            LiveStatus.PLAYING -> Unit
        }
        TileLabel(label, Modifier.align(Alignment.TopStart))
    }
}

@Composable
private fun TileLabel(label: String, modifier: Modifier) {
        Text(
            label,
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            modifier = modifier
                .padding(8.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
}

/** Grey tile for a camera switched off in the app: no player, no connection attempts. */
@Composable
private fun DisabledTile(label: String, modifier: Modifier) {
    Box(
        modifier.aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Text(
            "📴 " + stringResource(R.string.live_tile_disabled),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.align(Alignment.Center),
        )
        TileLabel(label, Modifier.align(Alignment.TopStart))
    }
}

/** Translucent top bar drawn over the live video (used instead of a Scaffold app bar so toggling
 *  fullscreen doesn't move the player in the tree and recreate it). */
@Composable
private fun LiveOverlayTopBar(nav: androidx.navigation.NavHostController, title: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)))
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { nav.popBackStack() }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back), tint = Color.White)
        }
        Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { nav.navigate("live_logs") }) {
            Icon(Icons.Filled.BugReport, contentDescription = stringResource(R.string.live_logs_title), tint = Color.White)
        }
        IconButton(onClick = { nav.navigate("camera_settings") }) {
            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.camera_settings_title), tint = Color.White)
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
 * The RTSP surface + on-video controls (quality SD/HD, mute, fullscreen). Uses a SINGLE ExoPlayer for
 * the screen's whole life and swaps the media source on quality/retry changes — recreating the player
 * (as an earlier version did) broke the video Surface handoff, so the new player reached READY but
 * never rendered a frame and the picture froze. "Playing" is therefore signalled by the first frame
 * actually being rendered, not merely by READY; a watchdog reconnects if no frame arrives in time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RtspLivePlayer(
    url: String,
    hd: Boolean,
    fullscreen: Boolean,
    onQuality: (Boolean) -> Unit,
    onToggleFullscreen: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var muted by rememberSaveable { mutableStateOf(true) }
    var status by remember { mutableStateOf(LiveStatus.CONNECTING) }
    var retryKey by remember { mutableIntStateOf(0) }
    var attempts by remember { mutableIntStateOf(0) }
    // Kept so "take photo" can grab the current frame straight off the video surface (PixelCopy),
    // without touching the player/Surface handoff that the 2K-freeze fix stabilised.
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    val snapshotFailed = stringResource(R.string.live_snapshot_failed)
    // Current quality label for logs; updated in the load effect (never log the URL — it has creds).
    var quality by remember { mutableStateOf(if (hd) "HD/ch0(2K)" else "SD/ch1(360p)") }
    // Fresh views of the quality state for the (created-once) player listener: capturing the raw
    // params there would freeze them at first composition.
    val hdNow = rememberUpdatedState(hd)
    val onQualityNow = rememberUpdatedState(onQuality)
    // True while the app is backgrounded and we deliberately stopped the stream; gates the retry
    // machinery so nothing reconnects until the user comes back.
    var stoppedInBackground by remember { mutableStateOf(false) }

    val player = remember {
        LiveLog.add("creating single player")
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1_000, 5_000, 500, 1_000)   // small buffers: live feed, not VOD
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        ExoPlayer.Builder(context).setLoadControl(loadControl).build().apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    LiveLog.add("[$quality] state=${playbackStateName(playbackState)}")
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    LiveLog.add("[$quality] isPlaying=$isPlaying")
                }
                override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                    LiveLog.add("[$quality] videoSize=${videoSize.width}x${videoSize.height}")
                }
                override fun onRenderedFirstFrame() {
                    LiveLog.add("[$quality] first frame rendered")
                    status = LiveStatus.PLAYING            // the true "the picture is showing" signal
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
                    if (stoppedInBackground) return          // we stopped it on purpose; don't reconnect
                    when {
                        attempts < MAX_AUTO_RETRIES -> { attempts++; retryKey++ }
                        // HD kept failing: drop to SD instead of a dead-end error. Retrying 2K
                        // against a struggling link just multiplies the camera's load (each retry
                        // is a fresh RTSP session asking for a 2-4 Mbps burst) — the same spiral
                        // that froze live HD and knocked the NVR's recording over in July.
                        hdNow.value -> {
                            LiveLog.add("[$quality] HD keeps failing; auto-falling back to SD")
                            attempts = 0
                            onQualityNow.value(false)
                        }
                        else -> status = LiveStatus.ERROR
                    }
                }
            })
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(player, muted) { player.volume = if (muted) 0f else 1f }

    // Backgrounding must FREE the camera. Without this, Home/lock left the RTSP session streaming
    // invisibly (a zombie viewer): it burns data and keeps competing with the NVR's recording
    // sessions on the camera's weak Wi-Fi — the July "storm" windows all lined up with live-view
    // usage. Stop on ON_STOP (Media3 sends TEARDOWN), reconnect automatically on return.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    LiveLog.add("[$quality] app backgrounded: releasing the live RTSP session")
                    stoppedInBackground = true
                    player.stop()
                }
                Lifecycle.Event.ON_START -> if (stoppedInBackground) {   // ignore the initial ON_START
                    LiveLog.add("[$quality] app resumed: reconnecting live")
                    stoppedInBackground = false
                    attempts = 0
                    retryKey++                                           // triggers the (re)load effect
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // (Re)load on a quality change (url) or a retry — swapping the source on the SAME player.
    LaunchedEffect(url, retryKey) {
        if (stoppedInBackground) return@LaunchedEffect   // don't (re)connect while backgrounded
        quality = if (hd) "HD/ch0(2K)" else "SD/ch1(360p)"
        status = LiveStatus.CONNECTING
        LiveLog.add("loading quality=$quality attempt=$retryKey")
        val source = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)                   // TCP transport: reliable through Wi-Fi/NAT
            .setTimeoutMs(8_000)                       // fail fast instead of hanging -> triggers reconnect
            .createMediaSource(MediaItem.fromUri(url))
        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true
    }
    // Reset the auto-retry budget when the user switches quality.
    LaunchedEffect(url) { attempts = 0 }

    // Watchdog: no rendered frame within the window -> reconnect; after the retry budget, HD falls
    // back to SD (see onPlayerError) and only SD dead-ends into the manual-retry error state.
    LaunchedEffect(url, retryKey) {
        delay(WATCHDOG_MS)
        if (status == LiveStatus.CONNECTING && !stoppedInBackground) {
            LiveLog.add("[$quality] watchdog: no frame after ${WATCHDOG_MS}ms, attempts=$attempts")
            when {
                attempts < MAX_AUTO_RETRIES -> { attempts++; retryKey++ }
                hd -> {
                    LiveLog.add("[$quality] HD keeps failing; auto-falling back to SD")
                    attempts = 0
                    onQuality(false)
                }
                else -> status = LiveStatus.ERROR
            }
        }
    }

    // Grab the current frame off the video surface and save it to the gallery as a JPEG. PixelCopy
    // reads the SurfaceView directly, so it works with the hardware video surface (a plain
    // View.draw() would come back black).
    fun captureSnapshot() {
        val surface = playerView?.videoSurfaceView as? android.view.SurfaceView
        if (surface == null || surface.width <= 0 || surface.height <= 0) {
            Toast.makeText(context, snapshotFailed, Toast.LENGTH_SHORT).show()
            return
        }
        val bmp = android.graphics.Bitmap.createBitmap(
            surface.width, surface.height, android.graphics.Bitmap.Config.ARGB_8888,
        )
        LiveLog.add("snapshot: capturing ${surface.width}x${surface.height}")
        android.view.PixelCopy.request(surface, bmp, { result ->
            if (result == android.view.PixelCopy.SUCCESS) {
                scope.launch {
                    val name = "live_" + java.time.LocalDateTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".jpg"
                    val path = ClipActions.saveSnapshot(context, bmp, name)
                    Toast.makeText(
                        context,
                        if (path != null) context.getString(R.string.saved_to, path) else snapshotFailed,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } else {
                LiveLog.add("snapshot: PixelCopy failed result=$result")
                Toast.makeText(context, snapshotFailed, Toast.LENGTH_SHORT).show()
            }
        }, android.os.Handler(android.os.Looper.getMainLooper()))
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
                    playerView = this
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
                .windowInsetsPadding(WindowInsets.navigationBars)
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
            if (status == LiveStatus.PLAYING) {
                IconButton(onClick = { captureSnapshot() }) {
                    Icon(
                        Icons.Filled.PhotoCamera,
                        contentDescription = stringResource(R.string.live_snapshot),
                        tint = Color.White,
                    )
                }
            }
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

/**
 * Camera setup: every camera the app knows about (reported by the NVR, in the registry, or set up for
 * live view), each with its ON/OFF switch and display name. The switch is written to cameras.json on
 * Drive; the NVR's watchdog stops a switched-off camera within ~2 min (no retries, no alarms).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraSettingsScreen(vm: MainViewModel, nav: androidx.navigation.NavHostController) {
    val context = LocalContext.current
    val live = remember { CameraConfigStore(context).cameras() }
    val ids = vm.knownCameraIds(live.map { it.id })
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.camera_switch_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ids.forEach { id ->
                val cfg = live.firstOrNull { it.id == id }
                val enabled = vm.isCameraEnabled(id)
                Card(Modifier.fillMaxWidth().clickable { nav.navigate("camera_settings/$id") }) {
                    Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(vm.displayName(id), style = MaterialTheme.typography.titleMedium)
                            Text(
                                listOfNotNull(
                                    id.takeIf { vm.displayName(id) != id },
                                    cfg?.let { "${it.host}:${it.port}" } ?: stringResource(R.string.camera_no_live),
                                    stringResource(R.string.camera_off).takeIf { !enabled },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = enabled, onCheckedChange = { vm.setCameraEnabled(id, it) })
                    }
                }
            }
            Button(onClick = { nav.navigate("camera_settings/$NEW_CAMERA") }) {
                Text(stringResource(R.string.camera_add))
            }
            Text(
                stringResource(R.string.camera_remote_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One camera: display name (cameras.json, shared) + optional live-view RTSP details (encrypted,
 *  on-device). [cameraId] = [NEW_CAMERA] adds a camera, pre-named [suggestedName] if given; for an
 *  existing camera the id (its Drive folder) is fixed and the live details are optional. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraEditScreen(
    vm: MainViewModel,
    nav: androidx.navigation.NavHostController,
    cameraId: String,
    suggestedName: String? = null,
) {
    val context = LocalContext.current
    val store = remember { CameraConfigStore(context) }
    val isNew = cameraId == NEW_CAMERA
    val existing = remember { if (isNew) null else store.get(cameraId) }

    var name by rememberSaveable { mutableStateOf(if (isNew) suggestedName.orEmpty() else cameraId) }
    var label by rememberSaveable {
        mutableStateOf(if (isNew) "" else vm.registry.cameras[cameraId]?.label.orEmpty())
    }
    var host by rememberSaveable { mutableStateOf(existing?.host.orEmpty()) }
    var port by rememberSaveable { mutableStateOf((existing?.port ?: CameraConfigStore.DEFAULT_PORT).toString()) }
    var user by rememberSaveable { mutableStateOf(existing?.user.orEmpty()) }
    var password by rememberSaveable { mutableStateOf(existing?.password.orEmpty()) }
    var showPassword by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) stringResource(R.string.camera_new_title) else vm.displayName(cameraId)) },
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
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.trim() },
                enabled = isNew,
                label = { Text(stringResource(R.string.camera_field_name)) },
                supportingText = { Text(stringResource(R.string.camera_name_help)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text(stringResource(R.string.camera_field_label)) },
                supportingText = { Text(stringResource(R.string.camera_label_help)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.camera_live_section), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.camera_settings_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
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
                    val l = if (showPassword) R.string.camera_pass_hide else R.string.camera_pass_show
                    androidx.compose.material3.TextButton(onClick = { showPassword = !showPassword }) {
                        Text(stringResource(l))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        val msg = when {
                            !isValidCameraId(name) -> R.string.camera_name_invalid
                            isNew && host.isBlank() -> R.string.camera_host_required
                            isNew && (store.get(name) != null || name in vm.knownCameraIds()) -> R.string.camera_name_taken
                            else -> null
                        }
                        if (msg != null) {
                            Toast.makeText(context, context.getString(msg), Toast.LENGTH_SHORT).show()
                        } else {
                            if (host.isNotBlank()) {
                                store.save(CameraConfig(name, host.trim(), port.toIntOrNull() ?: CameraConfigStore.DEFAULT_PORT, user.trim(), password))
                            } else if (existing != null) {
                                store.remove(name)                    // live details cleared on purpose
                            }
                            if (label.trim() != vm.registry.cameras[name]?.label.orEmpty()) vm.setCameraLabel(name, label)
                            Toast.makeText(context, context.getString(R.string.camera_saved_toast), Toast.LENGTH_SHORT).show()
                            nav.popBackStack()
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.camera_save)) }
                if (existing != null) {
                    OutlinedButton(onClick = { store.remove(existing.id); nav.popBackStack() }) {
                        Text(stringResource(R.string.camera_remove_live))
                    }
                }
            }
        }
    }
}
