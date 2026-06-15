package dev.advaitm.coreglobe.api

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
data class GlobeMarker(
    val id: String = Uuid.random().toString(),
    val lat: Double,
    val lng: Double,
    val style: MarkerStyle = MarkerStyle.Default,
    val label: String? = null
)

sealed class MarkerStyle {
    object Default     : MarkerStyle()
    object Current     : MarkerStyle()
    object Destination : MarkerStyle()
    object Visited     : MarkerStyle()
    data class Custom(
        val colorHex: String,
        val size: Float = 1f,
        val pulse: Boolean = false
    ) : MarkerStyle()
}
