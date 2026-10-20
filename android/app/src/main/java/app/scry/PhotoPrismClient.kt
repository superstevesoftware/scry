package app.scry

import android.util.Log
import core.Core
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The slice of PhotoPrism's API that backup needs, spoken through the core's worker listener
 * (so every request goes through the tunnel and the app never holds a socket to the server).
 *
 * Uploading is two steps: POST the files into a staging batch, then PUT to import that batch.
 * Both use the same random batch token. Authentication is the user's app password sent as a
 * Bearer token. This class never logs credentials or request bodies.
 */
class PhotoPrismClient(private val appPassword: String) {
    /** An HTTP-level failure; [code] is the status, so callers can tell "stop" from "retry". */
    class ApiException(val code: Int, message: String) : IOException(message)

    private val base = "http://${Core.workerEndpoint()}"

    private fun open(method: String, path: String): HttpURLConnection {
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.setRequestProperty("X-Scry-Token", Core.workerToken())
        c.setRequestProperty("Authorization", "Bearer $appPassword")
        c.connectTimeout = 15_000
        c.readTimeout = 10 * 60_000 // the server may spend a while importing a large batch
        c.useCaches = false
        return c
    }

    private fun check(c: HttpURLConnection): String {
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        if (code in 200..299) return body
        throw ApiException(code, describe(code, body))
    }

    private fun describe(code: Int, body: String): String {
        val detail = runCatching { JSONObject(body).optString("error").ifEmpty { JSONObject(body).optString("message") } }
            .getOrNull().orEmpty()
        val why = when (code) {
            401 -> "The app password was not accepted."
            403 -> "Not allowed: the app password may lack upload permission, or uploads are turned off on the server."
            404 -> "Not found: the upload batch or user was not recognised."
            413 -> "The server refused the file as too large."
            503 -> "The server is busy importing; try again shortly."
            507 -> "The server is out of storage space."
            else -> "The server answered HTTP $code."
        }
        return if (detail.isNotBlank()) "$why ($detail)" else why
    }

    /** Looks up the user this app password belongs to; uploads are addressed to that user. */
    fun userUid(): String {
        val c = open("GET", "/api/v1/session")
        try {
            val json = JSONObject(check(c))
            val user = json.optJSONObject("user") ?: json.optJSONObject("User")
            val uid = listOfNotNull(user?.optString("UID"), user?.optString("uid"), json.optString("uid"), json.optString("UID"))
                .firstOrNull { it.isNotBlank() }
            Log.i(TAG, "session lookup: keys=${json.keys().asSequence().toList()} userKeys=${user?.keys()?.asSequence()?.toList()}")
            return uid ?: throw IOException("The server did not say which user this app password belongs to.")
        } finally {
            c.disconnect()
        }
    }

    /** Sends the files of one batch. Returns how many files the server says it accepted, if it says. */
    fun upload(uid: String, batch: String, body: MultipartBody, onBytes: (Long) -> Unit): Int? {
        val c = open("POST", "/api/v1/users/$uid/upload/$batch")
        try {
            c.doOutput = true
            c.setFixedLengthStreamingMode(body.contentLength)
            c.setRequestProperty("Content-Type", body.contentType)
            c.outputStream.use { body.writeTo(it, onBytes) }
            val reply = check(c)
            // The message reads like "Uploaded 3 files in 1.2 s"; the number is only a sanity check.
            val n = runCatching { Regex("""(\d+)""").find(JSONObject(reply).optString("message"))?.value?.toInt() }.getOrNull()
            Log.i(TAG, "upload batch ok, server accepted=${n ?: "?"}")
            return n
        } finally {
            c.disconnect()
        }
    }

    /** Asks the server to import a staged batch into the library. */
    fun process(uid: String, batch: String) {
        val c = open("PUT", "/api/v1/users/$uid/upload/$batch")
        try {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write("""{"albums":[]}""".toByteArray()) }
            check(c)
        } finally {
            c.disconnect()
        }
    }

    companion object {
        private const val TAG = "Scry"
    }
}
