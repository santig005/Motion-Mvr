package com.famviva.camara.ui

import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.famviva.camara.data.AutoDownloadMode
import com.famviva.camara.data.BatterySample
import com.famviva.camara.data.Clip
import com.famviva.camara.data.DayPeriod
import com.famviva.camara.data.axisTimeLabel
import com.famviva.camara.data.clockLabel
import com.famviva.camara.data.epochLabel
import com.famviva.camara.data.estimateBatteryEtaMinutes
import com.famviva.camara.data.evaluateBatteryForecast
import com.famviva.camara.data.formatEta
import com.famviva.camara.data.humanSize
import com.famviva.camara.data.prettyDate
import com.famviva.camara.data.relativeLabel
import com.famviva.camara.data.uploadDelayLabel
import com.famviva.camara.media.ClipActions
import com.famviva.camara.ui.theme.status
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
fun AppNav(
    drive: com.famviva.camara.data.DriveClient,
    seenStore: com.famviva.camara.data.SeenStore,
    offlineStore: com.famviva.camara.data.OfflineStore,
    clipListCache: com.famviva.camara.data.ClipListCache,
    batteryHistory: com.famviva.camara.data.BatteryHistoryStore,
    favoritesStore: com.famviva.camara.data.FavoritesStore,
    tokenProvider: suspend () -> String,
) {
    val nav = rememberNavController()
    val vm: MainViewModel = viewModel(
        factory = MainViewModel.Factory(drive, seenStore, offlineStore, clipListCache, batteryHistory, favoritesStore, tokenProvider),
    )

    NavHost(navController = nav, startDestination = "days") {
        composable("days") { DaysScreen(vm, nav) }
        composable("live") { LiveScreen(nav) }
        composable("camera_settings") { CameraSettingsScreen(nav) }
        composable("favorites") { FavoritesScreen(vm, nav, tokenProvider) }
        composable("storage") { StorageScreen(vm, nav) }
        composable("battery/{camera}") { entry ->
            BatteryScreen(vm, nav, entry.arguments?.getString("camera").orEmpty())
        }
        composable("storage_day/{section}/{day}") { entry ->
            val section = entry.arguments?.getString("section")
                ?.let { runCatching { StorageSection.valueOf(it) }.getOrNull() } ?: StorageSection.LOCAL
            val day = entry.arguments?.getString("day").orEmpty()
            StorageDayScreen(vm, nav, section, day, tokenProvider)
        }
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

/**
 * Top-bar control for auto-downloading new clips for offline playback. A dropdown (like the language
 * switch) rather than a blind one-tap toggle: the current policy is always visible (checkmark) and
 * changing it is an explicit two-step choice. Three states — Off / Wi-Fi only / Wi-Fi + mobile data
 * — so the user can opt into using mobile data while away from Wi-Fi.
 */
@Composable
private fun AutoDownloadMenu(mode: AutoDownloadMode, onSelect: (AutoDownloadMode) -> Unit) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                if (mode != AutoDownloadMode.OFF) Icons.Filled.DownloadDone else Icons.Filled.Download,
                contentDescription = stringResource(R.string.auto_download_menu_title),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Text(
                stringResource(R.string.auto_download_menu_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            fun pick(target: AutoDownloadMode, toastRes: Int) {
                expanded = false
                if (mode != target) {
                    onSelect(target)
                    Toast.makeText(context, context.getString(toastRes), Toast.LENGTH_SHORT).show()
                }
            }
            AutoDownloadOption(R.string.auto_download_off, selected = mode == AutoDownloadMode.OFF) {
                pick(AutoDownloadMode.OFF, R.string.auto_download_disabled_toast)
            }
            AutoDownloadOption(R.string.auto_download_wifi, selected = mode == AutoDownloadMode.WIFI_ONLY) {
                pick(AutoDownloadMode.WIFI_ONLY, R.string.auto_download_wifi_toast)
            }
            AutoDownloadOption(R.string.auto_download_data, selected = mode == AutoDownloadMode.WIFI_AND_DATA) {
                pick(AutoDownloadMode.WIFI_AND_DATA, R.string.auto_download_data_toast)
            }
        }
    }
}

@Composable
private fun AutoDownloadOption(labelRes: Int, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(labelRes)) },
        onClick = onClick,
        leadingIcon = {
            if (selected) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            } else {
                Spacer(Modifier.size(24.dp))
            }
        },
    )
}

/** Top-bar language switcher (English / Spanish), applied at runtime via AppCompat locales. */
@Composable
private fun LanguageMenu() {
    var expanded by remember { mutableStateOf(false) }
    val tags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    val lang = if (tags.isNotEmpty()) tags else Locale.getDefault().language
    val isSpanish = lang.startsWith("es")
    Box {
        TextButton(
            onClick = { expanded = true },
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimary),
        ) {
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
                title = {
                    Text(
                        stringResource(R.string.app_name),
                        fontWeight = FontWeight.Bold,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                actions = {
                    IconButton(onClick = { nav.navigate("live") }) {
                        Icon(Icons.Filled.Videocam, contentDescription = stringResource(R.string.live_title))
                    }
                    AutoDownloadMenu(vm.autoDownloadMode, vm::setAutoDownloadMode)
                    IconButton(onClick = { nav.navigate("favorites") }) {
                        Icon(Icons.Filled.Star, contentDescription = stringResource(R.string.favorites_title))
                    }
                    IconButton(onClick = { nav.navigate("storage") }) {
                        Icon(Icons.Filled.Storage, contentDescription = stringResource(R.string.storage_title))
                    }
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

            vm.cameraHealth.forEach { h ->
                CameraStatusCard(h, onOpenBattery = if (h.battery != null) ({ nav.navigate("battery/${h.camera}") }) else null)
            }

            if (vm.loadedOnce && !vm.loading) {
                val avgDelay = vm.avgUploadDelaySeconds()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = pluralStringResource(R.plurals.events, totalEvents, totalEvents) +
                            " · " + humanSize(totalBytes),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (avgDelay != null) {
                        Spacer(Modifier.weight(1f))
                        Icon(
                            Icons.Filled.CloudDone,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            stringResource(R.string.avg_upload_delay, uploadDelayLabel(avgDelay)),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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

/** Which storage footprint the screen is showing: the local (downloaded) cache or the full Drive
 *  footprint. Passed through navigation to the day-detail screen, so it must be a stable name. */
enum class StorageSection { LOCAL, DRIVE }

/** One donut arc / legend entry. */
private data class DonutSlice(val value: Long, val color: Color)

/**
 * Storage overview with two switchable sections:
 *  - LOCAL: what's downloaded on this device (deletable here, never touches Drive).
 *  - DRIVE: the full cloud footprint across every clip (read-only from the app).
 * Each section shows a per-day donut (top days by size, the rest folded into "Other") with a
 * labelled legend — tapping a day opens its clips, sortable by size or time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StorageScreen(vm: MainViewModel, nav: NavHostController) {
    val context = LocalContext.current
    var section by rememberSaveable { mutableStateOf(StorageSection.LOCAL) }
    val palette = MaterialTheme.status.chart
    val otherColor = MaterialTheme.status.chartOther

    val isLocal = section == StorageSection.LOCAL
    val byDay = if (isLocal) vm.offlineSizeByDay() else vm.driveSizeByDay()
    val totalBytes = if (isLocal) vm.offlineTotalBytes() else vm.driveTotalBytes()

    // Largest days keep a categorical hue (fixed slot order); everything past the palette folds
    // into a single neutral "Other" slice so the donut never sprouts dozens of thin wedges.
    val ranked = byDay.sortedByDescending { it.second }
    val head = ranked.take(palette.size)
    val tailBytes = ranked.drop(palette.size).sumOf { it.second }
    val slices = buildList {
        head.forEachIndexed { i, (_, bytes) -> add(DonutSlice(bytes, palette[i])) }
        if (tailBytes > 0) add(DonutSlice(tailBytes, otherColor))
    }

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
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(8.dp))
            StorageSectionSelector(section) { section = it }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(
                    if (isLocal) R.string.storage_offline_hint else R.string.storage_drive_hint,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            val emptyMsg = if (isLocal) R.string.storage_empty else R.string.storage_drive_empty
            if (byDay.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(emptyMsg), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    StorageDonut(
                        slices = slices,
                        centerTop = humanSize(totalBytes),
                        centerBottom = stringResource(
                            if (isLocal) R.string.storage_section_local else R.string.storage_section_drive,
                        ),
                    )
                }
                Spacer(Modifier.height(8.dp))

                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    itemsIndexed(head, key = { _, it -> it.first }) { i, (day, bytes) ->
                        StorageLegendRow(
                            color = palette[i],
                            label = prettyDate(context, day),
                            bytes = bytes,
                            percent = percentLabel(context, bytes, totalBytes),
                            onClick = { nav.navigate("storage_day/${section.name}/$day") },
                            onDelete = if (isLocal) ({ confirmDeleteDay = day }) else null,
                        )
                    }
                    if (tailBytes > 0) {
                        item(key = "__other__") {
                            StorageLegendRow(
                                color = otherColor,
                                label = stringResource(R.string.storage_other),
                                bytes = tailBytes,
                                percent = percentLabel(context, tailBytes, totalBytes),
                                onClick = null,
                                onDelete = null,
                            )
                        }
                    }
                }

                if (isLocal) {
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { confirmDeleteAll = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.storage_delete_all))
                    }
                } else {
                    Text(
                        stringResource(R.string.storage_drive_readonly),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
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

/** "42%" / "<1%" of the total, localized. */
private fun percentLabel(context: android.content.Context, bytes: Long, total: Long): String {
    if (total <= 0) return context.getString(R.string.storage_percent, 0)
    val pct = (bytes * 100.0 / total)
    return if (pct < 1.0 && bytes > 0) context.getString(R.string.storage_percent_lt1)
    else context.getString(R.string.storage_percent, pct.toInt())
}

/** [En el teléfono] / [En Drive] two-way segmented control. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StorageSectionSelector(section: StorageSection, onSelect: (StorageSection) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = section == StorageSection.LOCAL,
            onClick = { onSelect(StorageSection.LOCAL) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
        ) { Text(stringResource(R.string.storage_section_local)) }
        SegmentedButton(
            selected = section == StorageSection.DRIVE,
            onClick = { onSelect(StorageSection.DRIVE) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
        ) { Text(stringResource(R.string.storage_section_drive)) }
    }
}

/** Donut of per-day slices with the total in the middle (Google-One style). Slices are separated
 *  by a small surface gap so adjacent days stay distinct even for colour-vision deficiencies. */
@Composable
private fun StorageDonut(slices: List<DonutSlice>, centerTop: String, centerBottom: String) {
    Box(Modifier.size(190.dp), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val strokePx = 30.dp.toPx()
            val d = size.minDimension - strokePx
            val topLeft = androidx.compose.ui.geometry.Offset((size.width - d) / 2f, (size.height - d) / 2f)
            val arcSize = androidx.compose.ui.geometry.Size(d, d)
            val total = slices.sumOf { it.value }.coerceAtLeast(1L).toFloat()
            val gap = if (slices.size > 1) 3f else 0f
            var start = -90f
            slices.forEach { s ->
                val sweep = 360f * (s.value.toFloat() / total)
                drawArc(
                    color = s.color,
                    startAngle = start + gap / 2f,
                    sweepAngle = (sweep - gap).coerceAtLeast(0.5f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = strokePx,
                        cap = androidx.compose.ui.graphics.StrokeCap.Butt,
                    ),
                )
                start += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(centerTop, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                centerBottom,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Legend entry: colour dot + day label + share-of-total + size, with an optional delete action.
 *  The label is the direct-label mitigation for the palette's floor-band CVD/contrast — identity
 *  is never carried by colour alone. */
@Composable
private fun StorageLegendRow(
    color: Color,
    label: String,
    bytes: Long,
    percent: String,
    onClick: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            percent,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            humanSize(bytes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onDelete != null) {
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        } else if (onClick != null) {
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A single day's clips (from one storage section), sortable by size or by time; tap plays. In the
 *  On-device section each row can also be deleted individually (local copy only). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StorageDayScreen(
    vm: MainViewModel,
    nav: NavHostController,
    section: StorageSection,
    day: String,
    tokenProvider: suspend () -> String,
) {
    val context = LocalContext.current
    var sortBySize by rememberSaveable { mutableStateOf(true) }
    var token by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { token = runCatching { tokenProvider() }.getOrNull() }

    val isLocal = section == StorageSection.LOCAL
    val base = if (isLocal) vm.clipsOf(day).filter { vm.isDownloaded(it) } else vm.clipsOf(day)
    val clips = if (sortBySize) base.sortedByDescending { it.sizeBytes } else base.sortedByDescending { it.name }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(prettyDate(context, day)) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            ChipsRow {
                FilterChip(
                    selected = sortBySize,
                    onClick = { sortBySize = true },
                    label = { Text(stringResource(R.string.storage_sort_size)) },
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = !sortBySize,
                    onClick = { sortBySize = false },
                    label = { Text(stringResource(R.string.storage_sort_time)) },
                )
            }
            if (clips.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.storage_day_empty_local), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    items(clips, key = { it.id }) { clip ->
                        StorageClipRow(
                            clip = clip,
                            token = token,
                            downloaded = vm.isDownloaded(clip),
                            onClick = { nav.navigate("player/${clip.id}") },
                            // Local section: remove just this clip's device copy (Drive untouched).
                            onDelete = if (isLocal) ({ vm.deleteOfflineCopy(clip) }) else null,
                        )
                    }
                }
            }
        }
    }
}

/** Compact clip row for the storage day detail: thumbnail, time, period, intensity, size, offline
 *  marker, and (for the on-device section) a per-clip delete. */
@Composable
private fun StorageClipRow(
    clip: Clip,
    token: String?,
    downloaded: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(64.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Videocam,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier.size(20.dp),
            )
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
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(clip.time, style = MaterialTheme.typography.titleSmall)
            clip.period?.let {
                Text(
                    stringResource(it.labelRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        clip.intensityLevel?.let { IntensityBars(it); Spacer(Modifier.width(12.dp)) }
        if (downloaded) {
            Icon(
                Icons.Filled.DownloadDone,
                contentDescription = stringResource(R.string.overlay_offline),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            humanSize(clip.sizeBytes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onDelete != null) {
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
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

    val dayClips = vm.clipsOf(day)
    val clips = dayClips
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
                    if (dayClips.isNotEmpty()) {
                        item(key = "__analytics__") {
                            DayAnalyticsCard(dayClips, selectedPeriod)
                        }
                    }
                    if (clips.isEmpty()) {
                        item {
                            Box(Modifier.fillParentMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
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
                                isFavorite = vm.isFavorite(clip),
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
            isFavorite = vm.isFavorite(clip),
            onToggleFavorite = { vm.toggleFavorite(clip) },
            onRemoveOffline = { vm.deleteOfflineCopy(clip) },
            onDismiss = { actionClip = null },
        )
    }
}

/** Starred clips across every day, newest first. Tap plays; long-press opens the actions sheet
 *  (from which a clip can be un-starred). Favorites survive batch deletes, so this is where the
 *  memorable moments live. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FavoritesScreen(
    vm: MainViewModel,
    nav: NavHostController,
    tokenProvider: suspend () -> String,
) {
    var token by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { token = runCatching { tokenProvider() }.getOrNull() }

    var actionClip by remember { mutableStateOf<Clip?>(null) }
    val clips = vm.favoriteClips()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.favorites_title)) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
    ) { pad ->
        if (clips.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(pad).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(48.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.favorites_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(pad),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(clips, key = { it.id }) { clip ->
                    ClipCard(
                        clip = clip,
                        token = token,
                        isNew = vm.isNew(clip),
                        isDownloaded = vm.isDownloaded(clip),
                        isFavorite = true,
                        onClick = { nav.navigate("player/${clip.id}") },
                        onLongClick = { actionClip = clip },
                    )
                }
            }
        }
    }

    actionClip?.let { clip ->
        ClipActionsSheet(
            clip = clip,
            token = token,
            isDownloaded = vm.isDownloaded(clip),
            isFavorite = vm.isFavorite(clip),
            onToggleFavorite = { vm.toggleFavorite(clip) },
            onRemoveOffline = { vm.deleteOfflineCopy(clip) },
            onDismiss = { actionClip = null },
        )
    }
}

/** One hour bucket of a day: how many events happened and their average motion intensity (1..5). */
private data class HourBin(val hour: Int, val count: Int, val avgIntensity: Int?)

private fun binClipsByHour(clips: List<Clip>): List<HourBin> {
    val byHour = clips.filter { it.hour != null }.groupBy { it.hour!! }
    return (0..23).map { h ->
        val cs = byHour[h].orEmpty()
        val ints = cs.mapNotNull { it.intensityLevel }
        HourBin(h, cs.size, if (ints.isEmpty()) null else ints.average().roundToInt())
    }
}

/**
 * Per-day activity analytics: a 24-hour histogram of how events are distributed across the day.
 * Bar height = number of events that hour; bar colour = the hour's average motion intensity (the
 * app's faint→strong ramp). When a time-of-day period is selected, its hours stay lit and the rest
 * dim, so the chart doubles as context for the filter below.
 */
@Composable
private fun DayAnalyticsCard(dayClips: List<Clip>, selectedPeriod: DayPeriod?) {
    val context = LocalContext.current
    val bins = remember(dayClips) { binClipsByHour(dayClips) }
    val peak = bins.maxByOrNull { it.count }?.takeIf { it.count > 0 }
    val avgDelay = remember(dayClips) {
        dayClips.mapNotNull { it.uploadDelaySeconds }.let { if (it.isEmpty()) null else it.sum() / it.size }
    }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.analytics_activity_by_hour),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (peak != null) {
                Text(
                    stringResource(
                        R.string.analytics_peak,
                        context.getString(R.string.analytics_hour_range, peak.hour, (peak.hour + 1) % 24),
                        peak.count,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (avgDelay != null) {
                Text(
                    stringResource(R.string.avg_upload_delay_day, uploadDelayLabel(avgDelay)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            EventDistributionChart(bins, selectedPeriod, Modifier.fillMaxWidth().height(120.dp))
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf(0, 6, 12, 18, 24).forEach {
                    Text(
                        "${it}h",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            IntensityLegend()
        }
    }
}

/** 24-bar histogram; height = event count, colour = average intensity, dimmed outside [selected]. */
@Composable
private fun EventDistributionChart(bins: List<HourBin>, selectedPeriod: DayPeriod?, modifier: Modifier) {
    val ramp = MaterialTheme.status.intensity
    val fallback = MaterialTheme.colorScheme.primary
    val baseline = MaterialTheme.colorScheme.outlineVariant
    val maxCount = (bins.maxOfOrNull { it.count } ?: 0).coerceAtLeast(1)
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width
        val h = size.height
        val slot = w / 24f
        val barW = slot * 0.6f
        drawLine(
            baseline,
            androidx.compose.ui.geometry.Offset(0f, h),
            androidx.compose.ui.geometry.Offset(w, h),
            strokeWidth = 1f,
        )
        bins.forEach { b ->
            if (b.count <= 0) return@forEach
            val inSel = selectedPeriod == null || b.hour in selectedPeriod.range
            val x = b.hour * slot + (slot - barW) / 2f
            val barH = (h - 2f) * (b.count.toFloat() / maxCount)
            val color = (b.avgIntensity?.let { ramp[(it - 1).coerceIn(0, 4)] } ?: fallback)
                .copy(alpha = if (inSel) 1f else 0.25f)
            drawRoundRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(x, h - barH),
                size = androidx.compose.ui.geometry.Size(barW, barH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW * 0.4f, barW * 0.4f),
            )
        }
    }
}

/** Small faint→strong colour key so the intensity encoding on the histogram is decodable. */
@Composable
private fun IntensityLegend() {
    val ramp = MaterialTheme.status.intensity
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.intensity_low),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        ramp.forEach { c ->
            Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(c))
            Spacer(Modifier.width(3.dp))
        }
        Spacer(Modifier.width(3.dp))
        Text(
            stringResource(R.string.intensity_high),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
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
            // Local-only, no token needed: star/unstar to protect it from batch deletes.
            SheetAction(
                stringResource(if (isFavorite) R.string.action_favorite_remove else R.string.action_favorite_add),
            ) {
                onToggleFavorite()
                onDismiss()
            }
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
    isFavorite: Boolean,
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
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.surfaceVariant,
                                MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                // Muted camera glyph shown behind the thumbnail while it loads (or if none exists).
                Icon(
                    Icons.Filled.Videocam,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier.size(40.dp),
                )
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
                // Play button on a circular scrim so it stays legible over bright thumbnails.
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.32f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = stringResource(R.string.action_play),
                        tint = Color.White,
                        modifier = Modifier.size(34.dp),
                    )
                }
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
                if (isFavorite) {
                    Box(
                        Modifier.align(Alignment.BottomStart).padding(8.dp)
                            .size(26.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = stringResource(R.string.favorite),
                            tint = Color(0xFFFFC107),
                            modifier = Modifier.size(18.dp),
                        )
                    }
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
                ClipLatencyLine(clip)
            }
        }
    }
}

/**
 * When recording finished vs. when the clip landed on Drive, plus the delay between them — the real
 * end-to-end upload latency, straight from Drive's modifiedTime/createdTime (no NVR change). Shown
 * only when both timestamps are available.
 */
@Composable
private fun ClipLatencyLine(clip: Clip) {
    val finished = clip.recordedFinishedTime ?: return
    val delay = clip.uploadDelaySeconds
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.CloudDone,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            stringResource(
                R.string.latency_line,
                finished,
                clip.uploadedTime ?: "—",
                delay?.let { uploadDelayLabel(it) } ?: "—",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Motion-intensity indicator: 5 "signal" bars, coloured by level
 * (1 faint grey → 5 strong red). Bars above the level are dimmed.
 */
@Composable
private fun IntensityBars(level: Int) {
    // Semantic ramp (faint grey → strong red) lives in the theme, tuned per light/dark.
    val color = MaterialTheme.status.intensity[(level - 1).coerceIn(0, 4)]
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
private fun CameraStatusCard(h: com.famviva.camara.data.CameraHealth, onOpenBattery: (() -> Unit)? = null) {
    val context = LocalContext.current
    val now = System.currentTimeMillis() / 1000
    val etaTxt = h.etaLabel(context)
    val battTxt = h.battery?.let {
        "🔋 $it%${if (h.charging == true) " ⚡" else ""}" + (etaTxt?.let { e -> " · ~$e" } ?: "")
    }
    val clickMod = if (onOpenBattery != null) Modifier.clickable(onClick = onOpenBattery) else Modifier
    Box(clickMod) {
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
            bg = MaterialTheme.status.warningContainer,
            fg = MaterialTheme.status.onWarningContainer,
            title = stringResource(R.string.status_lowbatt_title, h.battery ?: 0),
            body = stringResource(R.string.status_lowbatt_body),
        )
        else -> Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
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
            if (onOpenBattery != null) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
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

/**
 * Battery-level over time, from the app's own local time series (each reading it saw from the NVR's
 * status.json heartbeat). Reachable by tapping the camera status line. Resolution is bounded by how
 * often the app checks the camera, so it fills in gradually with use.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BatteryScreen(vm: MainViewModel, nav: NavHostController, camera: String) {
    val context = LocalContext.current
    val samples = vm.batterySamples(camera)
    val health = vm.cameraHealth.firstOrNull { it.camera == camera }
    val nowPct = samples.lastOrNull()?.battery ?: health?.battery
    val charging = samples.lastOrNull()?.charging ?: (health?.charging == true)
    // Prefer the NVR's own ETA; fall back to an app-side estimate from the collected history so an
    // estimate appears even before the NVR's rolling regression is ready.
    val etaMin = health?.etaMinutes ?: estimateBatteryEtaMinutes(samples)
    val eta = etaMin?.let { formatEta(context, it) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.battery_title)) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (nowPct != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.battery_now, nowPct),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(10.dp))
                    val suffix = when {
                        charging -> "⚡ " + stringResource(R.string.battery_charging)
                        eta != null -> "~$eta"
                        else -> null
                    }
                    if (suffix != null) {
                        Text(suffix, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                // Forecast clock time = last reading's timestamp + ETA (only while discharging).
                val lastEpoch = samples.lastOrNull()?.epochSec
                if (!charging && etaMin != null && lastEpoch != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.battery_forecast, clockLabel(lastEpoch + etaMin * 60L)),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(20.dp))
            }

            if (samples.size < 2) {
                Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.battery_history_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                var rangeDays by rememberSaveable { mutableStateOf(1) }   // 1/3/7 days, 0 = all
                val nowS = System.currentTimeMillis() / 1000
                val chartSamples = if (rangeDays <= 0) samples
                    else samples.filter { it.epochSec >= nowS - rangeDays.toLong() * 86_400L }

                BatteryRangeChips(rangeDays) { rangeDays = it }
                Spacer(Modifier.height(8.dp))
                if (chartSamples.size < 2) {
                    Text(
                        stringResource(R.string.battery_history_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    BatteryGraph(chartSamples, Modifier.fillMaxWidth().height(240.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.battery_minmax, chartSamples.minOf { it.battery }, chartSamples.maxOf { it.battery }),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Forecast accuracy is scored over the FULL history (not just the visible range).
                Spacer(Modifier.height(20.dp))
                ForecastAccuracyCard(evaluateBatteryForecast(samples))
            }
        }
    }
}

/**
 * How close the battery-life forecast has been. Scores each past NVR prediction against the discharge
 * that actually followed it (see [evaluateBatteryForecast]). Until enough predictions can be checked,
 * shows a "collecting" note so it's clear the data is being recorded now for later.
 */
@Composable
private fun ForecastAccuracyCard(accuracy: com.famviva.camara.data.ForecastAccuracy?) {
    val context = LocalContext.current
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                stringResource(R.string.forecast_accuracy_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            if (accuracy == null) {
                Text(
                    stringResource(R.string.forecast_accuracy_collecting),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            Text(
                stringResource(R.string.forecast_accuracy_error, formatEta(context, accuracy.meanAbsErrorMin)),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                pluralStringResource(R.plurals.forecast_checked, accuracy.count, accuracy.count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Direction of the bias, only when it's more than a rounding wobble (~10 min).
            val bias = accuracy.meanSignedErrorMin
            if (kotlin.math.abs(bias) >= 10) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(
                        if (bias > 0) R.string.forecast_accuracy_optimistic else R.string.forecast_accuracy_pessimistic,
                        formatEta(context, kotlin.math.abs(bias)),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            accuracy.improving?.let { improving ->
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(if (improving) R.string.forecast_accuracy_improving else R.string.forecast_accuracy_worsening),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (improving) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BatteryRangeChips(selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            1 to R.string.battery_range_24h,
            3 to R.string.battery_range_3d,
            7 to R.string.battery_range_7d,
            0 to R.string.battery_range_all,
        ).forEach { (days, res) ->
            FilterChip(
                selected = selected == days,
                onClick = { onSelect(days) },
                label = { Text(stringResource(res)) },
            )
        }
    }
}

/** Finds the sample index nearest (by time) to a touch x within the plot area. */
private fun nearestSampleIndex(px: Float, width: Float, leftPad: Float, samples: List<BatterySample>, tMin: Long, span: Long): Int {
    val frac = ((px - leftPad) / (width - leftPad).coerceAtLeast(1f)).coerceIn(0f, 1f)
    val target = tMin + (frac * span).toLong()
    return samples.indices.minByOrNull { kotlin.math.abs(samples[it].epochSec - target) } ?: 0
}

/**
 * Interactive battery-over-time graph: line + filled area, Y axis (0/50/100 %) and X axis (time
 * ticks) labels, and a scrub crosshair — drag across it to read the exact time + % of any point
 * (shown in the readout line above). Single series, so the title carries identity (no legend).
 */
@Composable
private fun BatteryGraph(samples: List<BatterySample>, modifier: Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary
    val fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val tMin = samples.first().epochSec
    val span = (samples.last().epochSec - tMin).coerceAtLeast(1L)
    val longSpan = span > 172_800L                      // > ~2 days -> date ticks instead of clock
    var selIdx by remember(samples) { mutableStateOf<Int?>(null) }

    Column(modifier) {
        val sel = selIdx?.let { samples.getOrNull(it) }
        Text(
            text = if (sel != null) "${epochLabel(sel.epochSec)} · ${sel.battery}%"
                else stringResource(R.string.battery_scrub_hint),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (sel != null) FontWeight.SemiBold else FontWeight.Normal,
            color = if (sel != null) MaterialTheme.colorScheme.primary else labelColor,
        )
        Spacer(Modifier.height(6.dp))
        androidx.compose.foundation.Canvas(
            Modifier.fillMaxWidth().weight(1f).pointerInput(samples) {
                awaitEachGesture {
                    val leftPad = 34.dp.toPx()
                    val w = size.width.toFloat()
                    val down = awaitFirstDown()
                    selIdx = nearestSampleIndex(down.position.x, w, leftPad, samples, tMin, span)
                    do {
                        val ev = awaitPointerEvent()
                        ev.changes.forEach { c ->
                            if (c.pressed) { selIdx = nearestSampleIndex(c.position.x, w, leftPad, samples, tMin, span); c.consume() }
                        }
                    } while (ev.changes.any { it.pressed })
                    selIdx = null
                }
            },
        ) {
            val leftPad = 34.dp.toPx()
            val topPad = 8.dp.toPx()
            val bottomPad = 18.dp.toPx()
            val plotW = (size.width - leftPad).coerceAtLeast(1f)
            val plotH = (size.height - topPad - bottomPad).coerceAtLeast(1f)
            fun y(pct: Int) = topPad + plotH * (1f - pct / 100f)
            fun x(t: Long) = leftPad + plotW * ((t - tMin).toFloat() / span.toFloat())

            // Y gridlines + labels (0/50/100)
            listOf(0, 25, 50, 75, 100).forEach { g ->
                drawLine(gridColor, Offset(leftPad, y(g)), Offset(size.width, y(g)), strokeWidth = 1f)
            }
            listOf(0, 50, 100).forEach { g ->
                val tl = measurer.measure("$g", style = TextStyle(color = labelColor, fontSize = 9.sp))
                drawText(tl, topLeft = Offset(leftPad - tl.size.width - 4f, y(g) - tl.size.height / 2f))
            }
            // Area + line
            val pts = samples.map { Offset(x(it.epochSec), y(it.battery)) }
            val area = Path().apply {
                moveTo(pts.first().x, topPad + plotH); pts.forEach { lineTo(it.x, it.y) }
                lineTo(pts.last().x, topPad + plotH); close()
            }
            drawPath(area, fillColor)
            val line = Path().apply {
                moveTo(pts.first().x, pts.first().y); pts.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(line, lineColor, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            // X axis time ticks (first / middle / last)
            listOf(0, samples.size / 2, samples.size - 1).distinct().forEach { i ->
                val s = samples[i]
                val tl = measurer.measure(axisTimeLabel(s.epochSec, longSpan), style = TextStyle(color = labelColor, fontSize = 9.sp))
                val tx = (x(s.epochSec) - tl.size.width / 2f).coerceIn(leftPad, size.width - tl.size.width)
                drawText(tl, topLeft = Offset(tx, size.height - bottomPad + 3f))
            }
            // Scrub crosshair + selected point, or the latest-point dot when idle
            val idx = selIdx
            if (idx != null && idx in pts.indices) {
                val p = pts[idx]
                drawLine(lineColor.copy(alpha = 0.5f), Offset(p.x, topPad), Offset(p.x, topPad + plotH), strokeWidth = 1.5.dp.toPx())
                drawCircle(lineColor, radius = 4.5.dp.toPx(), center = p)
                drawCircle(Color.White, radius = 2.dp.toPx(), center = p)
            } else {
                drawCircle(lineColor, radius = 3.dp.toPx(), center = pts.last())
            }
        }
    }
}

