/*
 * Copyright (c) 2026, PocketMS contributors
 */

package sync

import kotlinx.browser.document
import kotlinx.browser.window
import mono.remote.sync.RemoteSyncManager
import mono.store.dao.workspace.WorkspaceDao
import org.w3c.dom.Node
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event

/**
 * Renders a small "!" icon next to the brand title whenever the
 * RemoteSyncManager has paused-conflict drawings. Click opens a
 * popover listing each affected drawing and offering two resolutions:
 *
 *  - Use server: refetch the drawing, replace local content, reload
 *    the page so the editor's in-memory state resyncs.
 *  - Keep mine: adopt the server's version number so the next push
 *    succeeds, overwriting the server.
 *
 * The popover does not move existing UI; it appears as a flex sibling
 * inside #nav-brand.
 */
class ConflictBanner(private val manager: RemoteSyncManager) {

    fun mount() {
        val brand = document.getElementById("nav-brand") ?: return
        if (document.getElementById("sync-conflict-mount") != null) return

        val wrapper = document.createElement("div") as HTMLDivElement
        wrapper.id = "sync-conflict-mount"
        wrapper.className = "relative ml-3"
        wrapper.innerHTML = """
            <button id="sync-conflict-icon" type="button"
                    class="hidden items-center justify-center w-6 h-6 rounded-full bg-yellow-500 text-zinc-900 text-xs font-bold hover:bg-yellow-400"
                    title="Sync conflicts">!</button>
            <div id="sync-conflict-popover"
                 class="hidden absolute top-full left-0 mt-2 w-80 p-3 rounded bg-zinc-800 border border-zinc-700 shadow-lg z-50">
              <h3 class="text-sm font-semibold text-zinc-100 mb-2">Sync conflicts</h3>
              <div id="sync-conflict-list" class="text-xs"></div>
            </div>
        """.trimIndent()
        brand.appendChild(wrapper)

        val icon = document.getElementById("sync-conflict-icon") as HTMLButtonElement
        val popover = document.getElementById("sync-conflict-popover") as HTMLDivElement
        icon.addEventListener("click", { event ->
            event.stopPropagation()
            popover.classList.toggle("hidden")
        })
        document.addEventListener("click", { event ->
            val target = event.target as? Node ?: return@addEventListener
            if (!wrapper.contains(target)) popover.classList.add("hidden")
        })

        render(manager.activeConflicts)
        manager.conflictListener = { conflicts -> render(conflicts) }
    }

    private fun render(conflicts: Map<String, Int>) {
        val icon = document.getElementById("sync-conflict-icon") as? HTMLButtonElement ?: return
        val popover = document.getElementById("sync-conflict-popover") as? HTMLDivElement ?: return
        val list = document.getElementById("sync-conflict-list") as? HTMLDivElement ?: return

        if (conflicts.isEmpty()) {
            icon.classList.add("hidden")
            icon.classList.remove("inline-flex")
            popover.classList.add("hidden")
            list.innerHTML = ""
            return
        }

        icon.classList.remove("hidden")
        icon.classList.add("inline-flex")

        list.innerHTML = conflicts.entries.joinToString("") { (objectId, currentVersion) ->
            val safeName = escapeHtml(safeName(objectId))
            """
            <div data-object-id="$objectId" class="mb-2 pb-2 border-b border-zinc-700 last:border-b-0 last:mb-0 last:pb-0">
              <div class="text-zinc-100 mb-1 truncate" title="$safeName">$safeName</div>
              <div class="text-zinc-400 mb-2">Server is at version $currentVersion.</div>
              <div class="flex gap-2">
                <button data-action="server" type="button"
                        class="px-2 py-1 rounded bg-blue-600 text-white hover:bg-blue-500">Use server</button>
                <button data-action="mine" type="button"
                        class="px-2 py-1 rounded border border-zinc-600 text-zinc-200 hover:bg-zinc-700">Keep mine</button>
              </div>
            </div>
            """.trimIndent()
        }

        bindRowButtons(list)
    }

    private fun bindRowButtons(list: HTMLDivElement) {
        val nodes = list.querySelectorAll("button[data-action]")
        for (i in 0 until nodes.length) {
            val button = nodes.item(i) as? HTMLButtonElement ?: continue
            button.addEventListener("click", handler@{ event: Event ->
                event.stopPropagation()
                val row = button.closest("[data-object-id]") as? HTMLElement
                    ?: return@handler
                val id = row.getAttribute("data-object-id") ?: return@handler
                when (button.getAttribute("data-action")) {
                    "server" -> {
                        button.disabled = true
                        manager.reloadFromServer(id)
                            .then { window.location.reload() }
                            .catch { err ->
                                button.disabled = false
                                console.error("Reload from server failed:", err)
                            }
                    }
                    "mine" -> manager.overwriteServer(id)
                }
            })
        }
    }

    private fun safeName(objectId: String): String {
        val name = try {
            WorkspaceDao.instance.getObject(objectId).name
        } catch (_: Throwable) {
            objectId
        }
        return if (name.isBlank()) objectId else name
    }

    private fun escapeHtml(input: String): String =
        input.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
