package dev.advaitm.sample

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import dev.advaitm.coreglobe.api.ArcStyle
import dev.advaitm.coreglobe.api.Coordinates
import dev.advaitm.coreglobe.api.GlobeArc
import dev.advaitm.coreglobe.api.GlobeConfig
import dev.advaitm.coreglobe.api.GlobeMarker
import dev.advaitm.coreglobe.api.MarkerStyle
import dev.advaitm.coreglobe.ui.GlobeView
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val sfo = Coordinates(37.77, -122.41)
    private val tyo = Coordinates(35.68, 139.69)
    private val hnl = Coordinates(21.30, -157.85)
    private val rek = Coordinates(64.13, -21.94)
    private val scl = Coordinates(-33.45, -70.66)

    private val markers = listOf(
        GlobeMarker(id = "sfo", lat = sfo.lat, lng = sfo.lng, style = MarkerStyle.Current,     label = "San Francisco"),
        GlobeMarker(id = "tyo", lat = tyo.lat, lng = tyo.lng, style = MarkerStyle.Destination, label = "Tokyo"),
        GlobeMarker(id = "hnl", lat = hnl.lat, lng = hnl.lng, style = MarkerStyle.Destination, label = "Honolulu"),
        GlobeMarker(id = "rek", lat = rek.lat, lng = rek.lng, style = MarkerStyle.Destination, label = "Reykjavik"),
        GlobeMarker(id = "scl", lat = scl.lat, lng = scl.lng, style = MarkerStyle.Destination, label = "Santiago"),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var arcs by remember {
                mutableStateOf(
                    listOf(
                        // Dashed: potential destinations from SFO
                        GlobeArc(id = "sfo_tyo", from = sfo, to = tyo, style = ArcStyle.Dashed),
                        GlobeArc(id = "sfo_rek", from = sfo, to = rek, style = ArcStyle.Dashed),
                        // Trail: a previous leg (Honolulu to SFO)
                        GlobeArc(id = "hnl_sfo_trail", from = hnl, to = sfo, style = ArcStyle.Trail),
                    )
                )
            }
            var flightTarget by remember { mutableStateOf<Coordinates?>(null) }
            var showLandingOverlay by remember { mutableStateOf(false) }
            val landingOverlayAlpha by animateFloatAsState(
                targetValue = if (showLandingOverlay) 1f else 0f,
                animationSpec = tween(1000),
                label = "landingOverlayAlpha"
            )

            LaunchedEffect(Unit) {
                delay(2000)
                // Flight: solid arc that draws itself in while the globe rotates + zooms toward the destination
                arcs = listOf(
                    GlobeArc(id = "sfo_tyo", from = sfo, to = tyo, style = ArcStyle.Flight, animationProgress = 0f),
                    GlobeArc(id = "hnl_sfo_trail", from = hnl, to = sfo, style = ArcStyle.Trail),
                )
                flightTarget = tyo
            }

            Box(modifier = Modifier.fillMaxSize()) {
                GlobeView(
                    markers = markers,
                    arcs = arcs,
                    config = GlobeConfig(showBorders = false, showGrid = false),
                    animateFlightTo = flightTarget,
                    animateFlightFrom = sfo,
                    onMarkerTapped = { marker ->
                        Log.d("GlobeSample", "Marker tapped: ${marker.id} (${marker.label})")
                    },
                    onArcAnimationComplete = { arcId ->
                        Log.d("GlobeSample", "Arc animation complete: $arcId")
                    },
                    onFlightComplete = {
                        Log.d("GlobeSample", "Flight complete — landing overlay would fade in here")
                        showLandingOverlay = true
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Stand-in for a real city photo: proves onFlightComplete fires at the right
                // moment (camera fully zoomed in) before any landing content fades in.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(landingOverlayAlpha)
                        .background(Color(0xFF1A4088)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Landed in Tokyo", color = Color.White)
                }
            }
        }
    }
}
