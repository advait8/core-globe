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
    /** Solid tube that draws itself in over ~1.5s when [GlobeArc.animationProgress] starts below 1. */
    object Flight  : ArcStyle()

    /** Dashed, semi-transparent line. Always renders fully drawn — used for potential destinations. */
    object Dashed  : ArcStyle()

    /** Solid tube, low opacity, renders immediately at full draw. Used for past journey legs. */
    object Trail   : ArcStyle()

    data class Custom(val colorHex: String, val width: Float = 1f) : ArcStyle()
}
