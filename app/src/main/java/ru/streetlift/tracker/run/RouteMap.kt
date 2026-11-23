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
    /** Где вы сейчас и точность (м) — синий круг на карте. */
    current: RunTracker.Pt? = null,
    accuracy: Float? = null,
    /** Раскрасить маршрут по темпу: зелёный — быстрее, красный — медленнее. */
    paceColors: Boolean = false,
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
        m.overlays.removeAll { it is Polyline || it is org.osmdroid.views.overlay.Polygon }
        current?.let { c ->
            val gp = GeoPoint(c.lat, c.lon)
            // Круг точности и точка «вы здесь»
            m.overlays.add(
                org.osmdroid.views.overlay.Polygon(m).apply {
                    points = org.osmdroid.views.overlay.Polygon.pointsAsCircle(gp, (accuracy ?: 15f).toDouble().coerceIn(8.0, 500.0))
                    fillPaint.color = 0x332196F3
                    outlinePaint.color = 0x882196F3.toInt()
                    outlinePaint.strokeWidth = 2f
                },
            )
            m.overlays.add(
                org.osmdroid.views.overlay.Polygon(m).apply {
                    points = org.osmdroid.views.overlay.Polygon.pointsAsCircle(gp, 4.0)
                    fillPaint.color = 0xFF2196F3.toInt()
                    outlinePaint.color = 0xFFFFFFFF.toInt()
                    outlinePaint.strokeWidth = 3f
                },
            )
        }
        val pts = segments.flatten().map { GeoPoint(it.lat, it.lon) }
        val colored = if (paceColors) paceChunks(segments) else null
        if (colored != null) {
            colored.forEach { (pts, color) ->
                m.overlays.add(
                    Polyline(m).apply {
                        setPoints(pts.map { GeoPoint(it.lat, it.lon) })
                        outlinePaint.color = color
                        outlinePaint.strokeWidth = 12f
                        outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                    },
                )
            }
        } else segments.filter { it.size >= 2 }.forEach { seg ->
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
            follow && (pts.isNotEmpty() || current != null) -> {
                if (m.zoomLevelDouble < 15.0) m.controller.setZoom(17.0)
                m.controller.setCenter(current?.let { GeoPoint(it.lat, it.lon) } ?: pts.last())
            }
            current != null && pts.isEmpty() -> {
                if (m.zoomLevelDouble < 15.0) m.controller.setZoom(16.0)
                m.controller.setCenter(GeoPoint(current.lat, current.lon))
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

/**
 * Куски маршрута ~50 м с цветом по темпу (зелёный — быстрее, красный — медленнее,
 * шкала по 10–90 перцентилям пробежки). null — у маршрута нет времени точек.
 */
private fun paceChunks(segments: List<List<RunTracker.Pt>>): List<Pair<List<RunTracker.Pt>, Int>>? {
    val chunks = ArrayList<Pair<List<RunTracker.Pt>, Double>>()
    segments.forEach { seg ->
        if (seg.any { it.t == null }) return null
        var cur = arrayListOf<RunTracker.Pt>()
        var d = 0.0
        seg.forEach { p ->
            cur.lastOrNull()?.let { d += ru.streetlift.tracker.domain.RunStats.dist(it.lat, it.lon, p.lat, p.lon) }
            cur.add(p)
            if (d >= 50 && cur.size >= 2) {
                val dt = (cur.last().t!! - cur.first().t!!) / 1000.0
                chunks += cur.toList() to dt / (d / 1000.0)
                cur = arrayListOf(p)
                d = 0.0
            }
        }
        if (cur.size >= 2 && d > 0) {
            val dt = (cur.last().t!! - cur.first().t!!) / 1000.0
            chunks += cur.toList() to dt / (d / 1000.0)
        }
    }
    if (chunks.isEmpty()) return null
    val sorted = chunks.map { it.second }.sorted()
    val fast = sorted[(sorted.size * 0.1).toInt().coerceIn(0, sorted.size - 1)]
    val slow = sorted[(sorted.size * 0.9).toInt().coerceIn(0, sorted.size - 1)]
    return chunks.map { (pts, pace) ->
        val f = if (slow - fast < 1) 0.5 else ((pace - fast) / (slow - fast)).coerceIn(0.0, 1.0)
        // зелёный (76,175,80) → жёлтый (255,193,7) → красный (244,67,54)
        val (r, g, b) = if (f < 0.5) {
            val k = f / 0.5
            Triple(76 + (255 - 76) * k, 175 + (193 - 175) * k, 80 + (7 - 80) * k)
        } else {
            val k = (f - 0.5) / 0.5
            Triple(255 + (244 - 255) * k, 193 + (67 - 193) * k, 7 + (54 - 7) * k)
        }
        pts to android.graphics.Color.rgb(r.toInt(), g.toInt(), b.toInt())
    }
}
