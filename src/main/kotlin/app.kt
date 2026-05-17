/*
 * Copyright (c) 2023, tuanchauict
 */

@file:Suppress("ktlint:filename")

import auth.AuthGate
import kotlinx.browser.document
import kotlinx.browser.window
import mono.app.MonoSketchApplication
import mono.remote.RemoteClient

fun main() {
    AuthGate(RemoteClient()).start {
        val application = MonoSketchApplication()
        if (document.readyState.toString() == "loading") {
            window.onload = { application.onStart() }
        } else {
            application.onStart()
        }
        window.onresize = { application.onResize() }
    }
}
