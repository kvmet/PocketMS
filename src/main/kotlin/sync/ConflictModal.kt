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
import org.w3c.dom.events.Event

/**
 * Full-screen blocking modal shown whenever the RemoteSyncManager has
 * one or more paused-conflict drawings. The user cannot dismiss the
 * modal; they must resolve every conflict by either pulling the
 * server's copy or saving their local copy as a brand-new drawing.
 *
 * "Use server" pulls the server's version, overwrites local, and
 * reloads the page so the editor's in-memory state resyncs.
 *
 * "Save mine as new" forks the conflicting drawing under a fresh app
 * id with " (mine)" appended to the name, reverts the original to the
 * server's state, and navigates the editor to the new copy via
 * ?id=<newId>. The new copy then debounces up to PocketBase as its
 * own record.
 */
class ConflictModal(private val manager: RemoteSyncManager) {

    private var overlay: HTMLDivElement? = null
    private var list: HTMLDivElement? = null

    fun mount() {
        if (document.getElementById("sync-conflict-modal") != null) return

        val el = document.createElement("div") as HTMLDivElement
        el.id = "sync-conflict-modal"
        el.className =
            "hidden fixed inset-0 flex items-center justify-center bg-zinc-950/80"
        el.style.zIndex = "10001"
        el.innerHTML = """
            <div class="max-w-lg w-full p-6 rounded-md shadow-lg bg-zinc-800 border border-zinc-700">
              <h1 class="text-lg font-semibold text-zinc-100 mb-2">Sync conflicts</h1>
              <p class="text-sm text-zinc-300 mb-4">
                The server has a newer version of the following drawing(s).
                Resolve each before continuing to edit.
              </p>
              <div id="sync-conflict-modal-list" class="text-sm"></div>
            </div>
        """.trimIndent()
        document.body?.appendChild(el)
        overlay = el
        list = document.getElementById("sync-conflict-modal-list") as? HTMLDivElement

        render(manager.activeConflicts)
        manager.conflictListener = { conflicts -> render(conflicts) }
    }

    private fun render(conflicts: Map<String, Int>) {
        val overlayEl = overlay ?: return
        val listEl = list ?: return

        if (conflicts.isEmpty()) {
            overlayEl.classList.add("hidden")
            listEl.innerHTML = ""
            return
        }

        listEl.innerHTML = conflicts.entries.joinToString("") { (objectId, currentVersion) ->
            val safeName = escapeHtml(safeName(objectId))
            """
            <div data-object-id="$objectId" class="mb-3 pb-3 border-b border-zinc-700 last:border-b-0 last:mb-0 last:pb-0">
              <div class="text-zinc-100 font-medium mb-1 truncate" title="$safeName">$safeName</div>
              <div class="text-xs text-zinc-400 mb-3">Server is at version $currentVersion.</div>
              <div class="flex gap-2">
                <button data-action="server" type="button"
                        class="px-3 py-1.5 rounded bg-blue-600 text-white text-xs hover:bg-blue-500">Use server</button>
                <button data-action="fork" type="button"
                        class="px-3 py-1.5 rounded border border-zinc-600 text-zinc-200 text-xs hover:bg-zinc-700">Save mine as new</button>
              </div>
            </div>
            """.trimIndent()
        }

        bindRowButtons(listEl)
        overlayEl.classList.remove("hidden")
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
                        disableRow(row)
                        manager.reloadFromServer(id)
                            .then { window.location.reload() }
                            .catch { err ->
                                enableRow(row)
                                console.error("Reload from server failed:", err)
                            }
                    }
                    "fork" -> {
                        disableRow(row)
                        manager.saveAsNewAndRevert(id)
                            .then { newId ->
                                window.location.href = "?id=$newId"
                            }
                            .catch { err ->
                                enableRow(row)
                                console.error("Save mine as new failed:", err)
                            }
                    }
                }
            })
        }
    }

    private fun disableRow(row: HTMLElement) {
        val nodes = row.querySelectorAll("button")
        for (i in 0 until nodes.length) {
            (nodes.item(i) as? HTMLButtonElement)?.disabled = true
        }
    }

    private fun enableRow(row: HTMLElement) {
        val nodes = row.querySelectorAll("button")
        for (i in 0 until nodes.length) {
            (nodes.item(i) as? HTMLButtonElement)?.disabled = false
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
