package dev.advaitm.coreglobe.api

data class GlobeConfig(
    val globeColor: String          = "#0C1E3C",
    val gridColor: String           = "#142D62",
    val atmosphereColor: String     = "#1A4088",
    val currentDotColor: String     = "#4A9EFF",
    val destinationDotColor: String = "#F5A623",
    val arcColor: String            = "#4A9EFF",
    val backgroundColor: String     = "#020B18",
    val showGrid: Boolean           = true,
    val showAtmosphere: Boolean     = true,
    val showStars: Boolean          = true,
    val showBorders: Boolean        = true,
    val borderColor: String         = "#1E3A6E",
    val autoRotate: Boolean         = true,
    val autoRotateSpeed: Float      = 0.0022f,
    val tiltRadians: Float          = (Math.PI / 4).toFloat(),
    val cameraDistance: Float       = 5.0f
)
