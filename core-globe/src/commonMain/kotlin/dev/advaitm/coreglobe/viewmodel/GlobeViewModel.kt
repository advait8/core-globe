package dev.advaitm.coreglobe.viewmodel

import dev.advaitm.coreglobe.api.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class GlobeViewModel {
    private val _state = MutableStateFlow(GlobeState())
    val state: StateFlow<GlobeState> = _state.asStateFlow()

    fun setMarkers(markers: List<GlobeMarker>) {
        _state.update { it.copy(markers = markers) }
    }

    fun setArcs(arcs: List<GlobeArc>) {
        _state.update { it.copy(arcs = arcs) }
    }

    fun updateConfig(config: GlobeConfig) {
        _state.update { it.copy(config = config) }
    }

    fun onDragEnd(rotationX: Float, rotationY: Float) {
        _state.update { it.copy(rotationX = rotationX, rotationY = rotationY, isDragging = false) }
    }

    fun flyTo(target: Coordinates) {
        // V1 stub — animate rotationY/X toward target in a future update
    }
}
