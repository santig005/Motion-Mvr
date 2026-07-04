package com.famviva.camara.ui

import android.content.Context
import android.net.Uri
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
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.famviva.camara.R
import com.famviva.camara.data.Clip
import java.io.File

/**
 * Plays the clip from a local file if it's already downloaded (offline, instant, no auth needed);
 * otherwise streams the binary from Drive (?alt=media) with the Authorization: Bearer <token>
 * header. Streaming works because the NVR muxes with +faststart (the moov atom sits at the front
 * of the mp4), so progressive playback starts immediately.
 */
@Composable
fun PlayerScreen(clip: Clip, localFile: File?, tokenProvider: suspend () -> String, onBack: () -> Unit) {
    val context = LocalContext.current
    var token by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(clip.id, localFile) {
        if (localFile == null) {
            try { token = tokenProvider() } catch (e: Exception) { error = e.message }
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            localFile != null -> {
                val factory = remember { DefaultDataSource.Factory(context) }
                VideoSurface(MediaItem.fromUri(Uri.fromFile(localFile)), factory, clip.id to "local", context)
            }
            error != null -> Text(stringResource(R.string.error_prefix, error!!), color = MaterialTheme.colorScheme.error)
            token == null -> CircularProgressIndicator()
            else -> {
                val t = token!!
                val factory = remember(t) {
                    DefaultHttpDataSource.Factory().setDefaultRequestProperties(mapOf("Authorization" to "Bearer $t"))
                }
                VideoSurface(MediaItem.fromUri(clip.streamUrl), factory, clip.id to "remote", context)
            }
        }
    }
}

@Composable
private fun VideoSurface(mediaItem: MediaItem, dataSourceFactory: DataSource.Factory, key: Any, context: Context) {
    val player = remember(key) {
        ExoPlayer.Builder(context).build().apply {
            val source = ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
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
