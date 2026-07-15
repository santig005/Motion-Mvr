package com.famviva.camara.ui

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavHostController
import com.famviva.camara.R
import com.famviva.camara.data.AwayMode
import com.famviva.camara.data.AwayModeStore
import com.famviva.camara.data.LocationProvider
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

private val RADIUS_OPTIONS = listOf(100f, 150f, 300f)

/**
 * Setup for the automatic (location-based) away mode: grant location access, pin "home" to the
 * current location, choose a radius, and switch AUTO on. When AUTO is on, the background poll marks
 * you Away (motion alerts on) when you're outside the radius and Home (muted) when inside. The manual
 * Home/Away toggle in the ⋮ menu still overrides this by flipping the mode back to MANUAL.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AwayModeScreen(nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { AwayModeStore(context) }

    var hasFine by remember { mutableStateOf(LocationProvider.hasForegroundPermission(context)) }
    var hasBackground by remember { mutableStateOf(LocationProvider.hasBackgroundPermission(context)) }
    // Saved home as a reactive point so the map recenters when it's (re)set. null = not set.
    var homePoint by remember {
        mutableStateOf(store.homeLat?.let { la -> store.homeLng?.let { lo -> GeoPoint(la, lo) } })
    }
    val homeSet = homePoint != null
    var radius by remember { mutableStateOf(store.homeRadiusM) }
    var auto by remember { mutableStateOf(store.mode == AwayMode.AUTO) }
    var locating by remember { mutableStateOf(false) }
    // Current computed state for display ("at home" / "away"); null until evaluated.
    var currentlyAway by remember { mutableStateOf<Boolean?>(null) }

    val homeSavedToast = stringResource(R.string.away_home_saved_toast)
    val homeFailedToast = stringResource(R.string.away_home_failed_toast)

    val backgroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasBackground = granted || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q }

    val fineLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasFine = granted
        // Background location is a second, separate grant on API 29+.
        if (granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            !LocationProvider.hasBackgroundPermission(context)
        ) {
            backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }

    // Evaluate the current state whenever we're set up, so the card shows Home/Away live.
    LaunchedEffect(auto, homeSet, hasFine) {
        currentlyAway = if (homeSet && hasFine) {
            val dist = LocationProvider.lastLocation(context)?.let { store.distanceFromHome(it) }
            dist?.let { it > radius }
        } else {
            null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.away_settings_title)) },
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
                stringResource(R.string.away_settings_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))

            // 1) Location permission
            StepCard(
                done = hasFine,
                title = stringResource(R.string.away_step_permission),
            ) {
                if (hasFine) {
                    Text(
                        stringResource(
                            if (hasBackground) R.string.away_perm_granted_all else R.string.away_perm_granted_foreground,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!hasBackground) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = {
                            backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        }) { Text(stringResource(R.string.away_perm_allow_all)) }
                    }
                } else {
                    Text(
                        stringResource(R.string.away_perm_needed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { fineLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }) {
                        Text(stringResource(R.string.away_perm_grant))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // 2) Home location
            StepCard(
                done = homeSet,
                title = stringResource(R.string.away_step_home),
            ) {
                Text(
                    stringResource(if (homeSet) R.string.away_home_set else R.string.away_home_not_set),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    enabled = hasFine && !locating,
                    onClick = {
                        scope.launch {
                            locating = true
                            val loc = LocationProvider.currentLocation(context)
                            locating = false
                            if (loc != null) {
                                store.setHome(loc.latitude, loc.longitude)
                                homePoint = GeoPoint(loc.latitude, loc.longitude)
                                Toast.makeText(context, homeSavedToast, Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, homeFailedToast, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                ) {
                    if (locating) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.away_locating))
                    } else {
                        Text(stringResource(if (homeSet) R.string.away_set_home_again else R.string.away_set_home))
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.away_radius_label), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RADIUS_OPTIONS.forEach { r ->
                        FilterChip(
                            selected = radius == r,
                            onClick = { radius = r; store.homeRadiusM = r },
                            label = { Text(stringResource(R.string.away_radius_value, r.toInt())) },
                        )
                    }
                }
                homePoint?.let { pt ->
                    Spacer(Modifier.height(12.dp))
                    HomeMap(
                        home = pt,
                        radiusM = radius,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.away_map_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            // 3) Enable automatic mode
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.away_auto_enable), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                stringResource(R.string.away_auto_enable_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = auto,
                            enabled = hasFine && homeSet,
                            onCheckedChange = { on ->
                                auto = on
                                store.mode = if (on) AwayMode.AUTO else AwayMode.MANUAL
                            },
                        )
                    }
                    if (!hasFine || !homeSet) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.away_auto_needs_setup),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else if (auto && !hasBackground) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.away_auto_bg_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    currentlyAway?.let { away ->
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (away) Icons.Filled.LocationOn else Icons.Filled.Home,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(
                                    R.string.away_current_state,
                                    stringResource(if (away) R.string.away_state_away else R.string.away_state_home),
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A titled card with a leading "done" check that lights up once its step is satisfied. */
@Composable
private fun StepCard(done: Boolean, title: String, content: @Composable () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

/** Small OpenStreetMap preview centred on [home] with a translucent circle showing the away radius,
 *  so the user can confirm the exact spot. Pan/zoom enabled. */
@Composable
private fun HomeMap(home: GeoPoint, radiusM: Float, modifier: Modifier = Modifier) {
    val homeLabel = stringResource(R.string.away_home_marker)
    val fill = android.graphics.Color.argb(40, 0, 137, 123)
    val stroke = android.graphics.Color.rgb(0, 137, 123)
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            // osmdroid needs a user-agent (else OSM tile servers reply 403) and a writable cache;
            // keep both in app storage so no extra permission is required. Set before the MapView.
            Configuration.getInstance().apply {
                userAgentValue = ctx.packageName
                val base = java.io.File(ctx.cacheDir, "osmdroid")
                osmdroidBasePath = base
                osmdroidTileCache = java.io.File(base, "tiles")
            }
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(16.0)
                controller.setCenter(home)
                onResume()
            }
        },
        update = { map ->
            map.controller.setCenter(home)
            map.overlays.clear()
            map.overlays.add(
                Polygon().apply {
                    points = Polygon.pointsAsCircle(home, radiusM.toDouble())
                    fillPaint.color = fill
                    outlinePaint.color = stroke
                    outlinePaint.strokeWidth = 4f
                },
            )
            map.overlays.add(
                Marker(map).apply {
                    position = home
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = homeLabel
                },
            )
            map.invalidate()
        },
        onRelease = { it.onPause(); it.onDetach() },
    )
}
