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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import mono.common.currentTimeMillis
import mono.remote.RemoteClient
import mono.remote.RemoteDrawing
import mono.remote.RemoteDrawingInput
import mono.remote.RemoteError
import mono.store.dao.workspace.WorkspaceDao
import mono.store.dao.workspace.WorkspaceObjectDao
import mono.store.manager.StorageDocument
import mono.store.manager.StoreKeys
import mono.uuid.UUID

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
    private val offlinePushIds = mutableSetOf<String>()

    /**
     * Notified whenever the set of paused (conflicting) drawings
     * changes. The map is objectId -> server-side current version at
     * the moment of the conflict.
     */
    var conflictListener: ((Map<String, Int>) -> Unit)? = null

    /**
     * Notified whenever the coarse sync status changes (Synced /
     * Pending / Offline). Conflicts are reported via [conflictListener]
     * and are not part of this signal.
     */
    var statusListener: ((SyncStatus) -> Unit)? = null

    val activeConflicts: Map<String, Int> get() = pausedConflicts.toMap()

    val syncStatus: SyncStatus
        get() = when {
            offlinePushIds.isNotEmpty() -> SyncStatus.Offline
            pendingTimers.isNotEmpty() || pushingInFlight.isNotEmpty() -> SyncStatus.Pending
            else -> SyncStatus.Synced
        }

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
                // In same-user mode, do not stomp local content with the
                // server's copy. The user may have unsynced edits that
                // have not yet debounced through. Their loadedVersion in
                // metadata is preserved so the next push either succeeds
                // (server unchanged) or 409s (server diverged), which is
                // caught by the conflict banner.
                val localIsTrusted = sameUser &&
                    localHasContent(drawing.appId) &&
                    SyncMetadata.get(drawing.appId)?.pbRecordId != null
                if (!localIsTrusted) {
                    mirrorToLocal(drawing)
                    SyncMetadata.set(
                        drawing.appId,
                        DrawingSyncInfo(
                            pbRecordId = drawing.id,
                            loadedVersion = drawing.version,
                        ),
                    )
                }
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
        if (pausedConflicts.remove(objectId) != null) notifyConflictListener()
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
        notifyStatusListener()
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
            offlinePushIds.remove(objectId)
            Unit
        }.catch { err ->
            if (err is RemoteError.Network) {
                offlinePushIds.add(objectId)
            } else {
                offlinePushIds.remove(objectId)
            }
            handlePushError(objectId, err)
        }.then {
            pushingInFlight -= objectId
            notifyStatusListener()
            Unit
        }
    }

    private fun handlePushError(objectId: String, err: Throwable) {
        when (err) {
            is RemoteError.VersionConflict -> handleConflict(objectId, err.currentVersion)
            is RemoteError.NotFound -> recoverStaleRecord(objectId)
            is RemoteError.Unauthenticated -> {
                console.warn("Auth lost; reload required to re-sync")
            }
            else -> {
                console.error("Push failed for $objectId:", err)
            }
        }
    }

    /**
     * A 409 can mean either the genuine "server is ahead of us" case or
     * the phantom case where the server's record is gone (the hook
     * still fires for missing records with an empty record, reporting
     * version 0). When current < expected, the server is "behind" us,
     * which can only happen if the underlying record was deleted or our
     * local mapping is stale. Refetch by app_id to find truth.
     */
    private fun handleConflict(objectId: String, currentVersion: Int) {
        val expected = SyncMetadata.get(objectId)?.loadedVersion ?: 0
        if (currentVersion < expected) {
            recoverStaleRecord(objectId)
        } else {
            pausedConflicts[objectId] = currentVersion
            notifyConflictListener()
            console.warn(
                "Sync paused for $objectId: server is at version $currentVersion"
            )
        }
    }

    /**
     * User picked "use server" in the conflict banner. Pull the server
     * copy, write it into local storage via StorageDocument (no
     * listener firing), update metadata, clear the conflict. Caller is
     * expected to reload the page so the in-memory editor state
     * resyncs from the now-updated cache.
     */
    fun reloadFromServer(objectId: String): Promise<Unit> =
        client.getDrawing(objectId).then { drawing ->
            mirrorToLocal(drawing)
            SyncMetadata.set(
                objectId,
                DrawingSyncInfo(
                    pbRecordId = drawing.id,
                    loadedVersion = drawing.version,
                ),
            )
            pausedConflicts.remove(objectId)
            notifyConflictListener()
            Unit
        }

    /**
     * User picked "keep mine" in the conflict banner. Adopt the
     * server's current version as our loadedVersion so the next push's
     * X-Expected-Version matches, then schedule a push of the current
     * local state.
     */
    fun overwriteServer(objectId: String) {
        val current = pausedConflicts.remove(objectId) ?: return
        val info = SyncMetadata.get(objectId) ?: return
        SyncMetadata.set(objectId, info.copy(loadedVersion = current))
        notifyConflictListener()
        scheduleDebounce(objectId)
    }

    /**
     * User picked "save mine as new" in the conflict modal. Copies the
     * conflicting drawing's current local state to a fresh app id with
     * a " (mine)" name suffix and reverts the original drawing's local
     * state to whatever is on the server. The new drawing is then
     * debounced for an initial create push.
     *
     * Returns the new app id; callers typically reload the page with
     * ?id=<newId> so the editor opens the new copy cleanly.
     */
    fun saveAsNewAndRevert(objectId: String): Promise<String> {
        val newAppId = UUID.generate()
        copyLocalToNewAppId(oldId = objectId, newId = newAppId)
        SyncMetadata.set(
            newAppId,
            DrawingSyncInfo(pbRecordId = null, loadedVersion = 0),
        )
        return reloadFromServer(objectId).then {
            scheduleDebounce(newAppId)
            newAppId
        }
    }

    private fun copyLocalToNewAppId(oldId: String, newId: String) {
        val oldDoc = workspaceDocument.childDocument(oldId)
        val newDoc = workspaceDocument.childDocument(newId)

        val name = oldDoc.get(StoreKeys.OBJECT_NAME) ?: "Untitled"
        newDoc.set(StoreKeys.OBJECT_NAME, "$name (mine)")

        val contentRaw = oldDoc.get(StoreKeys.OBJECT_CONTENT)
        if (contentRaw != null) {
            newDoc.set(StoreKeys.OBJECT_CONTENT, rewriteContentId(contentRaw, newId))
        }
        oldDoc.get(StoreKeys.OBJECT_CONNECTORS)?.let {
            newDoc.set(StoreKeys.OBJECT_CONNECTORS, it)
        }
        oldDoc.get(StoreKeys.OBJECT_OFFSET)?.let {
            newDoc.set(StoreKeys.OBJECT_OFFSET, it)
        }
        val now = currentTimeMillis().toString()
        newDoc.set(StoreKeys.OBJECT_LAST_MODIFIED, now)
        newDoc.set(StoreKeys.OBJECT_LAST_OPENED, now)
    }

    /**
     * The serialized content blob embeds the root group's id under the
     * "i" key. When duplicating a drawing under a fresh app id we
     * rewrite this so the deserialized root's id matches the storage
     * key it lives under; otherwise the editor's in-memory root would
     * still report the old id and subsequent DAO writes would go to
     * the wrong drawing.
     */
    private fun rewriteContentId(contentRaw: String, newId: String): String {
        return try {
            val element = json.parseToJsonElement(contentRaw) as? JsonObject
                ?: return contentRaw
            val rebuilt = buildJsonObject {
                for ((key, value) in element) {
                    if (key != "i") put(key, value)
                }
                put("i", JsonPrimitive(newId))
            }
            json.encodeToString(JsonObject.serializer(), rebuilt)
        } catch (_: Throwable) {
            contentRaw
        }
    }

    private fun notifyConflictListener() {
        conflictListener?.invoke(pausedConflicts.toMap())
    }

    private fun notifyStatusListener() {
        statusListener?.invoke(syncStatus)
    }

    private fun recoverStaleRecord(objectId: String) {
        console.warn("Recovering stale mapping for $objectId; refetching by app_id")
        client.getDrawing(objectId).then { found ->
            SyncMetadata.set(
                objectId,
                DrawingSyncInfo(
                    pbRecordId = found.id,
                    loadedVersion = found.version,
                ),
            )
            scheduleDebounce(objectId)
            Unit
        }.catch { err ->
            if (err is RemoteError.NotFound) {
                SyncMetadata.set(
                    objectId,
                    DrawingSyncInfo(pbRecordId = null, loadedVersion = 0),
                )
                scheduleDebounce(objectId)
            } else {
                console.error("Recovery failed for $objectId:", err)
            }
            Unit
        }
    }

    // ---------- boot wipe / mirror ----------

    private fun wipeWorkspace() {
        workspaceDao.getObjects().map { it.objectId }.toList().forEach {
            workspaceDao.removeObject(it)
        }
        workspaceDocument.remove(StoreKeys.LAST_OPEN)
    }

    private fun localHasContent(appId: String): Boolean {
        val objectDocument = workspaceDocument.childDocument(appId)
        return objectDocument.get(StoreKeys.OBJECT_CONTENT) != null
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
