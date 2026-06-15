package dev.advaitm.coreglobe.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import dev.advaitm.coreglobe.api.*
import dev.advaitm.coreglobe.renderer.GlobeRenderer
import dev.advaitm.coreglobe.viewmodel.GlobeViewModel
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.foundation.layout.fillMaxSize

@Composable
fun GlobeView(
    markers: List<GlobeMarker> = emptyList(),
    arcs: List<GlobeArc> = emptyList(),
    config: GlobeConfig = GlobeConfig(),
    onMarkerTapped: (GlobeMarker) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val viewModel = remember { GlobeViewModel() }
    val context = LocalContext.current
    val renderer  = remember { GlobeRenderer(context) }
    val state by viewModel.state.collectAsState()

    LaunchedEffect(markers) { viewModel.setMarkers(markers) }
    LaunchedEffect(arcs)    { viewModel.setArcs(arcs) }
    LaunchedEffect(config)  { viewModel.updateConfig(config) }
    LaunchedEffect(state)   { renderer.updateState(state) }

    LaunchedEffect(renderer) {
        renderer.onBridgeEvent = { json ->
            try {
                val obj = org.json.JSONObject(json)
                when (obj.optString("event")) {
                    "dragEnd" -> viewModel.onDragEnd(
                        obj.getDouble("rotationX").toFloat(),
                        obj.getDouble("rotationY").toFloat()
                    )
                    "markerTapped" -> {
                        val markerId = obj.optString("markerId")
                        val marker = state.markers.find { it.id == markerId }
                        marker?.let { onMarkerTapped(it) }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    AndroidView(
        factory  = { renderer.initialize(state) as android.view.View },
        modifier = modifier
    )

    DisposableEffect(Unit) {
        onDispose { renderer.destroy() }
    }
}
