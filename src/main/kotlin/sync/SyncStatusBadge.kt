/*
 * Copyright (c) 2026, PocketMS contributors
 */

package sync

import kotlinx.browser.document
import mono.remote.sync.RemoteSyncManager
import mono.remote.sync.SyncStatus
import org.w3c.dom.HTMLSpanElement

/**
 * Small non-blocking text indicator that appears next to the brand
 * title while the sync state is anything other than Synced.
 *
 *   - Pending  -> "Saving…" (yellow)
 *   - Offline  -> "Offline" (red)
 *
 * Genuine sync conflicts are handled by [ConflictModal] and never
 * show here.
 */
class SyncStatusBadge(private val manager: RemoteSyncManager) {

    private var span: HTMLSpanElement? = null

    fun mount() {
        val brand = document.getElementById("nav-brand") ?: return
        if (document.getElementById("sync-status-badge") != null) return

        val el = document.createElement("span") as HTMLSpanElement
        el.id = "sync-status-badge"
        el.className = "hidden ml-3 px-2 py-0.5 rounded text-xs font-medium"
        brand.appendChild(el)
        span = el

        render(manager.syncStatus)
        manager.statusListener = { status -> render(status) }
    }

    private fun render(status: SyncStatus) {
        val el = span ?: return
        when (status) {
            SyncStatus.Synced -> {
                el.classList.add("hidden")
            }
            SyncStatus.Pending -> {
                el.textContent = "Saving…"
                el.className =
                    "ml-3 px-2 py-0.5 rounded text-xs font-medium bg-yellow-500/20 text-yellow-300"
            }
            SyncStatus.Offline -> {
                el.textContent = "Offline"
                el.className =
                    "ml-3 px-2 py-0.5 rounded text-xs font-medium bg-red-500/20 text-red-300"
            }
        }
    }
}
