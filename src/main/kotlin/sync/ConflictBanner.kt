/*
 * Copyright (c) 2026, PocketMS contributors
 */

package sync

import kotlinx.browser.document
import kotlinx.browser.window
import mono.remote.sync.RemoteSyncManager
import mono.store.dao.workspace.WorkspaceDao
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.Node
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
 * The icon sits inside #nav-brand. The popover is appended to
 * document.body and positioned with position:fixed so it floats above
 * the editor regardless of any ancestor's overflow or stacking
 * context.
 */
class ConflictBanner(private val manager: RemoteSyncManager) {

    private var icon: HTMLButtonElement? = null
    private var popover: HTMLDivElement? = null
    private var list: HTMLDivElement? = null

    fun mount() {
        val brand = document.getElementById("nav-brand") ?: return
        if (document.getElementById("sync-conflict-icon") != null) return

        val iconEl = document.createElement("button") as HTMLButtonElement
        iconEl.id = "sync-conflict-icon"
        iconEl.type = "button"
        iconEl.title = "Sync conflicts"
        iconEl.textContent = "!"
        iconEl.className = "hidden ml-3 items-center justify-center w-6 h-6 rounded-full " +
            "bg-yellow-500 text-zinc-900 text-xs font-bold hover:bg-yellow-400 cursor-pointer"
        brand.appendChild(iconEl)
        icon = iconEl

        val popoverEl = document.createElement("div") as HTMLDivElement
        popoverEl.id = "sync-conflict-popover"
        popoverEl.className =
            "hidden fixed w-80 p-3 rounded bg-zinc-800 border border-zinc-700 shadow-lg"
        popoverEl.style.zIndex = "10001"
        popoverEl.innerHTML = """
            <h3 class="text-sm font-semibold text-zinc-100 mb-2">Sync conflicts</h3>
            <div id="sync-conflict-list" class="text-xs text-zinc-100"></div>
        """.trimIndent()
        document.body?.appendChild(popoverEl)
        popover = popoverEl
        list = document.getElementById("sync-conflict-list") as? HTMLDivElement

        iconEl.addEventListener("click", { event ->
            event.stopPropagation()
            togglePopover(popoverEl, iconEl)
        })
        document.addEventListener("click", { event ->
            val target = event.target as? Node ?: return@addEventListener
            if (popoverEl.contains(target) || iconEl.contains(target)) return@addEventListener
            popoverEl.classList.add("hidden")
        })
        window.addEventListener("resize", {
            if (!popoverEl.classList.contains("hidden")) {
                positionPopover(popoverEl, iconEl)
            }
        })

        render(manager.activeConflicts)
        manager.conflictListener = { conflicts -> render(conflicts) }
    }

    private fun togglePopover(popover: HTMLDivElement, anchor: HTMLButtonElement) {
        val hidden = popover.classList.contains("hidden")
        if (hidden) {
            positionPopover(popover, anchor)
            popover.classList.remove("hidden")
        } else {
            popover.classList.add("hidden")
        }
    }

    private fun positionPopover(popover: HTMLDivElement, anchor: HTMLButtonElement) {
        val rect = anchor.getBoundingClientRect()
        popover.style.left = "${rect.left}px"
        popover.style.top = "${rect.bottom + 8}px"
    }

    private fun render(conflicts: Map<String, Int>) {
        val iconEl = icon ?: return
        val popoverEl = popover ?: return
        val listEl = list ?: return

        if (conflicts.isEmpty()) {
            iconEl.classList.add("hidden")
            iconEl.classList.remove("inline-flex")
            popoverEl.classList.add("hidden")
            listEl.innerHTML = ""
            return
        }

        iconEl.classList.remove("hidden")
        iconEl.classList.add("inline-flex")

        listEl.innerHTML = conflicts.entries.joinToString("") { (objectId, currentVersion) ->
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

        bindRowButtons(listEl)
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
