package com.example.routemock

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

@Composable
fun RouteMap(
    modifier: Modifier,
    start: LatLon?,
    end: LatLon?,
    route: Route?,
    current: LatLon?,
    follow: Boolean,
    onTap: (LatLon) -> Unit,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val onTapNow by rememberUpdatedState(onTap)
    val routeColor = MaterialTheme.colorScheme.primary.toArgb()

    val map = remember {
        Configuration.getInstance().userAgentValue = context.packageName
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(12.0)
            controller.setCenter(GeoPoint(50.4501, 30.5234))
            overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                    onTapNow(LatLon(p.latitude, p.longitude))
                    return true
                }

                override fun longPressHelper(p: GeoPoint): Boolean = false
            }))
        }
    }

    DisposableEffect(owner, map) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> map.onResume()
                Lifecycle.Event.ON_PAUSE -> map.onPause()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            map.onDetach()
        }
    }

    AndroidView(
        factory = { map },
        modifier = modifier,
        update = { view ->
            view.overlays.removeAll { it is Marker || it is Polyline }
            route?.let { r ->
                view.overlays.add(Polyline().apply {
                    setPoints(r.points.map { GeoPoint(it.lat, it.lon) })
                    outlinePaint.color = routeColor
                    outlinePaint.strokeWidth = 14f
                })
            }
            start?.let { view.overlays.add(marker(view, it, "Старт")) }
            end?.let { view.overlays.add(marker(view, it, "Финиш")) }
            current?.let { view.overlays.add(marker(view, it, "Вы здесь")) }
            view.invalidate()
        },
    )

    LaunchedEffect(route) {
        route?.let { r ->
            map.zoomToBoundingBox(
                BoundingBox.fromGeoPointsSafe(r.points.map { GeoPoint(it.lat, it.lon) }),
                true,
                120,
            )
        }
    }

    LaunchedEffect(current, follow) {
        if (follow && current != null) map.controller.setCenter(GeoPoint(current.lat, current.lon))
    }
}

private fun marker(map: MapView, p: LatLon, title: String) = Marker(map).apply {
    position = GeoPoint(p.lat, p.lon)
    this.title = title
    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
}
