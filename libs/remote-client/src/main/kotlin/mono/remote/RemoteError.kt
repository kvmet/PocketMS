/*
 * Copyright (c) 2026, PocketMS contributors
 */

package mono.remote

/**
 * Errors thrown (as Promise rejections) by [RemoteClient]. Callers should
 * branch on the sealed subtype rather than inspecting messages.
 */
sealed class RemoteError(message: String, cause: Throwable? = null) : Throwable(message, cause) {
    /** No token, expired token, or server rejected the token. */
    object Unauthenticated : RemoteError("unauthenticated")

    /**
     * Server detected a concurrent edit. [currentVersion] is the version
     * stored on the server at the moment of the conflict.
     */
    data class VersionConflict(val currentVersion: Int) :
        RemoteError("version_conflict (server is at $currentVersion)")

    /**
     * The targeted record does not exist server-side. Used by the sync
     * layer to recover from a stale pbRecordId by re-creating the
     * record from local state.
     */
    data class NotFound(val body: String) : RemoteError("not_found: $body")

    /** Network failure, server unreachable, CORS, etc. */
    class Network(cause: Throwable) :
        RemoteError("network: ${cause.message ?: cause::class.simpleName}", cause)

    /** Any other non-2xx response. */
    data class Other(val status: Int, val body: String) :
        RemoteError("http $status: $body")
}
