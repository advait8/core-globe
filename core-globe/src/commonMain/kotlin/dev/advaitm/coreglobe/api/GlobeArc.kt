package dev.advaitm.coreglobe.api

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
data class GlobeArc(
    val id: String = Uuid.random().toString(),
    val from: Coordinates,
    val to: Coordinates,
    val style: ArcStyle = ArcStyle.Flight,
    val animationProgress: Float = 1f
)

sealed class ArcStyle {
    object Flight  : ArcStyle()
    object Dashed  : ArcStyle()
    data class Custom(val colorHex: String, val width: Float = 1f) : ArcStyle()
}
