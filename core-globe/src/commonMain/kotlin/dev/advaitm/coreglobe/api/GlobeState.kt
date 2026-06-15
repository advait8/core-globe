package dev.advaitm.coreglobe.api

data class GlobeState(
    val rotationX: Float            = (Math.PI / 4).toFloat(),
    val rotationY: Float            = 0f,
    val markers: List<GlobeMarker>  = emptyList(),
    val arcs: List<GlobeArc>        = emptyList(),
    val config: GlobeConfig         = GlobeConfig(),
    val isDragging: Boolean         = false
)
