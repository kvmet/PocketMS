/*
 * Copyright (c) 2026, PocketMS contributors
 */

package mono.remote.sync

import kotlin.js.Date
import kotlin.js.Promise
import kotlinx.browser.window
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import mono.remote.RemoteClient
import mono.remote.RemoteDrawing
import mono.remote.RemoteDrawingInput
import mono.remote.RemoteError
import mono.store.dao.workspace.WorkspaceDao
import mono.store.dao.workspace.WorkspaceObjectDao
import mono.store.manager.StorageDocument
import mono.store.manager.StoreKeys

/**
 * Reconciler between the local workspace cache and PocketBase.
 *
 * Lifecycle:
 *   1. [start] runs once after authentication. It enforces the
 *      "server defers in any ambiguity" boot rule (see SyncMetadata)
 *      and resolves only after the cache has caught up with the
 *      server.
 *   2. After [start] resolves, change/remove listeners are installed
 *      on WorkspaceObjectDao and WorkspaceDao. Every editor write
 *      triggers a debounced push to PocketBase with optimistic
 *      concurrency. Every editor delete triggers a server-side
 *      delete.
 *
 * Conflicts (HTTP 409) pause auto-push for the affected drawing and
 * record the server's current version in [pausedConflicts]. A later
 * UI commit will surface them; for now the user can still edit
 * locally but writes do not propagate until the conflict is resolved.
 */
class RemoteSyncManager(
    private val client: RemoteClient,
    private val workspaceDao: WorkspaceDao = WorkspaceDao.instance,
) {
    private val workspaceDocument: StorageDocument =
        StorageDocument.get(StoreKeys.WORKSPACE)

    private val pendingTimers = mutableMapOf<String, Int>()
    private val pushingInFlight = mutableSetOf<String>()
    private val pausedConflicts = mutableMapOf<String, Int>()

    fun start(): Promise<Unit> {
        val userId = client.currentSession?.userId
            ?: return Promise.reject(
                IllegalStateException("RemoteSyncManager requires an authenticated session")
            )

        val sameUser = SyncMetadata.lastSyncUser == userId
        if (!sameUser) {
            wipeWorkspace()
            SyncMetadata.clearObjects()
        }

        return client.listDrawingsFull().then { drawings ->
            val serverAppIds = HashSet<String>(drawings.size)
            for (drawing in drawings) {
                serverAppIds += drawing.appId
                mirrorToLocal(drawing)
                SyncMetadata.set(
                    drawing.appId,
                    DrawingSyncInfo(
                        pbRecordId = drawing.id,
                        loadedVersion = drawing.version,
                    ),
                )
            }
            serverAppIds
        }.then { serverAppIds ->
            if (sameUser) pushLocalOnly(serverAppIds) else Promise.resolve(Unit)
        }.then {
            SyncMetadata.lastSyncUser = userId
            installListeners()
            Unit
        }
    }

    // ---------- listeners ----------

    private fun installListeners() {
        WorkspaceObjectDao.changeListener = { dao -> onLocalChange(dao.objectId) }
        WorkspaceDao.removeListener = { objectId -> onLocalRemove(objectId) }
    }

    private fun onLocalChange(objectId: String) {
        if (objectId in pausedConflicts) return
        scheduleDebounce(objectId)
    }

    private fun onLocalRemove(objectId: String) {
        pendingTimers.remove(objectId)?.let { window.clearTimeout(it) }
        pausedConflicts.remove(objectId)
        val info = SyncMetadata.get(objectId)
        SyncMetadata.remove(objectId)
        val recordId = info?.pbRecordId ?: return
        client.deleteDrawing(recordId).catch { err ->
            // If the record is already gone (e.g. raced with another
            // client), that is the desired end state. Anything else we
            // surface to the console; we have no good way to retry a
            // delete that the local side has already forgotten.
            console.warn("DELETE failed for $objectId:", err)
        }
    }

    private fun scheduleDebounce(objectId: String) {
        pendingTimers[objectId]?.let { window.clearTimeout(it) }
        val firstWrite = SyncMetadata.get(objectId)?.pbRecordId == null
        val delay = if (firstWrite) FIRST_WRITE_DEBOUNCE_MS else NORMAL_DEBOUNCE_MS
        pendingTimers[objectId] = window.setTimeout({ pushNow(objectId) }, delay)
    }

    private fun pushNow(objectId: String) {
        pendingTimers.remove(objectId)
        if (objectId in pushingInFlight) {
            // A push is already running; reschedule so the new state
            // gets a chance after the current request settles.
            scheduleDebounce(objectId)
            return
        }
        if (objectId in pausedConflicts) return

        val ownerId = client.currentSession?.userId ?: return
        val input = buildInputFromLocal(objectId, ownerId) ?: return
        val info = SyncMetadata.get(objectId)
        val recordId = info?.pbRecordId

        pushingInFlight += objectId
        val push: Promise<RemoteDrawing> = if (recordId == null) {
            client.createDrawing(input)
        } else {
            client.updateDrawing(recordId, info.loadedVersion, input)
        }
        push.then { result ->
            SyncMetadata.set(
                objectId,
                DrawingSyncInfo(
                    pbRecordId = result.id,
                    loadedVersion = result.version,
                ),
            )
            Unit
        }.catch { err ->
            handlePushError(objectId, err)
        }.then {
            pushingInFlight -= objectId
            Unit
        }
    }

    private fun handlePushError(objectId: String, err: Throwable) {
        when (err) {
            is RemoteError.VersionConflict -> {
                pausedConflicts[objectId] = err.currentVersion
                console.warn(
                    "Sync paused for $objectId: server is at version ${err.currentVersion}"
                )
            }
            is RemoteError.NotFound -> {
                // Stale pbRecordId, e.g. the server-side record was
                // deleted out-of-band. Drop the id and reschedule; the
                // next push will go through the create path.
                console.warn(
                    "Record gone for $objectId; clearing local mapping and recreating"
                )
                SyncMetadata.set(
                    objectId,
                    DrawingSyncInfo(pbRecordId = null, loadedVersion = 0),
                )
                scheduleDebounce(objectId)
            }
            is RemoteError.Unauthenticated -> {
                console.warn("Auth lost; reload required to re-sync")
            }
            else -> {
                console.error("Push failed for $objectId:", err)
            }
        }
    }

    // ---------- boot wipe / mirror ----------

    private fun wipeWorkspace() {
        workspaceDao.getObjects().map { it.objectId }.toList().forEach {
            workspaceDao.removeObject(it)
        }
        workspaceDocument.remove(StoreKeys.LAST_OPEN)
    }

    private fun mirrorToLocal(drawing: RemoteDrawing) {
        val objectDocument = workspaceDocument.childDocument(drawing.appId)
        objectDocument.set(StoreKeys.OBJECT_NAME, drawing.name)
        objectDocument.set(StoreKeys.OBJECT_CONTENT, drawing.content.toString())
        objectDocument.set(StoreKeys.OBJECT_CONNECTORS, drawing.connectors.toString())
        objectDocument.set(StoreKeys.OBJECT_OFFSET, offsetToLocalString(drawing.offset))

        val updatedMs = parseIsoToMillis(drawing.updated)
        if (updatedMs != null) {
            objectDocument.set(StoreKeys.OBJECT_LAST_MODIFIED, updatedMs.toString())
            objectDocument.set(StoreKeys.OBJECT_LAST_OPENED, updatedMs.toString())
        }
    }

    private fun offsetToLocalString(offsetJson: kotlinx.serialization.json.JsonElement): String {
        val obj = offsetJson as? JsonObject ?: return "0|0"
        val left = (obj["left"] as? JsonPrimitive)?.intOrNull ?: 0
        val top = (obj["top"] as? JsonPrimitive)?.intOrNull ?: 0
        return "$left|$top"
    }

    private fun parseIsoToMillis(updated: String): Double? {
        if (updated.isBlank()) return null
        val parsed = Date.parse(updated)
        return if (parsed.isNaN()) null else parsed
    }

    // ---------- push helpers ----------

    private fun pushLocalOnly(serverAppIds: Set<String>): Promise<Unit> {
        val ownerId = client.currentSession?.userId ?: return Promise.resolve(Unit)
        val locals = workspaceDao.getObjects().toList()
        val pending = mutableListOf<Promise<*>>()
        for (local in locals) {
            if (serverAppIds.contains(local.objectId)) continue
            if (SyncMetadata.get(local.objectId)?.pbRecordId != null) continue
            val input = buildInputFromLocal(local.objectId, ownerId) ?: continue
            pending += client.createDrawing(input).then { created ->
                SyncMetadata.set(
                    local.objectId,
                    DrawingSyncInfo(
                        pbRecordId = created.id,
                        loadedVersion = created.version,
                    ),
                )
            }
        }
        if (pending.isEmpty()) return Promise.resolve(Unit)
        return Promise.all(pending.toTypedArray()).then { Unit }
    }

    private fun buildInputFromLocal(objectId: String, ownerId: String): RemoteDrawingInput? {
        val objectDocument = workspaceDocument.childDocument(objectId)
        val contentRaw = objectDocument.get(StoreKeys.OBJECT_CONTENT) ?: return null
        val connectorsRaw =
            objectDocument.get(StoreKeys.OBJECT_CONNECTORS) ?: "[]"
        val offsetRaw = objectDocument.get(StoreKeys.OBJECT_OFFSET) ?: "0|0"
        val name = objectDocument.get(StoreKeys.OBJECT_NAME) ?: "Untitled"

        val parts = offsetRaw.split("|")
        val left = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val top = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val offsetJson = json.parseToJsonElement("""{"left":$left,"top":$top}""")
        val contentJson = json.parseToJsonElement(contentRaw)
        val connectorsJson = json.parseToJsonElement(connectorsRaw)

        return RemoteDrawingInput(
            appId = objectId,
            owner = ownerId,
            name = name,
            folderPath = "",
            content = contentJson,
            connectors = connectorsJson,
            offset = offsetJson,
        )
    }

    companion object {
        private const val NORMAL_DEBOUNCE_MS = 2000
        private const val FIRST_WRITE_DEBOUNCE_MS = 500

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
