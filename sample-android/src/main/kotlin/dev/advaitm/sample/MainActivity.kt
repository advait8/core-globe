package dev.advaitm.sample

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import dev.advaitm.coreglobe.api.GlobeMarker
import dev.advaitm.coreglobe.api.MarkerStyle
import dev.advaitm.coreglobe.ui.GlobeView

class MainActivity : ComponentActivity() {

    private val markers = listOf(
        GlobeMarker(id = "sfo",        lat = 37.77,   lng = -122.41, style = MarkerStyle.Current,     label = "San Francisco"),
        GlobeMarker(id = "tyo",        lat = 35.68,   lng =  139.69, style = MarkerStyle.Destination, label = "Tokyo"),
        GlobeMarker(id = "hnl",        lat = 21.30,   lng = -157.85, style = MarkerStyle.Destination, label = "Honolulu"),
        GlobeMarker(id = "rek",        lat = 64.13,   lng =  -21.94, style = MarkerStyle.Destination, label = "Reykjavik"),
        GlobeMarker(id = "scl",        lat = -33.45,  lng =  -70.66, style = MarkerStyle.Destination, label = "Santiago"),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GlobeView(
                markers = markers,
                onMarkerTapped = { marker ->
                    Log.d("GlobeSample", "Marker tapped: ${marker.id} (${marker.label})")
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
