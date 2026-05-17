/*
 * Copyright (c) 2026, PocketMS contributors
 */

package mono.remote.sync

import kotlin.js.Date
import kotlin.js.Promise
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import mono.remote.RemoteClient
import mono.remote.RemoteDrawing
import mono.remote.RemoteDrawingInput
import mono.store.dao.workspace.WorkspaceDao
import mono.store.manager.StorageDocument
import mono.store.manager.StoreKeys

/**
 * Boot-time reconciler between the local workspace cache and PocketBase.
 *
 * Run once after authentication succeeds and before the editor starts.
 * On completion the local cache reflects whatever the server holds for
 * the authenticated user, and per-drawing sync state ({pbRecordId,
 * loadedVersion}) is recorded so subsequent writes can use optimistic
 * concurrency.
 *
 * The reconciliation rule is "server defers in any ambiguity":
 *   - If the last reconciled user matches the current user, local is
 *     trusted; drawings without a pbRecordId are pushed up.
 *   - Otherwise local is wiped and rebuilt from the server.
 */
class RemoteSyncManager(
    private val client: RemoteClient,
    private val workspaceDao: WorkspaceDao = WorkspaceDao.instance,
) {
    private val workspaceDocument: StorageDocument =
        StorageDocument.get(StoreKeys.WORKSPACE)

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
            if (sameUser) {
                pushLocalOnly(serverAppIds)
            } else {
                Promise.resolve(Unit)
            }
        }.then {
            SyncMetadata.lastSyncUser = userId
            Unit
        }
    }

    // ---------- wipe / mirror ----------

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
        // The DAO persists offset as "left|top". The server payload is
        // { "left": <int>, "top": <int> }. Fall back to "0|0" if the
        // server stored something unexpected (e.g. an empty new drawing).
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

    // ---------- push local-only drawings ----------

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
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
