package com.famviva.camara.ui

import android.widget.Toast
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView
import com.famviva.camara.R
import com.famviva.camara.data.CameraConfigStore

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
    val url = remember(hd) { cfg.rtspUrl(hd) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.live_title)) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { nav.navigate("camera_settings") }) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.camera_settings_title))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            if (url == null) {
                LiveSetupPrompt { nav.navigate("camera_settings") }
            } else {
                RtspLivePlayer(url = url, hd = hd, onQuality = { hd = it })
            }
        }
    }
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

/** The RTSP surface + on-video controls (quality SD/HD, mute) and connection-error handling. */
@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun RtspLivePlayer(url: String, hd: Boolean, onQuality: (Boolean) -> Unit) {
    val context = LocalContext.current
    var muted by rememberSaveable { mutableStateOf(true) }
    var error by remember(url) { mutableStateOf(false) }
    var retryKey by remember { mutableIntStateOf(0) }

    val player = remember(url, retryKey) {
        ExoPlayer.Builder(context).build().apply {
            val source = RtspMediaSource.Factory()
                .setForceUseRtpTcp(true)               // TCP transport: reliable through Wi-Fi/NAT
                .createMediaSource(MediaItem.fromUri(url))
            setMediaSource(source)
            addListener(object : Player.Listener {
                override fun onPlayerError(e: PlaybackException) { error = true }
            })
            prepare()
            playWhenReady = true
        }
    }
    LaunchedEffect(player, muted) { player.volume = if (muted) 0f else 1f }
    DisposableEffect(player) { onDispose { player.release() } }

    Box(Modifier.fillMaxSize()) {
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

        if (error) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    stringResource(R.string.live_error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { error = false; retryKey++ }) { Text(stringResource(R.string.live_retry)) }
            }
        }

        // On-video control bar (quality + mute). Kept minimal and legible over dark frames.
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(12.dp),
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
                    if (muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                    contentDescription = stringResource(if (muted) R.string.live_unmute else R.string.live_mute),
                    tint = Color.White,
                )
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
        }
    }
}
