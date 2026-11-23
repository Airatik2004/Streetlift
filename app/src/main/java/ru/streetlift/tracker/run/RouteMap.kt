package ru.streetlift.tracker.run

import android.content.Context
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline

/** Настройка OpenStreetMap: свой user-agent и кэш плиток во внутренней памяти. */
fun initOsm(context: Context) {
    val c = Configuration.getInstance()
    c.userAgentValue = context.packageName
    val base = java.io.File(context.filesDir, "osmdroid")
    c.osmdroidBasePath = base
    c.osmdroidTileCache = java.io.File(base, "tiles")
}

/** Карта, которая не перехватывает касания (свайпы уходят вкладкам). */
private class PassiveMapView(context: Context) : MapView(context) {
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean = false
}

/**
 * Карта с маршрутом. [interactive] — можно двигать и масштабировать; иначе карта сама
 * следует за последней точкой ([follow]) или показывает весь маршрут.
 */
@Composable
fun RouteMap(
    segments: List<List<RunTracker.Pt>>,
    modifier: Modifier = Modifier,
    interactive: Boolean = false,
    follow: Boolean = false,
    fallback: RunTracker.Pt? = null,
) {
    val ctx = LocalContext.current
    val map = remember {
        initOsm(ctx)
        (if (interactive) MapView(ctx) else PassiveMapView(ctx)).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(interactive)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(3.0)
            isTilesScaledToDpi = true
            onResume()
        }
    }
    DisposableEffect(map) { onDispose { map.onDetach() } }
    AndroidView(factory = { map }, modifier = modifier, update = { m ->
        m.overlays.removeAll { it is Polyline }
        val pts = segments.flatten().map { GeoPoint(it.lat, it.lon) }
        segments.filter { it.size >= 2 }.forEach { seg ->
            m.overlays.add(
                Polyline(m).apply {
                    setPoints(seg.map { GeoPoint(it.lat, it.lon) })
                    outlinePaint.color = 0xFF2196F3.toInt()
                    outlinePaint.strokeWidth = 12f
                    outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                },
            )
        }
        when {
            follow && pts.isNotEmpty() -> {
                if (m.zoomLevelDouble < 15.0) m.controller.setZoom(17.0)
                m.controller.setCenter(pts.last())
            }
            pts.size >= 2 -> {
                val box = BoundingBox.fromGeoPointsSafe(pts)
                m.post { runCatching { m.zoomToBoundingBox(box.increaseByScale(1.3f), false) } }
            }
            pts.size == 1 -> {
                m.controller.setZoom(17.0)
                m.controller.setCenter(pts.first())
            }
            fallback != null -> {
                if (m.zoomLevelDouble < 15.0) m.controller.setZoom(16.0)
                m.controller.setCenter(GeoPoint(fallback.lat, fallback.lon))
            }
        }
        m.invalidate()
    })
}
