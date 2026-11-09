package io.github.brickystudios.funkblick.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.brickystudios.funkblick.core.Category
import io.github.brickystudios.funkblick.core.Device
import io.github.brickystudios.funkblick.core.distanceFromRssi
import io.github.brickystudios.funkblick.core.isFollowingVehicle
import io.github.brickystudios.funkblick.core.metersToLat
import io.github.brickystudios.funkblick.core.metersToLon
import io.github.brickystudios.funkblick.core.spotAt
import io.github.brickystudios.funkblick.core.tr
import io.github.brickystudios.funkblick.scan.Fix
import io.github.brickystudios.funkblick.scan.Funk
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory as P
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.cos
import kotlin.math.sin

// The map: OpenStreetMap vector data from OpenFreeMap (no key, no account), drawn with MapLibre like Organic Maps.
// Dark = OpenFreeMap "dark", re-tinted to the amber night palette. Light = "liberty", an ordinary-looking map for
// discreet mode. Without network the style can't load: a plain grid style keeps track, cameras and devices visible.

enum class MapLook(val url: String) {
    DARK("https://tiles.openfreemap.org/styles/dark"),
    LIGHT("https://tiles.openfreemap.org/styles/liberty"),
}

private fun hex(c: androidx.compose.ui.graphics.Color) = "#%06X".format(0xFFFFFF and c.value.shr(32).toInt())

/** Re-tints OpenFreeMap's grey "dark" style: amber roads, warm-black land, muted labels. */
private fun tintDark(style: Style) {
    for (l in style.layers) when (l) {
        is BackgroundLayer -> l.setProperties(P.backgroundColor("#0D0C0A"))
        is FillLayer -> l.setProperties(P.fillColor(if (l.id.startsWith("water")) "#121418" else if (l.id == "building") "#1A1611" else "#141109"))
        is LineLayer -> l.setProperties(P.lineColor(when {
            l.id.contains("motorway") -> "#8A5A12"
            l.id.contains("major") -> "#5E4418"
            l.id.startsWith("rail") -> "#3A3226"
            l.id.startsWith("boundary") -> "#3A3226"
            l.id.startsWith("waterway") -> "#1B2028"
            else -> "#3A2E1A"
        }))
        is SymbolLayer -> l.setProperties(P.textColor("#A39780"), P.textHaloColor("#0D0C0A"))
    }
}

/** No network at first start: a grid style so the map is never a grey void (plan 2A). */
private const val GRID_STYLE = """{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#0D0C0A"}}]}"""

private const val SRC_TRACK = "fb-track"
private const val SRC_CAMS = "fb-cams"
private const val SRC_TRAFFIC = "fb-traffic"
private const val SRC_NOTABLE = "fb-notable"
private const val SRC_OTHER = "fb-other"
private const val SRC_NEAR = "fb-near"
private const val SRC_ME = "fb-me"
private const val SRC_RING = "fb-ring"
private const val SRC_GRID = "fb-grid"
const val LAYER_NOTABLE = "fb-notable"
const val LAYER_OTHER = "fb-other"
const val LAYER_NEAR = "fb-near"

private fun addOverlay(style: Style, light: Boolean) {
    val empty = FeatureCollection.fromFeatures(emptyList())
    style.addSource(GeoJsonSource(SRC_GRID, empty))
    style.addSource(GeoJsonSource(SRC_TRACK, empty))
    style.addSource(GeoJsonSource(SRC_CAMS, empty))
    style.addSource(GeoJsonSource(SRC_TRAFFIC, empty))
    style.addSource(GeoJsonSource(SRC_RING, empty))
    style.addSource(GeoJsonSource(SRC_NOTABLE, empty))
    // everything else is bundled into numbers until you zoom into a street (plan 7E)
    style.addSource(GeoJsonSource(SRC_OTHER, empty, GeoJsonOptions().withCluster(true).withClusterRadius(48).withClusterMaxZoom(17)))
    style.addSource(GeoJsonSource(SRC_NEAR, empty))
    style.addSource(GeoJsonSource(SRC_ME, empty))
    style.addLayer(LineLayer("fb-grid", SRC_GRID).withProperties(P.lineColor("#2A2418"), P.lineWidth(1f)))
    if (light) {
        // discreet: only your own position, nothing that looks like a scanner
        style.addLayer(CircleLayer("fb-me", SRC_ME).withProperties(P.circleColor("#3D7BE0"), P.circleRadius(8f), P.circleStrokeColor("#FFFFFF"), P.circleStrokeWidth(3f)))
        return
    }
    style.addLayer(LineLayer("fb-track", SRC_TRACK).withProperties(P.lineColor("#FFB020"), P.lineOpacity(0.55f), P.lineWidth(3f)))
    style.addLayer(CircleLayer("fb-traffic", SRC_TRAFFIC).withProperties(P.circleColor(Expression.toColor(Expression.get("c"))), P.circleRadius(5f),
        P.circleStrokeColor("#0D0C0A"), P.circleStrokeWidth(1.5f)))
    style.addLayer(CircleLayer("fb-cams", SRC_CAMS).withProperties(P.circleColor("#FF4D5E"), P.circleRadius(7f), P.circleStrokeColor("#F2E8D5"), P.circleStrokeWidth(2f)))
    style.addLayer(CircleLayer("fb-other-cluster", SRC_OTHER).withFilter(Expression.has("point_count")).withProperties(
        P.circleColor("#2A2418"), P.circleRadius(14f), P.circleStrokeColor("#6B5A3A"), P.circleStrokeWidth(1f)).also { it.minZoom = 15f })
    style.addLayer(SymbolLayer("fb-other-count", SRC_OTHER).withFilter(Expression.has("point_count")).withProperties(
        P.textField(Expression.toString(Expression.get("point_count"))), P.textFont(arrayOf("Noto Sans Regular")), P.textSize(12f),
        P.textColor("#F2E8D5"), P.textAllowOverlap(true)).also { it.minZoom = 15f })
    style.addLayer(CircleLayer(LAYER_OTHER, SRC_OTHER).withFilter(Expression.not(Expression.has("point_count"))).withProperties(
        P.circleColor(Expression.toColor(Expression.get("c"))), P.circleRadius(4f), P.circleOpacity(0.85f)).also { it.minZoom = 15f })
    // "All devices": the ones without a position estimate, around you like on the radar. Hollow, because the direction is made up.
    style.addLayer(CircleLayer(LAYER_NEAR, SRC_NEAR).withProperties(P.circleColor("#0D0C0A"), P.circleRadius(5f),
        P.circleStrokeColor(Expression.toColor(Expression.get("c"))), P.circleStrokeWidth(1.5f)))
    style.addLayer(LineLayer("fb-ring", SRC_RING).withProperties(P.lineColor(Expression.toColor(Expression.get("c"))), P.lineWidth(2f), P.lineOpacity(0.8f)))
    style.addLayer(CircleLayer(LAYER_NOTABLE, SRC_NOTABLE).withProperties(
        P.circleColor(Expression.toColor(Expression.get("c"))), P.circleRadius(7f), P.circleStrokeColor("#0D0C0A"), P.circleStrokeWidth(2f)))
    style.addLayer(CircleLayer("fb-me", SRC_ME).withProperties(P.circleColor("#FFB020"), P.circleRadius(8f), P.circleStrokeColor("#0D0C0A"), P.circleStrokeWidth(3f)))
}

/** A circle as a polygon ring (MapLibre has no metre-sized circles), for uncertainty and the "follows you" ring. */
private fun ring(lat: Double, lon: Double, meters: Double, color: String): Feature {
    val pts = (0..48).map { i ->
        val a = Math.toRadians(i * 7.5)
        Point.fromLngLat(lon + metersToLon(meters * sin(a), lat), lat + metersToLat(meters * cos(a)))
    }
    return Feature.fromGeometry(LineString.fromLngLats(pts)).also { it.addStringProperty("c", color) }
}

/** 100 m grid around you, only drawn by the fallback style. */
private fun grid(lat: Double, lon: Double): FeatureCollection {
    val lines = mutableListOf<Feature>()
    for (i in -15..15) {
        val dy = metersToLat(i * 100.0); val dx = metersToLon(i * 100.0, lat)
        val w = metersToLon(1500.0, lat); val h = metersToLat(1500.0)
        lines += Feature.fromGeometry(LineString.fromLngLats(listOf(Point.fromLngLat(lon - w, lat + dy), Point.fromLngLat(lon + w, lat + dy))))
        lines += Feature.fromGeometry(LineString.fromLngLats(listOf(Point.fromLngLat(lon + dx, lat - h), Point.fromLngLat(lon + dx, lat + h))))
    }
    return FeatureCollection.fromFeatures(lines)
}

private fun Device.feature(lat: Double, lon: Double) = Feature.fromGeometry(Point.fromLngLat(lon, lat)).also {
    it.addStringProperty("id", id)
    it.addStringProperty("c", hex(cls.category.color()))
}

private fun Device.feature(): Feature? = pos?.let { feature(it.lat, it.lon) }

/** Pushes the session onto the map. Cheap enough for once a second. */
private fun update(style: Style, devices: List<Device>, fix: Fix?, track: List<Fix>, selected: String?, offline: Boolean, nearby: Boolean) {
    fun src(id: String) = style.getSourceAs<GeoJsonSource>(id)
    val notable = devices.filter { it.cls.mute == null && (it.cls.watch != null || it.cls.category.ordinal <= Category.FLEET.ordinal) }
    src(SRC_NOTABLE)?.setGeoJson(FeatureCollection.fromFeatures(notable.mapNotNull { it.feature() }))
    src(SRC_OTHER)?.setGeoJson(FeatureCollection.fromFeatures((devices - notable.toSet()).filter { !it.own }.mapNotNull { it.feature() }))
    if (track.size > 1) src(SRC_TRACK)?.setGeoJson(FeatureCollection.fromFeatures(listOf(
        Feature.fromGeometry(LineString.fromLngLats(track.map { Point.fromLngLat(it.lon, it.lat) })))))
    val rings = mutableListOf<Feature>()
    if (fix != null) {
        src(SRC_ME)?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(fix.lon, fix.lat)))
        // speed cameras belong to the traffic alerts and their opt-in (StVO § 23 Abs. 1c), like the warning itself
        src(SRC_CAMS)?.setGeoJson(FeatureCollection.fromFeatures(if (!Funk.settings.value.traffic) emptyList() else Funk.speedCams.near(fix.lat, fix.lon, 5_000.0)
            .map { Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)) }))
        src(SRC_TRAFFIC)?.setGeoJson(FeatureCollection.fromFeatures(Funk.trafficCams.near(fix.lat, fix.lon, 5_000.0).map {
            Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)).apply { addStringProperty("c", hex(trafficColor(it.kind))) }
        }))
        // vehicles that come along have no fixed spot: a ring around you, one per follower, slightly larger each
        devices.filter { it.isFollowingVehicle() }.forEachIndexed { i, _ -> rings += ring(fix.lat, fix.lon, 40.0 + i * 12, hex(Category.FLEET.color())) }
        if (offline) src(SRC_GRID)?.setGeoJson(grid(fix.lat, fix.lon))
    }
    // heard in the last minute of the scan (not the clock: the demo and a stopped scan keep their picture)
    val newest = devices.maxOfOrNull { it.lastSeen } ?: 0L
    src(SRC_NEAR)?.setGeoJson(FeatureCollection.fromFeatures(if (!nearby || fix == null) emptyList() else devices
        .filter { it.pos == null && !it.own && it.cls.mute == null && newest - it.lastSeen < 60_000 }
        .map { d -> spotAt(fix.lat, fix.lon, distanceFromRssi(d.rssi).coerceIn(25.0, 200.0), angle(d.id).toDouble()).let { (la, lo) -> d.feature(la, lo) } }))
    // with "All devices" the bundled others show before street zoom too
    for (id in listOf("fb-other-cluster", "fb-other-count", LAYER_OTHER)) style.getLayer(id)?.minZoom = if (nearby) 0f else 15f
    devices.firstOrNull { it.id == selected }?.pos?.let { p -> rings += ring(p.lat, p.lon, p.radiusM, "#F2E8D5") }
    src(SRC_RING)?.setGeoJson(FeatureCollection.fromFeatures(rings))
}

fun initMap(context: Context) {
    MapLibre.getInstance(context.applicationContext)
    // every tile request tells the map server roughly where you are; switched off, only saved regions and the cache are used
    MapLibre.setConnected(Funk.settings.value.mapData)
}

/**
 * follow: camera follows your position (and turns with the driving direction when moving).
 * nearby: also devices without a position estimate, around you (off in car mode, kept simple).
 * onTap: a device was tapped (its id). onOffline: the online style couldn't load.
 */
@Composable
fun FunkMap(modifier: Modifier = Modifier, look: MapLook = MapLook.DARK, follow: Boolean = true, selected: String? = null, nearby: Boolean = false,
            onTap: (String) -> Unit = {}, onFollowLost: () -> Unit = {}, onOffline: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val devices by Funk.devices.collectAsStateWithLifecycle()
    val fix by Funk.fix.collectAsStateWithLifecycle()
    val track by Funk.track.collectAsStateWithLifecycle()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var offline by remember { mutableStateOf(false) }
    val tap by rememberUpdatedState(onTap)
    val lost by rememberUpdatedState(onFollowLost)
    val view = remember {
        initMap(context)
        MapView(context).apply { onCreate(null) }
    }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> view.onStart()
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_STOP -> view.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); view.onPause(); view.onStop(); view.onDestroy() }
    }
    LaunchedEffect(look) {
        view.getMapAsync { m ->
            map = m
            m.uiSettings.isCompassEnabled = false
            m.uiSettings.isAttributionEnabled = true // © OpenStreetMap stays visible, as the licence asks
            m.uiSettings.isLogoEnabled = false
            m.addOnCameraMoveStartedListener { reason -> if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) lost() }
            m.addOnMapClickListener { ll ->
                val f = m.queryRenderedFeatures(m.projection.toScreenLocation(ll), LAYER_NOTABLE, LAYER_NEAR, LAYER_OTHER).firstOrNull()
                f?.getStringProperty("id")?.let { tap(it); true } ?: false
            }
            view.addOnDidFailLoadingMapListener {
                offline = true
                onOffline(true)
                m.setStyle(Style.Builder().fromJson(GRID_STYLE)) { s -> addOverlay(s, look == MapLook.LIGHT); style = s }
            }
            m.setStyle(Style.Builder().fromUri(look.url)) { s ->
                if (look == MapLook.DARK) tintDark(s)
                addOverlay(s, look == MapLook.LIGHT)
                style = s
                offline = false
                onOffline(false)
            }
            m.cameraPosition = CameraPosition.Builder().target(LatLng(fix?.lat ?: 51.1657, fix?.lon ?: 10.4515)).zoom(if (fix == null) 5.5 else 15.0).build() // no fix: all of Germany
        }
    }
    LaunchedEffect(style, devices, fix, track, selected, offline, nearby) {
        style?.let { update(it, devices, fix, track, selected, offline, nearby) }
    }
    LaunchedEffect(map, fix, follow) {
        val m = map ?: return@LaunchedEffect
        val f = fix ?: return@LaunchedEffect
        if (!follow) return@LaunchedEffect
        val moving = (f.speedKmh ?: 0f) > 8f
        m.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(LatLng(f.lat, f.lon))
            .zoom(if (moving) 15.5 else m.cameraPosition.zoom.coerceAtLeast(14.0))
            .bearing(if (moving) (f.bearing ?: 0f).toDouble() else m.cameraPosition.bearing).build()), 900)
    }
    val notableCount = devices.count { it.cls.category.ordinal <= Category.FLEET.ordinal }
    AndroidView({ view }, modifier.semantics {
        contentDescription = tr("Karte, $notableCount auffällige Geräte", "Map, $notableCount notable devices")
    })
}

/**
 * Saves the map around [lat]/[lon] (about 50 × 50 km, zoom 8 to 14) for use without network, like Organic Maps'
 * downloaded regions. Calls back with progress 0..1 and once with the result.
 */
fun saveOfflineRegion(context: Context, lat: Double, lon: Double, onProgress: (Float) -> Unit, onDone: (Boolean) -> Unit) {
    initMap(context)
    val bounds = org.maplibre.android.geometry.LatLngBounds.from(lat + 0.25, lon + 0.4, lat - 0.25, lon - 0.4)
    val def = org.maplibre.android.offline.OfflineTilePyramidRegionDefinition(MapLook.DARK.url, bounds, 8.0, 14.0, context.resources.displayMetrics.density)
    org.maplibre.android.offline.OfflineManager.getInstance(context).createOfflineRegion(def, "funkblick".toByteArray(),
        object : org.maplibre.android.offline.OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(offlineRegion: org.maplibre.android.offline.OfflineRegion) {
                offlineRegion.setObserver(object : org.maplibre.android.offline.OfflineRegion.OfflineRegionObserver {
                    override fun onStatusChanged(status: org.maplibre.android.offline.OfflineRegionStatus) {
                        if (status.requiredResourceCount > 0) onProgress(status.completedResourceCount.toFloat() / status.requiredResourceCount)
                        if (status.isComplete) {
                            offlineRegion.setDownloadState(org.maplibre.android.offline.OfflineRegion.STATE_INACTIVE)
                            onDone(true)
                        }
                    }
                    override fun onError(error: org.maplibre.android.offline.OfflineRegionError) = onDone(false)
                    override fun mapboxTileCountLimitExceeded(limit: Long) = onDone(false)
                })
                offlineRegion.setDownloadState(org.maplibre.android.offline.OfflineRegion.STATE_ACTIVE)
            }
            override fun onError(error: String) = onDone(false)
        })
}
