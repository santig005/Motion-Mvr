package com.famviva.camara.ui

import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.famviva.camara.DateFilter
import com.famviva.camara.MainViewModel
import com.famviva.camara.R
import com.famviva.camara.data.Clip
import com.famviva.camara.data.DayPeriod
import com.famviva.camara.data.humanSize
import com.famviva.camara.data.prettyDate
import com.famviva.camara.data.relativeLabel
import com.famviva.camara.media.ClipActions
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun AppNav(
    drive: com.famviva.camara.data.DriveClient,
    seenStore: com.famviva.camara.data.SeenStore,
    offlineStore: com.famviva.camara.data.OfflineStore,
    clipListCache: com.famviva.camara.data.ClipListCache,
    tokenProvider: suspend () -> String,
) {
    val nav = rememberNavController()
    val vm: MainViewModel = viewModel(
        factory = MainViewModel.Factory(drive, seenStore, offlineStore, clipListCache, tokenProvider),
    )

    NavHost(navController = nav, startDestination = "days") {
        composable("days") { DaysScreen(vm, nav) }
        composable("storage") { StorageScreen(vm, nav) }
        composable("clips/{day}") { entry ->
            val day = entry.arguments?.getString("day").orEmpty()
            ClipsScreen(vm, nav, day, tokenProvider)
        }
        composable("player/{id}") { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            val clip = vm.find(id)
            // Opening the player marks the clip as seen.
            LaunchedEffect(id) { vm.markSeen(id) }
            if (clip != null) PlayerScreen(clip, vm.localFileOrNull(clip), tokenProvider) { nav.popBackStack() }
            else CenteredText(stringResource(R.string.clip_not_found))
        }
    }
}

/** Top-bar toggle for auto-downloading new clips for offline playback (Wi-Fi only). */
@Composable
private fun AutoDownloadToggle(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val context = LocalContext.current
    val toastText = stringResource(
        if (enabled) R.string.auto_download_disabled_toast else R.string.auto_download_enabled_toast,
    )
    TextButton(onClick = {
        onToggle(!enabled)
        Toast.makeText(context, toastText, Toast.LENGTH_SHORT).show()
    }) {
        Text(
            "📥 " + stringResource(if (enabled) R.string.auto_download_on else R.string.auto_download_off),
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Top-bar language switcher (English / Spanish), applied at runtime via AppCompat locales. */
@Composable
private fun LanguageMenu() {
    var expanded by remember { mutableStateOf(false) }
    val tags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    val lang = if (tags.isNotEmpty()) tags else Locale.getDefault().language
    val isSpanish = lang.startsWith("es")
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(if (isSpanish) "ES" else "EN", fontWeight = FontWeight.SemiBold)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.lang_english)) },
                onClick = {
                    expanded = false
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.lang_spanish)) },
                onClick = {
                    expanded = false
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("es"))
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DaysScreen(vm: MainViewModel, nav: NavHostController) {
    LaunchedEffect(Unit) { if (!vm.loadedOnce) vm.load() }

    val days = vm.clipsByDay()
    val totalEvents = days.sumOf { it.second.size }
    val totalBytes = days.sumOf { pair -> pair.second.sumOf { it.sizeBytes } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    TextButton(onClick = { nav.navigate("storage") }) { Text("💾") }
                    AutoDownloadToggle(vm.autoDownloadEnabled, vm::setAutoDownload)
                    LanguageMenu()
                    IconButton(onClick = { vm.load() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh))
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            ChipsRow {
                DateFilter.entries.forEach { f ->
                    FilterChip(
                        selected = vm.dateFilter == f,
                        onClick = { vm.selectDateFilter(f) },
                        label = { Text(stringResource(f.labelRes)) },
                    )
                    Spacer(Modifier.width(8.dp))
                }
            }

            vm.cameraHealth.forEach { h -> CameraStatusCard(h) }

            if (vm.loadedOnce && !vm.loading) {
                Text(
                    text = pluralStringResource(R.plurals.events, totalEvents, totalEvents) +
                        " · " + humanSize(totalBytes),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            PullToRefreshBox(
                isRefreshing = vm.loading,
                onRefresh = { vm.load() },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    when {
                        vm.error != null && days.isEmpty() ->
                            item {
                                Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                                    ErrorBox(vm.error!!) { vm.load() }
                                }
                            }
                        days.isEmpty() && vm.loadedOnce && !vm.loading ->
                            item {
                                Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(stringResource(R.string.no_events_filter))
                                }
                            }
                        else ->
                            items(days, key = { it.first }) { (day, dayClips) ->
                                DayCard(day, dayClips, vm.newCountOf(day)) { nav.navigate("clips/$day") }
                            }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayCard(day: String, clips: List<Clip>, newCount: Int, onClick: () -> Unit) {
    val context = LocalContext.current
    val bytes = clips.sumOf { it.sizeBytes }
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(prettyDate(context, day), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    pluralStringResource(R.plurals.events, clips.size, clips.size) + " · " + humanSize(bytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (newCount > 0) {
                Badge(containerColor = MaterialTheme.colorScheme.primary) {
                    Text(pluralStringResource(R.plurals.badge_new, newCount, newCount))
                }
                Spacer(Modifier.width(8.dp))
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Device storage used by offline (downloaded) clips, by day, with device-only deletion — never
 * touches the Drive originals (deleting there needs a broader OAuth scope, out of scope here).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StorageScreen(vm: MainViewModel, nav: NavHostController) {
    val context = LocalContext.current
    val byDay = vm.offlineSizeByDay()
    val totalBytes = vm.offlineTotalBytes()
    val maxBytes = (byDay.maxOfOrNull { it.second } ?: 1L).coerceAtLeast(1L)
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var confirmDeleteDay by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.storage_title)) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp)) {
            Text(
                stringResource(R.string.storage_offline_total, humanSize(totalBytes)),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.storage_offline_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            if (byDay.isEmpty()) {
                Text(stringResource(R.string.storage_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(byDay, key = { it.first }) { (day, bytes) ->
                        StorageDayRow(
                            label = prettyDate(context, day),
                            bytes = bytes,
                            fraction = bytes.toFloat() / maxBytes.toFloat(),
                            onDelete = { confirmDeleteDay = day },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = { confirmDeleteAll = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.storage_delete_all))
                }
            }
        }
    }

    if (confirmDeleteAll) {
        ConfirmDialog(
            title = stringResource(R.string.storage_delete_all_title),
            body = stringResource(R.string.storage_delete_all_body),
            onConfirm = { vm.deleteAllOffline(); confirmDeleteAll = false },
            onDismiss = { confirmDeleteAll = false },
        )
    }
    confirmDeleteDay?.let { day ->
        ConfirmDialog(
            title = stringResource(R.string.storage_delete_day_title, prettyDate(context, day)),
            body = stringResource(R.string.storage_delete_day_body),
            onConfirm = { vm.deleteOfflineDay(day); confirmDeleteDay = null },
            onDismiss = { confirmDeleteDay = null },
        )
    }
}

/** One day's offline footprint: label, a single-hue magnitude bar (relative to the day with the
 *  most), the size, and a delete action for that day's offline copies. */
@Composable
private fun StorageDayRow(label: String, bytes: Long, fraction: Float, onDelete: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(humanSize(bytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = onDelete) { Text("✕") }
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun ConfirmDialog(title: String, body: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClipsScreen(
    vm: MainViewModel,
    nav: NavHostController,
    day: String,
    tokenProvider: suspend () -> String,
) {
    val context = LocalContext.current
    var newestFirst by rememberSaveable { mutableStateOf(true) }
    var periodName by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedPeriod = periodName?.let { name -> DayPeriod.entries.firstOrNull { it.name == name } }

    // Token to authorize thumbnail loading and downloads (Drive).
    var token by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { token = runCatching { tokenProvider() }.getOrNull() }

    // Clip selected with a long press (for the share/download menu).
    var actionClip by remember { mutableStateOf<Clip?>(null) }

    val clips = vm.clipsOf(day)
        .filter { selectedPeriod == null || it.period == selectedPeriod }
        .let { list -> if (newestFirst) list.sortedByDescending { it.name } else list.sortedBy { it.name } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(prettyDate(context, day)) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { newestFirst = !newestFirst }) {
                        Icon(
                            if (newestFirst) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                            contentDescription = stringResource(
                                if (newestFirst) R.string.sort_newest_first else R.string.sort_oldest_first,
                            ),
                        )
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            ChipsRow {
                FilterChip(
                    selected = selectedPeriod == null,
                    onClick = { periodName = null },
                    label = { Text(stringResource(R.string.period_all)) },
                )
                Spacer(Modifier.width(8.dp))
                DayPeriod.entries.forEach { p ->
                    FilterChip(
                        selected = selectedPeriod == p,
                        onClick = { periodName = if (selectedPeriod == p) null else p.name },
                        label = { Text("${p.emoji} ${stringResource(p.labelRes)}") },
                    )
                    Spacer(Modifier.width(8.dp))
                }
            }

            PullToRefreshBox(
                isRefreshing = vm.loading,
                onRefresh = { vm.load() },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (clips.isEmpty()) {
                        item {
                            Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.no_events_period))
                            }
                        }
                    } else {
                        items(clips, key = { it.id }) { clip ->
                            ClipCard(
                                clip = clip,
                                token = token,
                                isNew = vm.isNew(clip),
                                isDownloaded = vm.isDownloaded(clip),
                                onClick = { nav.navigate("player/${clip.id}") },
                                onLongClick = { actionClip = clip },
                            )
                        }
                    }
                }
            }
        }
    }

    actionClip?.let { clip ->
        ClipActionsSheet(
            clip = clip,
            token = token,
            isDownloaded = vm.isDownloaded(clip),
            onRemoveOffline = { vm.deleteOfflineCopy(clip) },
            onDismiss = { actionClip = null },
        )
    }
}

/** Bottom sheet with actions (share / download) for a clip; shown on long press. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClipActionsSheet(
    clip: Clip,
    token: String?,
    isDownloaded: Boolean,
    onRemoveOffline: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = { if (!working) onDismiss() }) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                "${prettyDate(context, clip.dateKey ?: "")} · ${clip.time}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            // Doesn't need a token: it only touches the local (device) copy, never Drive.
            if (isDownloaded) {
                SheetAction(stringResource(R.string.action_remove_offline)) {
                    onRemoveOffline()
                    onDismiss()
                }
            }
            if (working || token == null) {
                Row(
                    Modifier.fillMaxWidth().padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Text(stringResource(if (token == null) R.string.authorizing else R.string.processing))
                }
            } else {
                val shareFailed = stringResource(R.string.share_failed)
                val downloadFailed = stringResource(R.string.download_failed)
                SheetAction(stringResource(R.string.action_share)) {
                    scope.launch {
                        working = true
                        val ok = ClipActions.shareClip(context, clip, token)
                        working = false
                        if (ok) onDismiss()
                        else Toast.makeText(context, shareFailed, Toast.LENGTH_SHORT).show()
                    }
                }
                SheetAction(stringResource(R.string.action_download)) {
                    scope.launch {
                        working = true
                        val path = ClipActions.saveToGallery(context, clip, token)
                        working = false
                        Toast.makeText(
                            context,
                            if (path != null) context.getString(R.string.saved_to, path) else downloadFailed,
                            Toast.LENGTH_LONG,
                        ).show()
                        if (path != null) onDismiss()
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 16.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ClipCard(
    clip: Clip,
    token: String?,
    isNew: Boolean,
    isDownloaded: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Column {
            // Large 16:9 preview with overlays (NEW, duration, play).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(clip.period?.emoji ?: "🎬", style = MaterialTheme.typography.displaySmall)
                val thumb = clip.thumbUrl
                if (thumb != null && token != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(thumb)
                            .addHeader("Authorization", "Bearer $token")
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = stringResource(R.string.action_play),
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(48.dp),
                )
                if (isNew) {
                    OverlayChip(
                        text = stringResource(R.string.overlay_new),
                        bg = MaterialTheme.colorScheme.primary,
                        fg = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                    )
                }
                if (isDownloaded) {
                    OverlayChip(
                        text = stringResource(R.string.overlay_offline),
                        bg = Color.Black.copy(alpha = 0.6f),
                        fg = Color.White,
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                    )
                }
                clip.durationLabel?.let { dur ->
                    OverlayChip(
                        text = dur,
                        bg = Color.Black.copy(alpha = 0.6f),
                        fg = Color.White,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                    )
                }
            }
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isNew) {
                        Box(
                            Modifier.size(8.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        clip.time,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (isNew) FontWeight.Bold else FontWeight.Medium,
                    )
                    Spacer(Modifier.weight(1f))
                    clip.localDateTime?.let {
                        Text(
                            relativeLabel(context, it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    clip.period?.let {
                        Text(
                            "${it.emoji} ${stringResource(it.labelRes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    clip.intensityLevel?.let { IntensityBars(it); Spacer(Modifier.width(8.dp)) }
                    Text(
                        clip.sizeMb,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Motion-intensity indicator: 5 "signal" bars, coloured by level
 * (1 faint grey → 5 strong red). Bars above the level are dimmed.
 */
@Composable
private fun IntensityBars(level: Int) {
    val color = when (level) {
        5 -> Color(0xFFE53935)   // strong
        4 -> Color(0xFFF59E0B)   // notable
        3 -> Color(0xFFC0CA33)   // moderate
        2 -> Color(0xFF43A047)   // light (shadow/bug/background)
        else -> Color(0xFF9E9E9E) // very faint / noise
    }
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 1..5) {
            Box(
                Modifier
                    .width(3.5.dp)
                    .height((5 + i * 2).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (i <= level) color else color.copy(alpha = 0.20f)),
            )
        }
    }
}

/** Small pill overlaid on the thumbnail (NEW / duration). */
@Composable
private fun OverlayChip(text: String, bg: Color, fg: Color, modifier: Modifier = Modifier) {
    Surface(color = bg, shape = RoundedCornerShape(6.dp), modifier = modifier) {
        Text(
            text,
            color = fg,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun ChipsRow(content: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun ErrorBox(message: String, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(24.dp),
    ) {
        Text(stringResource(R.string.error_prefix, message), color = MaterialTheme.colorScheme.error)
        Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
    }
}

@Composable
private fun CenteredText(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text) }
}

/**
 * NVR/camera status (from status.json). Shows the most serious problem as a coloured banner, or a
 * discreet info line (battery + last signal) when everything is fine.
 */
@Composable
private fun CameraStatusCard(h: com.famviva.camara.data.CameraHealth) {
    val context = LocalContext.current
    val now = System.currentTimeMillis() / 1000
    val etaTxt = h.etaLabel(context)
    val battTxt = h.battery?.let {
        "🔋 $it%${if (h.charging == true) " ⚡" else ""}" + (etaTxt?.let { e -> " · ~$e" } ?: "")
    }
    when {
        !h.ok -> StatusBanner(
            bg = MaterialTheme.colorScheme.errorContainer,
            fg = MaterialTheme.colorScheme.onErrorContainer,
            title = stringResource(R.string.status_down_title, h.camera),
            body = stringResource(R.string.status_down_body),
        )
        h.isStale(now) -> StatusBanner(
            bg = MaterialTheme.colorScheme.errorContainer,
            fg = MaterialTheme.colorScheme.onErrorContainer,
            title = stringResource(R.string.status_stale_title, h.camera),
            body = stringResource(R.string.status_stale_body, h.sinceLabel(context, now)),
        )
        h.etaCritical() -> StatusBanner(
            bg = MaterialTheme.colorScheme.errorContainer,
            fg = MaterialTheme.colorScheme.onErrorContainer,
            title = stringResource(R.string.status_critical_title, etaTxt ?: ""),
            body = stringResource(R.string.status_critical_body),
        )
        h.lowBattery -> StatusBanner(
            bg = Color(0xFFFFE0B2),
            fg = Color(0xFF6D4C00),
            title = stringResource(R.string.status_lowbatt_title, h.battery ?: 0),
            body = stringResource(R.string.status_lowbatt_body),
        )
        else -> Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.status_recording, h.camera) + (battTxt?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                h.sinceLabel(context, now),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusBanner(bg: Color, fg: Color, title: String, body: String) {
    Surface(
        color = bg,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = fg)
            Text(body, style = MaterialTheme.typography.bodySmall, color = fg)
        }
    }
}
