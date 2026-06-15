package dev.advaitm.coreglobe.renderer

import dev.advaitm.coreglobe.api.GlobeState

expect class GlobeRenderer {
    fun initialize(state: GlobeState): Any
    fun updateState(state: GlobeState)
    fun destroy()
}
