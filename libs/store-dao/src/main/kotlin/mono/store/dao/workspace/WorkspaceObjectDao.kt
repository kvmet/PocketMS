/*
 * Copyright (c) 2023, tuanchauict
 */

package mono.store.dao.workspace

import mono.common.currentTimeMillis
import mono.graphics.geo.Point
import mono.shape.serialization.SerializableGroup
import mono.shape.serialization.SerializableLineConnector
import mono.shape.serialization.ShapeSerializationUtil
import mono.store.manager.StorageDocument
import mono.store.manager.StoreKeys.OBJECT_CONNECTORS
import mono.store.manager.StoreKeys.OBJECT_CONTENT
import mono.store.manager.StoreKeys.OBJECT_LAST_MODIFIED
import mono.store.manager.StoreKeys.OBJECT_LAST_OPENED
import mono.store.manager.StoreKeys.OBJECT_NAME
import mono.store.manager.StoreKeys.OBJECT_OFFSET

/**
 * A dao for an object (aka project or file) in the workspace.
 */
class WorkspaceObjectDao internal constructor(
    val objectId: String,
    workspaceDocument: StorageDocument
) {

    private val objectDocument: StorageDocument = workspaceDocument.childDocument(objectId)

    var offset: Point
        get() {
            val offsetString = objectDocument.get(OBJECT_OFFSET)
            val (leftString, topString) = offsetString?.split('|')?.takeIf { it.size == 2 }
                ?: return Point.ZERO
            val left = leftString.toIntOrNull() ?: return Point.ZERO
            val top = topString.toIntOrNull() ?: return Point.ZERO
            return Point(left, top)
        }
        set(value) {
            objectDocument.set(OBJECT_OFFSET, "${value.left}|${value.top}")
            notifyChange()
        }

    var rootGroup: SerializableGroup?
        get() {
            val json = objectDocument.get(OBJECT_CONTENT) ?: return null
            return ShapeSerializationUtil.fromShapeJson(json) as? SerializableGroup
        }
        set(value) {
            if (value != null) {
                val json = ShapeSerializationUtil.toShapeJson(value)
                objectDocument.set(OBJECT_CONTENT, json)
            }
            lastModifiedTimestampMillis = currentTimeMillis()
            notifyChange()
        }

    var connectors: List<SerializableLineConnector>
        get() {
            val json = objectDocument.get(OBJECT_CONNECTORS) ?: return emptyList()
            return ShapeSerializationUtil.fromConnectorsJson(json)
        }
        set(value) {
            val json = ShapeSerializationUtil.toConnectorsJson(value)
            objectDocument.set(OBJECT_CONNECTORS, json)
            notifyChange()
        }

    var name: String
        get() = objectDocument.get(OBJECT_NAME) ?: DEFAULT_NAME
        set(value) {
            objectDocument.set(OBJECT_NAME, value)
            lastModifiedTimestampMillis = currentTimeMillis()
            notifyChange()
        }

    var lastModifiedTimestampMillis: Double
        get() {
            val lastModifiedString = objectDocument.get(OBJECT_LAST_MODIFIED)
            return lastModifiedString?.toDoubleOrNull() ?: currentTimeMillis()
        }
        private set(value) = objectDocument.set(OBJECT_LAST_MODIFIED, value.toString())

    var lastOpened: Double
        get() {
            val lastOpenedString = objectDocument.get(OBJECT_LAST_OPENED)
            return lastOpenedString?.toDoubleOrNull() ?: currentTimeMillis()
        }
        private set(value) = objectDocument.set(OBJECT_LAST_OPENED, value.toString())

    fun updateLastOpened() {
        lastOpened = currentTimeMillis()
    }

    fun removeSelf() {
        with(objectDocument) {
            remove(OBJECT_OFFSET)
            remove(OBJECT_CONTENT)
            remove(OBJECT_CONNECTORS)
            remove(OBJECT_NAME)
            remove(OBJECT_LAST_MODIFIED)
        }
    }

    private fun notifyChange() {
        changeListener?.invoke(this)
    }

    companion object {
        const val DEFAULT_NAME = "Undefined"

        /**
         * Hook for outside-the-DAO observers (e.g. RemoteSyncManager) to
         * react to writes. Fires after each setter has finished writing to
         * storage. Kept here rather than per-instance because object DAOs
         * are lazily created and the observer is a singleton.
         */
        var changeListener: ((WorkspaceObjectDao) -> Unit)? = null
    }
}
