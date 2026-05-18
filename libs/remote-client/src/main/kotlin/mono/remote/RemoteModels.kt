/*
 * Copyright (c) 2026, PocketMS contributors
 */

package mono.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * The session returned by a successful login. [token] is what subsequent
 * requests pass in the Authorization header; [userId] is the PocketBase
 * record id of the authenticated user.
 */
@Serializable
data class AuthSession(
    val token: String,
    val userId: String,
    val email: String,
)

/**
 * The shape sent to the server when creating or updating a drawing. JSON
 * fields are passed as raw [JsonElement] so we never re-parse the output
 * of MonoSketch's existing shape serializer.
 *
 * Note: this intentionally has no `version` field. PocketBase merges
 * the request body into the record before hooks fire, so sending
 * version=0 on update would clobber the DB's current version inside
 * the hook's view of e.record and break optimistic concurrency. The
 * server-side hook is the sole writer for that field (forces 0 on
 * create, current+1 on update).
 */
@Serializable
data class RemoteDrawingInput(
    @SerialName("app_id") val appId: String,
    val owner: String,
    val name: String,
    @SerialName("folder_path") val folderPath: String = "",
    val content: JsonElement,
    val connectors: JsonElement,
    val offset: JsonElement,
)

/** A drawing record as returned by PocketBase. */
@Serializable
data class RemoteDrawing(
    val id: String,
    @SerialName("app_id") val appId: String,
    val owner: String,
    val name: String,
    @SerialName("folder_path") val folderPath: String = "",
    val content: JsonElement,
    val connectors: JsonElement,
    val offset: JsonElement,
    val version: Int,
    val updated: String,
)

/** Lightweight summary used for listing without pulling content blobs. */
@Serializable
data class RemoteDrawingSummary(
    val id: String,
    @SerialName("app_id") val appId: String,
    val name: String,
    @SerialName("folder_path") val folderPath: String = "",
    val version: Int,
    val updated: String,
)

@Serializable
internal data class PbListResponse<T>(
    val page: Int,
    val perPage: Int,
    val totalItems: Int,
    val totalPages: Int,
    val items: List<T>,
)

@Serializable
internal data class PbAuthResponse(
    val token: String,
    val record: PbAuthRecord,
)

@Serializable
internal data class PbAuthRecord(
    val id: String,
    val email: String,
)
