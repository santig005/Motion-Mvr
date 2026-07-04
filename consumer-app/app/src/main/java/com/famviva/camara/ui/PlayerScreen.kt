package com.famviva.camara.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.famviva.camara.R
import com.famviva.camara.data.Clip

/**
 * Plays the clip by progressively streaming the binary from Drive (?alt=media) with the
 * Authorization: Bearer <token> header. It works because the NVR muxes with +faststart
 * (the moov atom sits at the front of the mp4).
 */
@Composable
fun PlayerScreen(clip: Clip, tokenProvider: suspend () -> String, onBack: () -> Unit) {
    val context = LocalContext.current
    var token by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(clip.id) {
        try { token = tokenProvider() } catch (e: Exception) { error = e.message }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            error != null -> Text(stringResource(R.string.error_prefix, error!!), color = MaterialTheme.colorScheme.error)
            token == null -> CircularProgressIndicator()
            else -> VideoSurface(clip, token!!, context)
        }
    }
}

@Composable
private fun VideoSurface(clip: Clip, token: String, context: android.content.Context) {
    val player = remember(clip.id, token) {
        ExoPlayer.Builder(context).build().apply {
            val factory = DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(mapOf("Authorization" to "Bearer $token"))
            val source = ProgressiveMediaSource.Factory(factory)
                .createMediaSource(MediaItem.fromUri(clip.streamUrl))
            setMediaSource(source)
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                setShutterBackgroundColor(android.graphics.Color.BLACK)
            }
        },
    )
}
