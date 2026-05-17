/*
 * Copyright (c) 2026, PocketMS contributors
 */

package mono.remote

import kotlin.js.Promise
import kotlinx.browser.localStorage
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.w3c.dom.get
import org.w3c.dom.set
import org.w3c.fetch.RequestInit
import org.w3c.fetch.Response

/**
 * Thin Kotlin/JS wrapper around the PocketBase HTTP API. Stateless apart
 * from the cached auth token; the caller owns lifecycle.
 *
 * All public methods return [Promise]s that reject with [RemoteError]
 * subtypes. The matching project style is callbacks/Promises rather than
 * coroutines.
 */
class RemoteClient(private val baseUrl: String = "") {

    private var session: AuthSession? = loadSession()

    val isAuthed: Boolean get() = session != null

    val currentSession: AuthSession? get() = session

    // ---------- auth ----------

    fun login(email: String, password: String): Promise<AuthSession> {
        val body = json.encodeToString(
            JsonObject.serializer(),
            JsonObject(
                mapOf(
                    "identity" to jsonPrimitiveOf(email),
                    "password" to jsonPrimitiveOf(password),
                )
            )
        )
        return request(
            method = "POST",
            path = "/api/collections/users/auth-with-password",
            body = body,
            auth = false,
        ).then { text ->
            val resp = json.decodeFromString(PbAuthResponse.serializer(), text)
            val s = AuthSession(token = resp.token, userId = resp.record.id, email = resp.record.email)
            session = s
            persistSession(s)
            s
        }
    }

    fun logout() {
        session = null
        localStorage.removeItem(SESSION_KEY)
    }

    // ---------- drawings ----------

    fun listDrawings(): Promise<List<RemoteDrawingSummary>> {
        // Use ?fields to skip the heavy content/connectors/offset blobs.
        val fields = "id,app_id,name,folder_path,version,updated"
        return request(
            method = "GET",
            path = "/api/collections/drawings/records?perPage=500&fields=$fields&sort=-updated",
        ).then { text ->
            val resp = json.decodeFromString(
                PbListResponse.serializer(RemoteDrawingSummary.serializer()),
                text,
            )
            resp.items
        }
    }

    /**
     * Same as [listDrawings] but returns full drawings including content,
     * connectors, and offset. Used by boot-time reconciliation in
     * RemoteSyncManager.
     */
    fun listDrawingsFull(): Promise<List<RemoteDrawing>> =
        request(
            method = "GET",
            path = "/api/collections/drawings/records?perPage=500&sort=-updated",
        ).then { text ->
            val resp = json.decodeFromString(
                PbListResponse.serializer(RemoteDrawing.serializer()),
                text,
            )
            resp.items
        }

    fun getDrawing(appId: String): Promise<RemoteDrawing> {
        val filter = encode("app_id=\"$appId\"")
        return request(
            method = "GET",
            path = "/api/collections/drawings/records?perPage=1&filter=$filter",
        ).then { text ->
            val resp = json.decodeFromString(
                PbListResponse.serializer(RemoteDrawing.serializer()),
                text,
            )
            resp.items.firstOrNull()
                ?: throw RemoteError.Other(404, "no drawing with app_id=$appId")
        }
    }

    fun createDrawing(payload: RemoteDrawingInput): Promise<RemoteDrawing> {
        val body = json.encodeToString(RemoteDrawingInput.serializer(), payload)
        return request(
            method = "POST",
            path = "/api/collections/drawings/records",
            body = body,
        ).then { text -> json.decodeFromString(RemoteDrawing.serializer(), text) }
    }

    fun updateDrawing(
        recordId: String,
        expectedVersion: Int,
        payload: RemoteDrawingInput,
    ): Promise<RemoteDrawing> {
        val body = json.encodeToString(RemoteDrawingInput.serializer(), payload)
        return request(
            method = "PATCH",
            path = "/api/collections/drawings/records/$recordId",
            body = body,
            extraHeaders = mapOf("X-Expected-Version" to expectedVersion.toString()),
        ).then { text -> json.decodeFromString(RemoteDrawing.serializer(), text) }
    }

    fun deleteDrawing(recordId: String): Promise<Unit> =
        request(
            method = "DELETE",
            path = "/api/collections/drawings/records/$recordId",
        ).then { }

    // ---------- internals ----------

    private fun request(
        method: String,
        path: String,
        body: String? = null,
        auth: Boolean = true,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Promise<String> {
        val headers = js("({})")
        headers["Accept"] = "application/json"
        if (body != null) headers["Content-Type"] = "application/json"
        if (auth) {
            val token = session?.token ?: return Promise.reject(RemoteError.Unauthenticated)
            headers["Authorization"] = token
        }
        for ((k, v) in extraHeaders) headers[k] = v

        val init = js("({})").unsafeCast<RequestInit>()
        init.asDynamic().method = method
        init.asDynamic().headers = headers
        if (body != null) init.asDynamic().body = body

        return kotlinx.browser.window.fetch("$baseUrl$path", init)
            .then { response: Response ->
                response.text().then inner@{ text ->
                    if (response.ok) return@inner text
                    handleErrorResponse(response.status.toInt(), text)
                }
            }
            .then { it.unsafeCast<String>() }
            .catch { err ->
                if (err is RemoteError) throw err
                throw RemoteError.Network(err)
            }
    }

    private fun handleErrorResponse(status: Int, body: String): String {
        when (status) {
            401, 403 -> {
                logout()
                throw RemoteError.Unauthenticated
            }
            409 -> {
                val current = parseConflictVersion(body)
                throw RemoteError.VersionConflict(currentVersion = current ?: -1)
            }
            else -> throw RemoteError.Other(status, body)
        }
    }

    private fun parseConflictVersion(body: String): Int? {
        return try {
            val obj = json.parseToJsonElement(body) as? JsonObject ?: return null
            // PocketBase wraps custom hook errors as { "code": ..., "data": {...} }.
            val data = obj["data"] as? JsonObject
            (data?.get("current_version") as? JsonElement)?.jsonPrimitive?.intOrNull
        } catch (_: Throwable) {
            null
        }
    }

    private fun loadSession(): AuthSession? {
        val raw = localStorage[SESSION_KEY] ?: return null
        return try {
            json.decodeFromString(AuthSession.serializer(), raw)
        } catch (_: Throwable) {
            null
        }
    }

    private fun persistSession(s: AuthSession) {
        localStorage[SESSION_KEY] = json.encodeToString(AuthSession.serializer(), s)
    }

    private fun jsonPrimitiveOf(value: String): JsonElement =
        kotlinx.serialization.json.JsonPrimitive(value)

    private fun encode(s: String): String = js("encodeURIComponent")(s).unsafeCast<String>()

    companion object {
        private const val SESSION_KEY = "pms.auth"

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
