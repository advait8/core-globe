package dev.advaitm.coreglobe.bridge

import android.webkit.JavascriptInterface

class GlobeBridge(private val onEvent: (String) -> Unit) {
    @JavascriptInterface
    fun onEvent(json: String) {
        onEvent.invoke(json)
    }
}
