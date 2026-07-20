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
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.famviva.camara.DateFilter
import com.famviva.camara.MainViewModel
import com.famviva.camara.R
import com.famviva.camara.data.AutoDownloadMode
import com.famviva.camara.data.BatterySample
import com.famviva.camara.data.agoLabel
import com.famviva.camara.data.BlipCluster
import com.famviva.camara.data.BlipClusterEntry
import com.famviva.camara.data.BlipEntry
import com.famviva.camara.data.buildHealthTimeline
import com.famviva.camara.data.buildServiceTimeline
import com.famviva.camara.data.buildDailyTimeline
import com.famviva.camara.data.Clip
import com.famviva.camara.data.clusterHealthTimeline
import com.famviva.camara.data.DailyHealth
import com.famviva.camara.data.DayCell
import com.famviva.camara.data.dateKeyOf
import com.famviva.camara.data.LaneState
import com.famviva.camara.data.LaneSummary
import com.famviva.camara.data.ServiceDayLane
import com.famviva.camara.data.ServiceDailyTimeline
import com.famviva.camara.data.ServiceLane
import com.famviva.camara.data.ServiceTimeline
import com.famviva.camara.data.SummaryTone
import com.famviva.camara.data.TimelineSpan
import com.famviva.camara.data.DayPeriod
import com.famviva.camara.data.formatDurationSec
import com.famviva.camara.data.HealthEvent
import com.famviva.camara.data.hourMinuteLabel
import com.famviva.camara.data.Outage
import com.famviva.camara.data.OutageEntry
import com.famviva.camara.data.OutageEvent
import com.famviva.camara.data.SyncStatus
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
import com.famviva.camara.data.AwayMode
import com.famviva.camara.data.AwayModeStore
import com.famviva.camara.data.GeofenceManager
import com.famviva.camara.notify.AlertIntensity
import com.famviva.camara.notify.NotifyStore
import com.famviva.camara.media.ClipActions
import com.famviva.camara.ui.theme.status
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt
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
    deepLinkRoute: String? = null,
    onDeepLinkHandled: () -> Unit = {},
) {
    val nav = rememberNavController()
    val vm: MainViewModel = viewModel(
        factory = MainViewModel.Factory(drive, seenStore, offlineStore, clipListCache, batteryHistory, favoritesStore, tokenProvider),
    )

    // Returning to the foreground refetches when the in-memory data has gone stale. Without this,
    // reopening from background never refreshes (the first-composition load only runs once), so the
    // health banner would judge hours-old data against the current clock -> false "not reporting".
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START &&
                vm.shouldResumeRefresh(System.currentTimeMillis() / 1000)
            ) {
                vm.load()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // A tapped notification asks for a screen (e.g. "live"): push it on top of "days" so Back
    // still returns to the event list. Consumed once so it doesn't re-fire on recomposition.
    LaunchedEffect(deepLinkRoute) {
        if (deepLinkRoute != null) {
            nav.navigate(deepLinkRoute)
            onDeepLinkHandled()
        }
    }

    // The bottom bar shows only on the four top-level destinations (not on player/storage/away/etc.).
    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val onTop = TopTab.entries.any { it.route == currentRoute }

    Scaffold(
        bottomBar = {
            if (onTop) {
                AppBottomBar(currentRoute) { route -> navigateTab(nav, route) }
            }
        },
    ) { innerPadding ->
        // Only the bottom inset matters here: each screen owns its own top bar / status-bar inset,
        // so applying the full padding would double it. This inset keeps content clear of the bar.
        NavHost(
            navController = nav,
            startDestination = "days",
            modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
        ) {
        composable("days") { DaysScreen(vm, nav) }
        composable("health") { HealthScreen(vm, nav, drive) }
        composable("live") { LiveScreen(nav) }
        composable("away_settings") { AwayModeScreen(nav) }
        composable("camera_settings") { CameraSettingsScreen(nav) }
        composable("live_logs") { LiveLogScreen(nav) }
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
}

/**
 * The four top-level destinations reachable from the bottom bar. Clips is the start destination
 * (home); the other three were previously buried in the ⋮ menu / a top-bar icon.
 */
private enum class TopTab(val route: String, val labelRes: Int, val icon: ImageVector) {
    CLIPS("days", R.string.tab_clips, Icons.Filled.VideoLibrary),
    LIVE("live", R.string.live_title, Icons.Filled.Videocam),
    FAVORITES("favorites", R.string.favorites_title, Icons.Filled.Star),
    HEALTH("health", R.string.health_title, Icons.Filled.MonitorHeart),
}

/** Standard state-saving tab navigation: one entry per tab on the back stack, each tab's own state
 *  saved/restored, and re-tapping the current tab is a no-op (single-top). */
private fun navigateTab(nav: NavHostController, route: String) {
    nav.navigate(route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun AppBottomBar(currentRoute: String?, onSelect: (String) -> Unit) {
    NavigationBar {
        TopTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = currentRoute == tab.route,
                onClick = { onSelect(tab.route) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(stringResource(tab.labelRes)) },
            )
        }
    }
}

/**
 * Single overflow (⋮) menu for the home screen, so the top bar stays uncluttered. Live / Favoritos /
 * Salud now live in the bottom bar; what remains here is the secondary settings: Storage, the
 * auto-download policy, the motion-alert gate (away / quiet-hours / min intensity) and the language
 * switch — each showing its current state inline with a checkmark. Refresh stays a direct icon.
 */
@Composable
private fun HomeOverflowMenu(vm: MainViewModel, nav: NavHostController, onAwayChanged: () -> Unit = {}) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }

    // Away/Home is read by the background poll (NewClipsWorker) straight from the store, so the menu
    // toggles it there directly — no ViewModel round-trip (same as the language switch below).
    val awayStore = remember { AwayModeStore(context) }
    var awayMode by remember { mutableStateOf(awayStore.mode) }
    var manualAway by remember { mutableStateOf(awayStore.manualAway) }

    // Quiet-hours (mute motion alerts at night) is read by the background poll straight from the
    // store, so the menu toggles it there directly — same pattern as away/language.
    val notifyStore = remember { NotifyStore(context) }
    var quietHours by remember { mutableStateOf(notifyStore.quietHours) }
    var alertLevel by remember { mutableStateOf(notifyStore.minAlertLevel) }

    val tags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    val isSpanish = (if (tags.isNotEmpty()) tags else Locale.getDefault().language).startsWith("es")

    Box {
        IconButton(onClick = {
            // Re-read on open so the checkmark reflects changes made in the away-settings screen.
            awayMode = awayStore.mode
            manualAway = awayStore.manualAway
            quietHours = notifyStore.quietHours
            alertLevel = notifyStore.minAlertLevel
            expanded = true
        }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.menu_more))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.storage_title)) },
                leadingIcon = { Icon(Icons.Filled.Storage, contentDescription = null) },
                onClick = { expanded = false; nav.navigate("storage") },
            )
            HorizontalDivider()

            MenuSectionLabel(stringResource(R.string.auto_download_menu_title))
            fun pickDownload(target: AutoDownloadMode, toastRes: Int) {
                expanded = false
                if (vm.autoDownloadMode != target) {
                    vm.selectAutoDownloadMode(target)
                    Toast.makeText(context, context.getString(toastRes), Toast.LENGTH_SHORT).show()
                }
            }
            CheckableMenuItem(R.string.auto_download_off, selected = vm.autoDownloadMode == AutoDownloadMode.OFF) {
                pickDownload(AutoDownloadMode.OFF, R.string.auto_download_disabled_toast)
            }
            CheckableMenuItem(R.string.auto_download_wifi, selected = vm.autoDownloadMode == AutoDownloadMode.WIFI_ONLY) {
                pickDownload(AutoDownloadMode.WIFI_ONLY, R.string.auto_download_wifi_toast)
            }
            CheckableMenuItem(R.string.auto_download_data, selected = vm.autoDownloadMode == AutoDownloadMode.WIFI_AND_DATA) {
                pickDownload(AutoDownloadMode.WIFI_AND_DATA, R.string.auto_download_data_toast)
            }
            HorizontalDivider()

            MenuSectionLabel(stringResource(R.string.away_menu_title))
            fun pickManual(target: Boolean, toastRes: Int) {
                expanded = false
                awayStore.mode = AwayMode.MANUAL
                awayStore.manualAway = target
                awayMode = AwayMode.MANUAL
                manualAway = target
                // Leaving AUTO: tear down the OS geofence (MANUAL owns the state by hand).
                GeofenceManager.remove(context)
                onAwayChanged()
                Toast.makeText(context, context.getString(toastRes), Toast.LENGTH_SHORT).show()
            }
            CheckableMenuItem(R.string.away_home, selected = awayMode == AwayMode.MANUAL && !manualAway) {
                pickManual(false, R.string.away_home_toast)
            }
            CheckableMenuItem(R.string.away_away, selected = awayMode == AwayMode.MANUAL && manualAway) {
                pickManual(true, R.string.away_away_toast)
            }
            CheckableMenuItem(R.string.away_auto, selected = awayMode == AwayMode.AUTO) {
                expanded = false
                nav.navigate("away_settings")
            }
            CheckableMenuItem(R.string.quiet_hours, selected = quietHours) {
                val target = !quietHours
                notifyStore.quietHours = target
                quietHours = target
                Toast.makeText(
                    context,
                    context.getString(if (target) R.string.quiet_hours_on_toast else R.string.quiet_hours_off_toast),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            HorizontalDivider()

            // Which motion strengths are worth a push. Uses the same 1..5 intensity classification as
            // the clip-list "strong only" chip. Clips below the threshold still appear in the app.
            MenuSectionLabel(stringResource(R.string.alert_level_title))
            fun pickAlertLevel(target: AlertIntensity, toastRes: Int) {
                expanded = false
                if (notifyStore.minAlertLevel != target) {
                    notifyStore.minAlertLevel = target
                    alertLevel = target
                    Toast.makeText(context, context.getString(toastRes), Toast.LENGTH_SHORT).show()
                }
            }
            CheckableMenuItem(R.string.alert_level_all, selected = alertLevel == AlertIntensity.ALL) {
                pickAlertLevel(AlertIntensity.ALL, R.string.alert_level_all_toast)
            }
            CheckableMenuItem(R.string.alert_level_medium, selected = alertLevel == AlertIntensity.MEDIUM_PLUS) {
                pickAlertLevel(AlertIntensity.MEDIUM_PLUS, R.string.alert_level_medium_toast)
            }
            CheckableMenuItem(R.string.alert_level_strong, selected = alertLevel == AlertIntensity.STRONG) {
                pickAlertLevel(AlertIntensity.STRONG, R.string.alert_level_strong_toast)
            }
            HorizontalDivider()

            MenuSectionLabel(stringResource(R.string.menu_language))
            CheckableMenuItem(R.string.lang_english, selected = !isSpanish) {
                expanded = false
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
            }
            CheckableMenuItem(R.string.lang_spanish, selected = isSpanish) {
                expanded = false
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("es"))
            }
        }
    }
}

/** Small section header inside a dropdown menu. */
@Composable
private fun MenuSectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** A dropdown item that shows a leading checkmark when it's the current selection. */
@Composable
private fun CheckableMenuItem(labelRes: Int, selected: Boolean, onClick: () -> Unit) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DaysScreen(vm: MainViewModel, nav: NavHostController) {
    LaunchedEffect(Unit) { if (!vm.loadedOnce) vm.load() }

    val context = LocalContext.current
    // Away state surfaced as a chip (safety-relevant, was buried in ⋮). Bumped when the overflow
    // toggles it so the chip re-reads; it also re-reads on return from the away-settings screen.
    val awayStore = remember { AwayModeStore(context) }
    var awayRefresh by remember { mutableStateOf(0) }

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
                    // Salud (health) is now a bottom-bar tab; Live/Favoritos likewise moved out of ⋮.
                    IconButton(onClick = { vm.load() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh))
                    }
                    HomeOverflowMenu(vm, nav, onAwayChanged = { awayRefresh++ })
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

            AwayModeChip(awayStore, awayRefresh) { nav.navigate("away_settings") }

            val now = System.currentTimeMillis() / 1000
            val dataFresh = vm.isDataFresh(now)
            vm.cameraHealth.forEach { h ->
                // On the home screen the whole card opens the Health screen; per-camera battery is
                // reached from inside Health (the same card there routes to the battery graph).
                CameraStatusCard(h, dataFresh = dataFresh, onClick = { nav.navigate("health") })
            }
            DataFreshnessLine(vm, dataFresh, now) { vm.load() }

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

/** One donut arc. [day] is the YYYYMMDD it maps to for tap-to-open, or null for the folded "Other"
 *  wedge (which spans several days and so isn't individually navigable). */
private data class DonutSlice(val value: Long, val color: Color, val day: String?)

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
        head.forEachIndexed { i, (day, bytes) -> add(DonutSlice(bytes, palette[i], day)) }
        if (tailBytes > 0) add(DonutSlice(tailBytes, otherColor, null))
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
                        // A tapped slice opens that day's clips — same destination as its legend row.
                        onSliceTap = { day -> nav.navigate("storage_day/${section.name}/$day") },
                    )
                }
                Spacer(Modifier.height(8.dp))

                // Honest headroom: average per day across the days shown, extrapolated to a month.
                // Coarse on purpose (no fake precision) — it's a "roughly this much" sense of the rate.
                val dayCount = ranked.size
                if (dayCount > 0 && totalBytes > 0) {
                    val avgPerDay = totalBytes / dayCount
                    Text(
                        stringResource(R.string.storage_headroom, humanSize(avgPerDay), humanSize(avgPerDay * 30)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                }

                // The donut folds days past the palette into a single "Other" wedge, but the legend
                // lists every day individually (each tappable/deletable). Days beyond the palette
                // share the neutral "Other" hue — they're the "Other" wedge, broken out by day.
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    itemsIndexed(ranked, key = { _, it -> it.first }) { i, (day, bytes) ->
                        StorageLegendRow(
                            color = if (i < palette.size) palette[i] else otherColor,
                            label = prettyDate(context, day),
                            bytes = bytes,
                            percent = percentLabel(context, bytes, totalBytes),
                            onClick = { nav.navigate("storage_day/${section.name}/$day") },
                            onDelete = if (isLocal) ({ confirmDeleteDay = day }) else null,
                        )
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
private fun StorageDonut(
    slices: List<DonutSlice>,
    centerTop: String,
    centerBottom: String,
    onSliceTap: (String) -> Unit,
) {
    // Same total the arcs are sized against, so hit-test boundaries line up with what's drawn.
    val total = slices.sumOf { it.value }.coerceAtLeast(1L).toFloat()
    Box(Modifier.size(190.dp), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(
            Modifier.fillMaxSize().pointerInput(slices) {
                // Map a tap to a slice: check it lands on the ring band (radius), then walk the same
                // -90°-start / clockwise sweeps the draw uses and open the matching day.
                detectTapGestures { pos ->
                    val strokePx = 30.dp.toPx()
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val ringRadius = (minOf(size.width, size.height).toFloat() - strokePx) / 2f
                    val dx = pos.x - cx
                    val dy = pos.y - cy
                    val r = sqrt(dx * dx + dy * dy)
                    if (kotlin.math.abs(r - ringRadius) > strokePx / 2f + 8.dp.toPx()) return@detectTapGestures
                    // atan2 gives degrees clockwise from 3 o'clock (screen y is down); the ring starts
                    // at 12 o'clock (-90°), so rebase and normalise into [0,360).
                    val deg = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
                    var rel = (deg + 90f) % 360f
                    if (rel < 0f) rel += 360f
                    var acc = 0f
                    for (s in slices) {
                        val sweep = 360f * (s.value.toFloat() / total)
                        if (rel >= acc && rel < acc + sweep) {
                            s.day?.let(onSliceTap)
                            break
                        }
                        acc += sweep
                    }
                }
            },
        ) {
            val strokePx = 30.dp.toPx()
            val d = size.minDimension - strokePx
            val topLeft = androidx.compose.ui.geometry.Offset((size.width - d) / 2f, (size.height - d) / 2f)
            val arcSize = androidx.compose.ui.geometry.Size(d, d)
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
            IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
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
            IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
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
        confirmButton = {
            // Destructive action in the error colour so "Delete" reads as the consequential choice.
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.action_delete)) }
        },
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
    var strongOnly by rememberSaveable { mutableStateOf(false) }
    var newOnly by rememberSaveable { mutableStateOf(false) }
    val selectedPeriod = periodName?.let { name -> DayPeriod.entries.firstOrNull { it.name == name } }

    // Token to authorize thumbnail loading and downloads (Drive).
    var token by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { token = runCatching { tokenProvider() }.getOrNull() }

    // Clip selected with a long press (for the share/download menu).
    var actionClip by remember { mutableStateOf<Clip?>(null) }

    val dayClips = vm.clipsOf(day)
    val clips = dayClips
        .filter { selectedPeriod == null || it.period == selectedPeriod }
        .filter { !strongOnly || (it.intensityLevel ?: 0) >= 4 }
        .filter { !newOnly || vm.isNew(it) }
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
                // Content filters (combine with the period filter): only strong motion / only unseen.
                FilterChip(
                    selected = strongOnly,
                    onClick = { strongOnly = !strongOnly },
                    label = { Text(stringResource(R.string.filter_strong_only)) },
                    leadingIcon = if (strongOnly) {
                        { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    } else null,
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = newOnly,
                    onClick = { newOnly = !newOnly },
                    label = { Text(stringResource(R.string.filter_new_only)) },
                    leadingIcon = if (newOnly) {
                        { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    } else null,
                )
            }

            // Day storyboard: a chronological thumbnail scrub of the whole day (ignores the filters/
            // sort above so it's always the full picture). Tap a frame to jump straight to its clip.
            if (dayClips.isNotEmpty()) {
                DayFilmstrip(dayClips, token) { clip -> nav.navigate("player/${clip.id}") }
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

/**
 * Horizontal thumbnail filmstrip for a day: one small frame per clip in chronological order, a quick
 * visual scrub of the whole day. Reuses the exact Coil + Bearer-token thumbnail path the list rows
 * use (Drive's own jpg via [Clip.thumbUrl]); a clip with no thumbnail yet shows its HH:MM instead.
 * Tapping a frame opens that clip's player.
 */
@Composable
private fun DayFilmstrip(clips: List<Clip>, token: String?, onClipClick: (Clip) -> Unit) {
    val strip = remember(clips) { clips.sortedBy { it.name } }   // chronological, oldest -> newest
    LazyRow(
        Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(strip, key = { it.id }) { clip ->
            FilmstripFrame(clip, token) { onClipClick(clip) }
        }
    }
}

/** One filmstrip frame: a fixed 72x40 thumbnail (or a HH:MM placeholder) with the time caption. */
@Composable
private fun FilmstripFrame(clip: Clip, token: String?, onClick: () -> Unit) {
    val hhmm = clip.time.take(5)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Box(
            Modifier.width(72.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val thumb = clip.thumbUrl
            if (thumb != null && token != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(thumb)
                        .addHeader("Authorization", "Bearer $token")
                        .crossfade(true)
                        .build(),
                    contentDescription = hhmm,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    hhmm,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            hhmm,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                // Top-level tab: no back arrow (the bottom bar is the way between tabs).
                title = { Text(stringResource(R.string.favorites_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
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

/** Home/Away chip on the home screen (the state was previously only reachable through ⋮). Tapping
 *  opens the away settings. [refresh] is bumped by the overflow menu so the label re-reads after a
 *  change made there; it also re-reads whenever this screen re-enters composition. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AwayModeChip(store: AwayModeStore, refresh: Int, onClick: () -> Unit) {
    val (mode, away) = remember(refresh) {
        val m = store.mode
        m to when (m) {
            AwayMode.MANUAL -> store.manualAway
            AwayMode.AUTO -> store.lastAutoAway
        }
    }
    val emoji = if (away) "🚶" else "🏠"
    val label = "$emoji ${stringResource(if (away) R.string.away_away else R.string.away_home)}" +
        if (mode == AwayMode.AUTO) " · ${stringResource(R.string.away_chip_auto)}" else ""
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
        AssistChip(
            onClick = onClick,
            label = { Text(label) },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = if (away) MaterialTheme.status.warningContainer
                else MaterialTheme.colorScheme.surfaceVariant,
            ),
        )
    }
}

/**
 * Freshness line under the health cards. When the data is fresh it reads "Actualizado HH:MM"; when
 * the last good load is old or the last refresh failed it flips to a neutral "Datos sin refrescar
 * (hace X)" — the honest, non-alarming counterpart to the "not reporting" banner (which is gated on
 * freshness, so it can't fire on stale in-memory data). Tapping the line retries the load.
 */
@Composable
private fun DataFreshnessLine(vm: MainViewModel, dataFresh: Boolean, now: Long, onRetry: () -> Unit) {
    if (vm.lastLoadOk <= 0) return          // nothing has ever loaded — the list's own states cover it
    val context = LocalContext.current
    val healthy = dataFresh && vm.error == null
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onRetry).padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (healthy) {
            Text(
                stringResource(R.string.data_updated, clockLabel(vm.lastLoadOk)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                stringResource(R.string.data_stale, agoLabel(context, vm.lastLoadOk, now)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * NVR/camera status (from status.json). Shows the most serious problem as a coloured banner, or a
 * discreet info line (battery + last signal) when everything is fine.
 *
 * [dataFresh] gates the "not reporting" alarm: it only fires when the app's own data was fetched
 * recently AND the heartbeat is >3 h old. Stale in-memory data (a load from hours ago) must never
 * be judged against the current clock — that produced a false "sin reportar" while the NVR was fine.
 */
@Composable
private fun CameraStatusCard(
    h: com.famviva.camara.data.CameraHealth,
    dataFresh: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val now = System.currentTimeMillis() / 1000
    val etaTxt = h.etaLabel(context)
    val battTxt = h.battery?.let {
        "🔋 $it%${if (h.charging == true) " ⚡" else ""}" + (etaTxt?.let { e -> " · ~$e" } ?: "")
    }
    val clickMod = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Box(clickMod) {
      when {
        !h.ok -> StatusBanner(
            bg = MaterialTheme.colorScheme.errorContainer,
            fg = MaterialTheme.colorScheme.onErrorContainer,
            title = stringResource(R.string.status_down_title, h.camera),
            body = stringResource(R.string.status_down_body),
        )
        dataFresh && h.isStale(now) -> StatusBanner(
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
        h.recordingInSub -> StatusBanner(
            bg = MaterialTheme.status.warningContainer,
            fg = MaterialTheme.status.onWarningContainer,
            title = stringResource(R.string.status_sub_title, h.camera),
            body = stringResource(R.string.status_sub_body),
        )
        h.recording2kUnstable() -> StatusBanner(
            bg = MaterialTheme.status.warningContainer,
            fg = MaterialTheme.status.onWarningContainer,
            title = stringResource(R.string.status_2kflap_title),
            body = stringResource(R.string.status_2kflap_body, h.rec2kDropsLastHour ?: 0),
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
            if (onClick != null) {
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
 * Health screen: current camera status (reused status card), sync-pipeline health, and an outage
 * timeline built from the NVR's events.jsonl. Fetches its own data on entry (in-memory only). All of
 * events.jsonl / sync_status.json may be absent while the NVR side ships in parallel, so every part
 * degrades to an empty state rather than an error.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HealthScreen(vm: MainViewModel, nav: NavHostController, drive: com.famviva.camara.data.DriveClient) {
    val context = LocalContext.current
    val now = System.currentTimeMillis() / 1000
    val dataFresh = vm.isDataFresh(now)

    var loading by remember { mutableStateOf(true) }
    var rawEvents by remember { mutableStateOf<List<OutageEvent>>(emptyList()) }
    var timeline by remember { mutableStateOf<List<HealthEvent>>(emptyList()) }
    var daily by remember { mutableStateOf<List<DailyHealth>>(emptyList()) }
    var sync by remember { mutableStateOf<SyncStatus?>(null) }
    // Selected horizon for the coverage swimlane: 0 = 24h, 1 = 7d, 2 = 30d.
    var horizon by rememberSaveable { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        loading = true
        val raw = runCatching { drive.fetchOutageEvents() }.getOrDefault(emptyList())
        rawEvents = raw
        timeline = clusterHealthTimeline(buildHealthTimeline(raw))
        daily = runCatching { drive.fetchDailyHealth() }.getOrDefault(emptyList())
        sync = runCatching { drive.fetchSyncStatus() }.getOrNull()
        loading = false
    }

    // Timeline entries are already newest-first, so groupBy yields days newest-first with each day's
    // entries newest-first too.
    val byDay = timeline.groupBy { dateKeyOf(it.ts) }

    Scaffold(
        topBar = {
            TopAppBar(
                // Top-level tab (Salud): no back arrow — switch tabs from the bottom bar.
                title = { Text(stringResource(R.string.health_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Current status (reuses the same card as the home screen; here it routes on to battery).
            item { HealthSectionHeader(stringResource(R.string.health_current_section)) }
            if (vm.cameraHealth.isEmpty()) {
                item { HealthEmptyText(stringResource(R.string.health_no_camera_data)) }
            } else {
                items(vm.cameraHealth, key = { "cam_${it.camera}" }) { h ->
                    CameraStatusCard(
                        h,
                        dataFresh = dataFresh,
                        onClick = if (h.battery != null) ({ nav.navigate("battery/${h.camera}") }) else null,
                    )
                }
            }

            // Per-service coverage swimlane (24h/7d from events.jsonl, 30d from daily_health.jsonl).
            // Sits ABOVE the outage timeline; the timeline + sync card below are its drill-down.
            item {
                Spacer(Modifier.height(6.dp))
                HealthSectionHeader(stringResource(R.string.health_coverage_section))
            }
            item {
                if (loading) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    ServiceCoverageSection(
                        horizon = horizon,
                        onHorizonChange = { horizon = it },
                        events = rawEvents,
                        daily = daily,
                        nowSec = now,
                    )
                }
            }

            // Sync pipeline.
            item {
                Spacer(Modifier.height(6.dp))
                HealthSectionHeader(stringResource(R.string.health_sync_section))
            }
            item { SyncCard(sync, now) }

            // Outage timeline.
            item {
                Spacer(Modifier.height(6.dp))
                HealthSectionHeader(stringResource(R.string.health_timeline_section))
            }
            when {
                loading -> item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                timeline.isEmpty() -> item { HealthEmptyText(stringResource(R.string.health_events_empty)) }
                else -> byDay.forEach { (day, dayEvents) ->
                    item { HealthDayHeader(prettyDate(context, day)) }
                    items(dayEvents) { entry ->
                        when (entry) {
                            is OutageEntry -> OutageCard(entry.outage)
                            is BlipEntry -> BlipRow(entry.event)
                            is BlipClusterEntry -> BlipClusterRow(entry.cluster)
                        }
                    }
                }
            }
        }
    }
}

/** Localized service name for an events.jsonl `svc` (falls back to the raw token if unknown). */
@Composable
private fun serviceName(svc: String): String = when (svc) {
    "recording" -> stringResource(R.string.health_svc_recording)
    "segmenter" -> stringResource(R.string.health_svc_segmenter)
    "detector" -> stringResource(R.string.health_svc_detector)
    "sync" -> stringResource(R.string.health_svc_sync)
    else -> svc
}

@Composable
private fun HealthSectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
    )
}

@Composable
private fun HealthDayHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun HealthEmptyText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
    )
}

/** Sync-pipeline health card. Colour + headline reflect severity: a full Drive or a stalled uploader
 *  reads red ("Drive full — clips aren't backing up"); a recent transient error reads amber; all good
 *  reads calm. The generic "why" (the reason code from the NVR) is spelled out, not left as a code. */
@Composable
private fun SyncCard(sync: SyncStatus?, now: Long) {
    val context = LocalContext.current
    if (sync == null) {
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Text(
                    stringResource(R.string.health_sync_no_data),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    val hasErr = sync.hasRecentError(now)
    val reason = if (hasErr) syncReasonText(sync.lastError) else null
    val full = hasErr && sync.lastError == "storage_full"
    val stale = sync.isStale(now)
    val sev = when {
        full || stale -> Sev.CRITICAL
        reason != null -> Sev.WARNING
        else -> Sev.GOOD
    }
    val fg = sevOn(sev)
    // Headline = the most severe live condition. A full Drive is the loudest (backup is broken), so it
    // takes the headline; otherwise a stalled uploader, otherwise the all-good line.
    val headline = when {
        full -> reason!!
        stale -> stringResource(R.string.health_sync_stale, agoLabel(context, sync.updated, now))
        else -> stringResource(R.string.health_sync_ok, agoLabel(context, sync.updated, now))
    }
    Surface(color = sevContainer(sev), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(
                headline,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = fg,
            )
            // If the reason isn't already the headline (i.e. not a full Drive), show it underneath.
            if (reason != null && !full) {
                Spacer(Modifier.height(4.dp))
                Text(reason, style = MaterialTheme.typography.bodySmall, color = fg)
            }
        }
    }
}

/** A paired outage span. Ongoing outages are critical (red); a resolved one still reads as notable
 *  (amber), and the title says what happened ("Stopped recording") rather than just the service. */
@Composable
private fun OutageCard(outage: Outage) {
    val context = LocalContext.current
    val ongoing = outage.ongoing
    val sev = if (ongoing) Sev.CRITICAL else Sev.WARNING
    val bg = sevContainer(sev)
    val fg = sevOn(sev)
    Surface(color = bg, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    outageTitle(outage.svc, ongoing) + (outage.cam?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = fg,
                    modifier = Modifier.weight(1f),
                )
                if (ongoing) {
                    Text(
                        stringResource(R.string.health_outage_ongoing),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = fg,
                    )
                } else outage.durationSec?.let {
                    Text(
                        formatDurationSec(context, it),
                        style = MaterialTheme.typography.labelMedium,
                        color = fg,
                    )
                }
            }
            val span = if (!ongoing && outage.endTs != null) {
                "${hourMinuteLabel(outage.startTs)} – ${hourMinuteLabel(outage.endTs)}"
            } else {
                hourMinuteLabel(outage.startTs)
            }
            Text(
                stringResource(R.string.health_outage_span, span),
                style = MaterialTheme.typography.bodySmall,
                color = fg,
            )
        }
    }
}

/** A minor blip (short 2K drop or a logged error) — smaller than an outage card. */
@Composable
private fun BlipRow(event: OutageEvent) {
    val label = when (event.ev) {
        "drop" -> stringResource(R.string.health_ev_drop)
        "error" -> stringResource(R.string.health_ev_error)
        else -> event.ev
    }
    val isError = event.ev == "error"
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            hourMinuteLabel(event.ts),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${serviceName(event.svc)}${event.cam?.let { " · $it" } ?: ""} · $label",
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            event.msg?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A collapsed reconnect storm: one card standing in for many same-kind blips ("87 detector drops ·
 *  10:46–12:49"), so a flapping link doesn't bury the real outages. */
@Composable
private fun BlipClusterRow(cluster: BlipCluster) {
    val isError = cluster.ev == "error"
    val label = when (cluster.ev) {
        "drop" -> stringResource(R.string.health_cluster_drops, cluster.count, serviceName(cluster.svc))
        "error" -> stringResource(R.string.health_cluster_errors, cluster.count, serviceName(cluster.svc))
        else -> "${cluster.count}× ${serviceName(cluster.svc)}"
    }
    val span = "${hourMinuteLabel(cluster.firstTs)} – ${hourMinuteLabel(cluster.lastTs)}"
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                label + (cluster.cam?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                stringResource(R.string.health_outage_span, span),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ==============================================================================================
// "Cobertura por servicio" — the per-service health swimlane at the top of the Salud screen.
// Fixed status hexes (identical in light & dark, from the approved mockup). MaterialTheme.status has
// no dedicated saturated good/warn/crit fill trio, so these are used for the segment fills; the card
// chrome (track, labels, dividers) uses MaterialTheme colours so it still respects light/dark.
// ==============================================================================================
private val TL_OK = Color(0xFF0CA30C)       // grabando 2K
private val TL_WARN = Color(0xFFFAB219)     // 360p degradado / fallo transitorio
private val TL_SERIOUS = Color(0xFFEC835A)  // parpadeo (moderado)
private val TL_CRIT = Color(0xFFD03B3B)     // caído / parpadeo fuerte
private val LANE_LABEL_W = 92.dp

private fun laneColor(state: LaneState): Color = when (state) {
    LaneState.OK -> TL_OK
    LaneState.DEGRADED -> TL_WARN
    LaneState.DOWN -> TL_CRIT
    LaneState.FLAP_SERIOUS -> TL_SERIOUS
    LaneState.FLAP_CRITICAL -> TL_CRIT
    LaneState.SYNC_WARN -> TL_WARN
}

private fun isFlapState(state: LaneState): Boolean =
    state == LaneState.FLAP_SERIOUS || state == LaneState.FLAP_CRITICAL

/** Short lane label (fits the 92dp column). Reuses serviceName() except detector, whose full name
 *  ("Detector de movimiento") is too long here. */
@Composable
private fun laneShortName(svc: String): String =
    if (svc == "detector") stringResource(R.string.health_lane_name_detector) else serviceName(svc)

@Composable
private fun laneSub(svc: String): String = stringResource(
    when (svc) {
        "recording" -> R.string.health_lane_sub_recording
        "detector" -> R.string.health_lane_sub_detector
        "segmenter" -> R.string.health_lane_sub_segmenter
        else -> R.string.health_lane_sub_sync
    },
)

/** Localized state label; for the sync lane, OK reads "Subiendo a Drive" rather than "Grabando 2K". */
@Composable
private fun stateLabel(svc: String, state: LaneState): String = stringResource(
    when {
        state == LaneState.OK && svc == "sync" -> R.string.health_state_sync_ok
        state == LaneState.OK -> R.string.health_state_ok
        state == LaneState.DEGRADED -> R.string.health_state_degraded
        state == LaneState.DOWN -> R.string.health_state_down
        state == LaneState.FLAP_SERIOUS -> R.string.health_state_flap
        state == LaneState.FLAP_CRITICAL -> R.string.health_state_flap_heavy
        else -> R.string.health_state_sync_warn
    },
)

/** Severity of a health card, so what needs attention reads at a glance by colour (not just text). */
private enum class Sev { GOOD, WARNING, CRITICAL }

@Composable private fun sevContainer(sev: Sev): Color = when (sev) {
    Sev.CRITICAL -> MaterialTheme.colorScheme.errorContainer
    Sev.WARNING -> MaterialTheme.status.warningContainer
    Sev.GOOD -> MaterialTheme.colorScheme.surfaceVariant
}
@Composable private fun sevOn(sev: Sev): Color = when (sev) {
    Sev.CRITICAL -> MaterialTheme.colorScheme.onErrorContainer
    Sev.WARNING -> MaterialTheme.status.onWarningContainer
    Sev.GOOD -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Event-phrased outage title — says what HAPPENED ("Stopped recording"), not just the service name
 *  ("Recording"), so the user grasps it at a glance. Ongoing vs resolved changes the wording. */
@Composable
private fun outageTitle(svc: String, ongoing: Boolean): String = stringResource(
    when (svc) {
        "recording" -> if (ongoing) R.string.health_ev_recording_down_now else R.string.health_ev_recording_past
        "detector" -> R.string.health_ev_detector_down
        "segmenter" -> R.string.health_ev_segmenter_down
        "sync" -> R.string.health_ev_sync_down
        else -> R.string.health_svc_recording
    },
)

/** Maps the NVR's sync-failure reason CODE (sync_status.json last_error) to a clear message. Older
 *  NVR builds wrote a descriptive string instead of a code — show that raw (else branch). */
@Composable
private fun syncReasonText(code: String?): String? = when (code) {
    null, "" -> null
    "storage_full" -> stringResource(R.string.health_sync_reason_full)
    "rate_limit" -> stringResource(R.string.health_sync_reason_rate)
    "network" -> stringResource(R.string.health_sync_reason_net)
    "auth" -> stringResource(R.string.health_sync_reason_auth)
    "error" -> null
    else -> code
}

/**
 * The horizon selector + coverage swimlane + per-service summary. 24h/7d reconstruct live spans from
 * events.jsonl; 30d aggregates daily_health.jsonl (honest empty state while the NVR side ships).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServiceCoverageSection(
    horizon: Int,
    onHorizonChange: (Int) -> Unit,
    events: List<OutageEvent>,
    daily: List<DailyHealth>,
    nowSec: Long,
) {
    Column {
        val labels = listOf(R.string.health_horizon_24h, R.string.health_horizon_7d, R.string.health_horizon_30d)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, res ->
                SegmentedButton(
                    selected = horizon == i,
                    onClick = { onHorizonChange(i) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = labels.size),
                ) { Text(stringResource(res)) }
            }
        }
        Spacer(Modifier.height(10.dp))
        when (horizon) {
            0, 1 -> {
                val is24h = horizon == 0
                val spanSec = if (is24h) 86_400L else 7L * 86_400L
                val timeline = remember(events, horizon) {
                    buildServiceTimeline(events, nowSec - spanSec, nowSec)
                }
                LiveSwimlane(timeline, is24h)
            }
            else -> {
                val dailyTl = remember(daily) { buildDailyTimeline(daily) }
                DailySwimlane(dailyTl)
            }
        }
    }
}

/** The 24h/7d swimlane card (proportional Canvas spans) + its summary card. */
@Composable
private fun LiveSwimlane(timeline: ServiceTimeline, is24h: Boolean) {
    var selSvc by remember(timeline) { mutableStateOf<String?>(null) }
    var selSpan by remember(timeline) { mutableStateOf<TimelineSpan?>(null) }
    val windowSpan = (timeline.windowEnd - timeline.windowStart).coerceAtLeast(1L)

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(
                stringResource(if (is24h) R.string.health_win_note_24h else R.string.health_win_note_7d),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(LANE_LABEL_W))
                TimelineAxis(timeline.windowStart, windowSpan, is24h, Modifier.weight(1f))
            }
            timeline.lanes.forEach { lane ->
                Spacer(Modifier.height(7.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LaneLabel(lane.svc, Modifier.width(LANE_LABEL_W))
                    SpanLane(
                        lane = lane,
                        windowStart = timeline.windowStart,
                        windowSpan = windowSpan,
                        selected = if (selSvc == lane.svc) selSpan else null,
                        modifier = Modifier.weight(1f),
                    ) { svc, span -> selSvc = svc; selSpan = span }
                }
            }
            Spacer(Modifier.height(12.dp))
            CoverageLegend()
            HorizontalDivider(Modifier.padding(top = 12.dp, bottom = 8.dp))
            val span = selSpan
            val svc = selSvc
            if (span != null && svc != null) {
                SelectedSpanDetail(svc, span)
            } else {
                Text(
                    stringResource(R.string.health_tap_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    SummaryCard(timeline.summaries, is30d = false)
}

/** The 30d swimlane card (one cell per day) + summary, or the honest empty state if the NVR hasn't
 *  started writing daily_health.jsonl yet. */
@Composable
private fun DailySwimlane(timeline: ServiceDailyTimeline) {
    if (timeline.dayCount == 0) {
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Text(
                    stringResource(R.string.health_win_note_30d),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.health_30d_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    var selSvc by remember(timeline) { mutableStateOf<String?>(null) }
    var selCell by remember(timeline) { mutableStateOf<DayCell?>(null) }
    val dates = timeline.lanes.firstOrNull()?.cells?.map { it.date } ?: emptyList()

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(
                stringResource(R.string.health_win_note_30d),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(LANE_LABEL_W))
                DailyAxis(dates, Modifier.weight(1f))
            }
            timeline.lanes.forEach { lane ->
                Spacer(Modifier.height(7.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LaneLabel(lane.svc, Modifier.width(LANE_LABEL_W))
                    DayLane(
                        lane = lane,
                        selected = if (selSvc == lane.svc) selCell else null,
                        modifier = Modifier.weight(1f),
                    ) { svc, cell -> selSvc = svc; selCell = cell }
                }
            }
            Spacer(Modifier.height(12.dp))
            CoverageLegend(showOff = true)
            HorizontalDivider(Modifier.padding(top = 12.dp, bottom = 8.dp))
            val cell = selCell
            val svc = selSvc
            if (cell != null && svc != null) {
                SelectedDayDetail(svc, cell)
            } else {
                Text(
                    stringResource(R.string.health_tap_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    SummaryCard(timeline.summaries, is30d = true)
}

/** Two-line lane label (name + faint sub-caption) filling the fixed left column. */
@Composable
private fun LaneLabel(svc: String, modifier: Modifier) {
    Column(modifier.padding(end = 8.dp)) {
        Text(
            laneShortName(svc),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            laneSub(svc),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Time axis for the live horizons: evenly spaced ticks (HH:MM for 24h, DD/MM for 7d). */
@Composable
private fun TimelineAxis(windowStart: Long, windowSpan: Long, is24h: Boolean, modifier: Modifier) {
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val measurer = rememberTextMeasurer()
    val ticks = if (is24h) 5 else 8
    androidx.compose.foundation.Canvas(modifier.height(15.dp)) {
        val w = size.width
        val h = size.height
        drawLine(gridColor, Offset(0f, h - 1f), Offset(w, h - 1f), strokeWidth = 1f)
        for (i in 0 until ticks) {
            val frac = i.toFloat() / (ticks - 1)
            val ts = windowStart + (frac * windowSpan).toLong()
            val tl = measurer.measure(axisTimeLabel(ts, longSpan = !is24h), style = TextStyle(color = labelColor, fontSize = 9.sp))
            val cx = frac * w
            val tx = (cx - tl.size.width / 2f).coerceIn(0f, (w - tl.size.width).coerceAtLeast(0f))
            drawText(tl, topLeft = Offset(tx, 0f))
            drawLine(gridColor, Offset(cx.coerceIn(0.5f, w - 0.5f), h - 4f), Offset(cx.coerceIn(0.5f, w - 0.5f), h), strokeWidth = 1f)
        }
    }
}

/** Day axis for the 30d horizon: first / middle / last date labels (DD/MM), aligned to cell centres. */
@Composable
private fun DailyAxis(dates: List<String>, modifier: Modifier) {
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val measurer = rememberTextMeasurer()
    androidx.compose.foundation.Canvas(modifier.height(15.dp)) {
        val w = size.width
        val h = size.height
        val n = dates.size.coerceAtLeast(1)
        drawLine(gridColor, Offset(0f, h - 1f), Offset(w, h - 1f), strokeWidth = 1f)
        listOf(0, n / 2, n - 1).distinct().forEach { i ->
            val d = dates.getOrNull(i) ?: return@forEach
            val label = if (d.length >= 8) "${d.substring(6, 8)}/${d.substring(4, 6)}" else d
            val tl = measurer.measure(label, style = TextStyle(color = labelColor, fontSize = 9.sp))
            val cx = (i + 0.5f) / n * w
            val tx = (cx - tl.size.width / 2f).coerceIn(0f, (w - tl.size.width).coerceAtLeast(0f))
            drawText(tl, topLeft = Offset(tx, 0f))
        }
    }
}

/** One live lane: proportional segments on a rounded track; flapping windows are diagonally hatched.
 *  Tap a segment to select it (detail shown below the chart). */
@Composable
private fun SpanLane(
    lane: ServiceLane,
    windowStart: Long,
    windowSpan: Long,
    selected: TimelineSpan?,
    modifier: Modifier,
    onSelect: (String, TimelineSpan) -> Unit,
) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    val outline = MaterialTheme.colorScheme.onSurface
    val spans = lane.spans
    androidx.compose.foundation.Canvas(
        modifier.height(26.dp).pointerInput(spans, windowStart, windowSpan) {
            detectTapGestures { off ->
                val w = size.width.toFloat().coerceAtLeast(1f)
                val frac = (off.x / w).coerceIn(0f, 1f)
                val ts = windowStart + (frac * windowSpan).toLong()
                (spans.firstOrNull { ts in it.startTs..it.endTs } ?: spans.lastOrNull())
                    ?.let { onSelect(lane.svc, it) }
            }
        },
    ) {
        val w = size.width
        val h = size.height
        drawRoundRect(trackColor, cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx(), 6.dp.toPx()))
        val top = 3.dp.toPx()
        val segH = h - 2 * top
        val minPx = 2.5.dp.toPx()
        val corner = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx(), 3.dp.toPx())
        spans.forEach { s ->
            val left0 = ((s.startTs - windowStart).toFloat() / windowSpan.toFloat()) * w
            var segW = ((s.endTs - s.startTs).toFloat() / windowSpan.toFloat()) * w
            if (segW < minPx) segW = minPx
            val left = left0.coerceIn(0f, (w - segW).coerceAtLeast(0f))
            drawRoundRect(
                color = laneColor(s.state),
                topLeft = Offset(left, top),
                size = androidx.compose.ui.geometry.Size(segW, segH),
                cornerRadius = corner,
            )
            if (isFlapState(s.state)) drawHatch(left, top, segW, segH)
            if (selected == s) {
                drawRoundRect(
                    color = outline,
                    topLeft = Offset(left, top),
                    size = androidx.compose.ui.geometry.Size(segW, segH),
                    cornerRadius = corner,
                    style = Stroke(1.5.dp.toPx()),
                )
            }
        }
    }
}

/** One 30d lane: a fixed grid of day cells (one per day), coloured by that day's worst state. */
@Composable
private fun DayLane(
    lane: ServiceDayLane,
    selected: DayCell?,
    modifier: Modifier,
    onSelect: (String, DayCell) -> Unit,
) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    val outline = MaterialTheme.colorScheme.onSurface
    val offColor = MaterialTheme.colorScheme.outline
    val isRec = lane.svc == "recording"
    val cells = lane.cells
    androidx.compose.foundation.Canvas(
        modifier.height(26.dp).pointerInput(cells) {
            detectTapGestures { off ->
                val w = size.width.toFloat().coerceAtLeast(1f)
                val idx = ((off.x / w) * cells.size).toInt().coerceIn(0, cells.size - 1)
                cells.getOrNull(idx)?.let { onSelect(lane.svc, it) }
            }
        },
    ) {
        val w = size.width
        val h = size.height
        drawRoundRect(trackColor, cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx(), 6.dp.toPx()))
        val n = cells.size.coerceAtLeast(1)
        val slot = w / n
        val gap = (slot * 0.14f).coerceAtMost(2.dp.toPx())
        val top = 3.dp.toPx()
        val segH = h - 2 * top
        val corner = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx(), 2.dp.toPx())
        cells.forEachIndexed { i, c ->
            val left = i * slot + gap / 2f
            val cw = (slot - gap).coerceAtLeast(1f)
            val frac = c.coveredFrac.toFloat()
            if (isRec && frac < 0.999f) {
                // Grey base = the hours the NVR was OFF (no footage); the day's recording state fills
                // up from the bottom in proportion to how much of the 24 h it was actually covering —
                // so a night powered off reads as a half-empty cell, not a full green one.
                drawRoundRect(offColor, topLeft = Offset(left, top), size = androidx.compose.ui.geometry.Size(cw, segH), cornerRadius = corner)
                val ch = segH * frac.coerceIn(0f, 1f)
                if (ch > 0f) drawRoundRect(
                    color = laneColor(c.state),
                    topLeft = Offset(left, top + (segH - ch)),
                    size = androidx.compose.ui.geometry.Size(cw, ch),
                    cornerRadius = corner,
                )
            } else {
                drawRoundRect(
                    color = laneColor(c.state),
                    topLeft = Offset(left, top),
                    size = androidx.compose.ui.geometry.Size(cw, segH),
                    cornerRadius = corner,
                )
            }
            if (isFlapState(c.state)) drawHatch(left, top, cw, segH)
            if (selected == c) {
                drawRoundRect(
                    color = outline,
                    topLeft = Offset(left, top),
                    size = androidx.compose.ui.geometry.Size(cw, segH),
                    cornerRadius = corner,
                    style = Stroke(1.5.dp.toPx()),
                )
            }
        }
    }
}

/** Diagonal 45° hatch fill inside a segment rect — the "flapping (reconnect storm)" texture. Clipped
 *  to the rect so lines never bleed past it. Black at low alpha (fixed in both themes, as the mockup). */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHatch(
    left: Float, top: Float, segW: Float, segH: Float,
) {
    val period = 5.dp.toPx()
    val lw = 1.6.dp.toPx()
    val hatch = Color.Black.copy(alpha = 0.30f)
    clipRect(left, top, left + segW, top + segH) {
        var x0 = left - segH
        while (x0 < left + segW) {
            drawLine(hatch, Offset(x0, top + segH), Offset(x0 + segH, top), strokeWidth = lw)
            x0 += period
        }
    }
}

/** Colour key so the segment encoding stays decodable (identity never by colour alone). [showOff]
 *  adds the grey "NVR off" key, only meaningful on the 30d recording lane. */
@Composable
private fun CoverageLegend(showOff: Boolean = false) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        LegendKey(TL_OK, stringResource(R.string.health_legend_ok), hatched = false)
        Spacer(Modifier.width(14.dp))
        LegendKey(TL_WARN, stringResource(R.string.health_legend_degraded), hatched = false)
        Spacer(Modifier.width(14.dp))
        LegendKey(TL_CRIT, stringResource(R.string.health_legend_down), hatched = false)
        Spacer(Modifier.width(14.dp))
        LegendKey(TL_SERIOUS, stringResource(R.string.health_legend_flap), hatched = true)
        if (showOff) {
            Spacer(Modifier.width(14.dp))
            LegendKey(MaterialTheme.colorScheme.outline, stringResource(R.string.health_legend_off), hatched = false)
        }
    }
}

@Composable
private fun LegendKey(color: Color, label: String, hatched: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Canvas(Modifier.size(13.dp)) {
            drawRoundRect(color, cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx(), 3.dp.toPx()))
            if (hatched) drawHatch(0f, 0f, size.width, size.height)
        }
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Detail line for a tapped live segment: coloured swatch + "service · state" and the time range /
 *  duration (or reconnect count for flap windows). */
@Composable
private fun SelectedSpanDetail(svc: String, span: TimelineSpan) {
    val context = LocalContext.current
    val dur = span.endTs - span.startTs
    val range = if (dur > 60) "${hourMinuteLabel(span.startTs)} – ${hourMinuteLabel(span.endTs)}" else hourMinuteLabel(span.startTs)
    val extra = when {
        isFlapState(span.state) -> stringResource(R.string.health_detail_flap, span.flapCount)
        span.state == LaneState.DOWN || span.state == LaneState.DEGRADED -> formatDurationSec(context, dur)
        else -> null
    }
    DetailBody(laneColor(span.state), isFlapState(span.state), "${laneShortName(svc)} · ${stateLabel(svc, span.state)}", buildString {
        append(stringResource(R.string.health_outage_span, range))
        if (extra != null) append(" · $extra")
    })
}

/** Detail line for a tapped 30d day cell: coloured swatch + "service · state" and the date plus the
 *  day's headline metric (coverage for recording, drop / error count otherwise). */
@Composable
private fun SelectedDayDetail(svc: String, cell: DayCell) {
    val context = LocalContext.current
    val metricText = when (svc) {
        "recording" -> cell.coveragePct?.let { "%.1f%%".format(it) }
        "detector", "segmenter" -> if (cell.metric > 0) stringResource(R.string.health_detail_flap, cell.metric) else null
        else -> if (cell.metric > 0) stringResource(R.string.health_sum_sync_errors, cell.metric) else null
    }
    // Recording: if the NVR was off part of the day, say how much of the 24 h it actually covered —
    // an off stretch is a real footage gap, not "OK coverage".
    val coverNote = if (svc == "recording" && cell.coveredFrac < 0.98)
        stringResource(R.string.health_day_cover, formatDurationSec(context, (cell.coveredFrac * 86_400).toLong()))
    else null
    DetailBody(laneColor(cell.state), isFlapState(cell.state), "${laneShortName(svc)} · ${stateLabel(svc, cell.state)}", buildString {
        append(prettyDate(context, cell.date))
        if (coverNote != null) append(" · $coverNote")
        if (metricText != null) append(" · $metricText")
    })
}

@Composable
private fun DetailBody(swatch: Color, hatched: Boolean, title: String, sub: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Canvas(Modifier.size(14.dp)) {
            drawRoundRect(swatch, cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx(), 3.dp.toPx()))
            if (hatched) drawHatch(0f, 0f, size.width, size.height)
        }
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Per-service summary card: one row per lane with a tone-coloured headline stat + a detail caption. */
@Composable
private fun SummaryCard(summaries: List<LaneSummary>, is30d: Boolean) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
            Text(
                stringResource(R.string.health_summary_section),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            summaries.forEachIndexed { i, s ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                SummaryRow(s, is30d)
            }
        }
    }
}

@Composable
private fun SummaryRow(s: LaneSummary, is30d: Boolean) {
    val context = LocalContext.current
    val tone = when (s.tone) {
        SummaryTone.GOOD -> TL_OK
        SummaryTone.MID -> TL_SERIOUS
        SummaryTone.BAD -> TL_CRIT
    }
    val stat: String
    val detail: String
    when (s.svc) {
        "recording" -> {
            stat = "%.1f%%".format(s.coveragePct ?: 100.0)
            detail = when {
                is30d && s.worstDate != null ->
                    stringResource(R.string.health_sum_worst_day, prettyDate(context, s.worstDate!!), formatDurationSec(context, s.worstOutageSec))
                s.outageCount > 0 ->
                    stringResource(R.string.health_sum_rec_outages, s.outageCount, formatDurationSec(context, s.worstOutageSec))
                else -> stringResource(R.string.health_sum_rec_none)
            }
        }
        "detector", "segmenter" -> {
            stat = s.flapDrops.toString()
            detail = if (s.flapDrops > 0) stringResource(R.string.health_sum_flap_active) else stringResource(R.string.health_sum_flap_stable)
        }
        else -> {
            stat = if (s.syncErrors > 0) s.syncErrors.toString() else stringResource(R.string.health_stat_ok)
            detail = if (s.syncErrors > 0) stringResource(R.string.health_sum_sync_errors, s.syncErrors) else stringResource(R.string.health_sum_sync_ok)
        }
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            laneShortName(s.svc),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(LANE_LABEL_W),
        )
        Text(stat, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = tone)
        Spacer(Modifier.weight(1f))
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
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

