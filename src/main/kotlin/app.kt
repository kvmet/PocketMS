/*
 * Copyright (c) 2023, tuanchauict
 */

@file:Suppress("ktlint:filename")

import auth.AuthGate
import kotlinx.browser.document
import kotlinx.browser.window
import mono.app.MonoSketchApplication
import mono.remote.RemoteClient
import mono.remote.sync.RemoteSyncManager
import org.w3c.dom.HTMLDivElement

fun main() {
    val client = RemoteClient()
    AuthGate(client).start {
        RemoteSyncManager(client).start()
            .then {
                val application = MonoSketchApplication()
                if (document.readyState.toString() == "loading") {
                    window.onload = { application.onStart() }
                } else {
                    application.onStart()
                }
                window.onresize = { application.onResize() }
                Unit
            }
            .catch { err ->
                showStartupError(err)
            }
    }
}

private fun showStartupError(err: Throwable) {
    val message = err.message ?: err::class.simpleName ?: "unknown error"
    val container = document.createElement("div") as HTMLDivElement
    container.id = "startup-error"
    container.className =
        "fixed inset-0 z-50 flex items-center justify-center bg-zinc-950 text-zinc-100"
    container.innerHTML = """
        <div class="max-w-md p-6 rounded-md shadow-lg bg-zinc-800 border border-zinc-700">
          <h1 class="text-lg font-semibold mb-2">Could not sync with the server</h1>
          <p class="text-sm text-zinc-300 mb-4">$message</p>
          <p class="text-xs text-zinc-400 mb-4">Your local data has not been touched. Refresh to try again.</p>
          <button id="startup-error-retry" type="button"
                  class="px-3 py-1.5 rounded bg-blue-600 text-white text-sm hover:bg-blue-500">
            Retry
          </button>
        </div>
    """.trimIndent()
    document.body?.appendChild(container)
    document.getElementById("startup-error-retry")?.addEventListener("click", {
        window.location.reload()
    })
}
