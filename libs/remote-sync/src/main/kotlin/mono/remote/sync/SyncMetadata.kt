/*
 * Copyright (c) 2026, PocketMS contributors
 */

package mono.remote.sync

import kotlinx.browser.localStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.w3c.dom.get
import org.w3c.dom.set

/**
 * Per-drawing sync state. [pbRecordId] is null until the drawing has been
 * pushed up for the first time. [loadedVersion] is the server-side
 * `version` field as observed at the most recent successful pull or push,
 * used as the `X-Expected-Version` on the next update.
 */
@Serializable
data class DrawingSyncInfo(
    val pbRecordId: String? = null,
    val loadedVersion: Int = 0,
)

/**
 * Sidecar storage for sync state. Lives in its own localStorage namespace
 * so the existing WorkspaceDao layer is unaware of it. Keys:
 *
 *   pms.sync.user     — the userId of the last successfully reconciled
 *                       session. A mismatch on next login triggers a wipe.
 *   pms.sync.objects  — JSON map of objectId -> DrawingSyncInfo.
 */
object SyncMetadata {
    private const val USER_KEY = "pms.sync.user"
    private const val OBJECTS_KEY = "pms.sync.objects"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val mapSerializer = MapSerializer(String.serializer(), DrawingSyncInfo.serializer())

    private var cache: MutableMap<String, DrawingSyncInfo>? = null

    var lastSyncUser: String?
        get() = localStorage[USER_KEY]
        set(value) {
            if (value == null) {
                localStorage.removeItem(USER_KEY)
            } else {
                localStorage[USER_KEY] = value
            }
        }

    fun get(objectId: String): DrawingSyncInfo? = load()[objectId]

    fun set(objectId: String, info: DrawingSyncInfo) {
        val map = load()
        map[objectId] = info
        persist(map)
    }

    fun remove(objectId: String) {
        val map = load()
        if (map.remove(objectId) != null) {
            persist(map)
        }
    }

    fun all(): Map<String, DrawingSyncInfo> = load().toMap()

    /** Wipes the per-drawing map. Does not touch [lastSyncUser]. */
    fun clearObjects() {
        cache = mutableMapOf()
        localStorage.removeItem(OBJECTS_KEY)
    }

    private fun load(): MutableMap<String, DrawingSyncInfo> {
        cache?.let { return it }
        val raw = localStorage[OBJECTS_KEY]
        val parsed = if (raw.isNullOrBlank()) {
            mutableMapOf()
        } else {
            try {
                json.decodeFromString(mapSerializer, raw).toMutableMap()
            } catch (_: Throwable) {
                mutableMapOf()
            }
        }
        cache = parsed
        return parsed
    }

    private fun persist(map: Map<String, DrawingSyncInfo>) {
        localStorage[OBJECTS_KEY] = json.encodeToString(mapSerializer, map)
    }
}
