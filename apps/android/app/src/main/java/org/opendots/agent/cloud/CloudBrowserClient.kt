package org.opendots.agent.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class CloudBrowserConfig(
    val baseUrl: String = "",
    val botId: String = "bot-open-dots-1"
)

data class CloudFrame(
    val available: Boolean,
    val frameId: String?,
    val format: String,
    val width: Int,
    val height: Int,
    val url: String?,
    val dataBase64: String?,
    val message: String?
)

data class CloudActionResponse(
    val completed: Boolean,
    val pendingApproval: Boolean,
    val requestId: String? = null,
    val message: String,
    val payload: JSONObject? = null
)

class CloudBrowserClient {
    suspend fun status(config: CloudBrowserConfig, token: String): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            runCatching { request("GET", config, token, "/api/v1/computers/${config.botId}", null).second }
        }

    suspend fun ensureRunning(config: CloudBrowserConfig, token: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(config.baseUrl.startsWith("http://") || config.baseUrl.startsWith("https://")) {
                    "Cloud browser server must be an HTTP(S) URL."
                }
                val statusResponse = request(
                    "GET", config, token, "/api/v1/computers/${config.botId}", null
                ).second
                val created = statusResponse.optBoolean("created", false)
                val state = statusResponse.optJSONObject("status")?.optString("state").orEmpty()
                if (!created) {
                    request("POST", config, token, "/api/v1/computers/${config.botId}/create", JSONObject())
                }
                if (state != "running") {
                    request("POST", config, token, "/api/v1/computers/${config.botId}/start", JSONObject())
                }
            }
        }

    suspend fun screenshot(
        config: CloudBrowserConfig,
        token: String
    ): Result<CloudFrame> = withContext(Dispatchers.IO) {
        runCatching {
            val (_, json) = request(
                "GET",
                config,
                token,
                "/api/v1/computers/${config.botId}/screenshot",
                null
            )
            val result = json.optJSONObject("result") ?: json
            CloudFrame(
                available = result.optBoolean("available", false),
                frameId = result.optString("frame_id").takeIf { it.isNotBlank() && it != "null" },
                format = result.optString("format", "jpeg"),
                width = result.optInt("width", 0),
                height = result.optInt("height", 0),
                url = result.optString("url").takeIf { it.isNotBlank() },
                dataBase64 = result.optString("data").takeIf { it.isNotBlank() && it != "null" },
                message = result.optString("message").takeIf { it.isNotBlank() }
            )
        }
    }

    suspend fun navigate(
        config: CloudBrowserConfig,
        token: String,
        url: String
    ): Result<CloudActionResponse> = openAction(
        config = config,
        token = token,
        action = "browser_navigate",
        arguments = JSONObject().put("url", url),
        pendingMessage = "Cloud browser navigation needs approval.",
        completedMessage = "Cloud browser navigation completed."
    )

    suspend fun sendInput(
        config: CloudBrowserConfig,
        token: String,
        event: JSONObject
    ): Result<CloudActionResponse> = openAction(
        config = config,
        token = token,
        action = "send_input",
        arguments = JSONObject().put("event", event),
        pendingMessage = "Cloud browser input needs approval.",
        completedMessage = "Cloud browser input completed."
    )

    private suspend fun openAction(
        config: CloudBrowserConfig,
        token: String,
        action: String,
        arguments: JSONObject,
        pendingMessage: String,
        completedMessage: String
    ): Result<CloudActionResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject()
                .put("action", action)
                .put("arguments", arguments)
            val (code, json) = request(
                "POST", config, token, "/api/v1/computers/${config.botId}/actions", body
            )
            if (code == 202) {
                val requestObject = json.optJSONObject("request")
                val requestId = requestObject?.optString("request_id")
                    ?.takeIf { it.isNotBlank() }
                    ?: requestObject?.optString("id")?.takeIf { it.isNotBlank() }
                    ?: json.optJSONObject("approval")?.optString("request_id")
                CloudActionResponse(
                    completed = false,
                    pendingApproval = true,
                    requestId = requestId,
                    message = pendingMessage,
                    payload = json
                )
            } else {
                CloudActionResponse(
                    completed = true,
                    pendingApproval = false,
                    message = completedMessage,
                    payload = json
                )
            }
        }
    }

    suspend fun resolveAndExecute(
        config: CloudBrowserConfig,
        token: String,
        requestId: String,
        allow: Boolean
    ): Result<CloudActionResponse> = withContext(Dispatchers.IO) {
        runCatching {
            request(
                "POST",
                config,
                token,
                "/api/v1/approvals/respond",
                JSONObject().put("request_id", requestId).put("action", if (allow) "allow" else "deny")
            )
            if (!allow) {
                return@runCatching CloudActionResponse(
                    completed = false,
                    pendingApproval = false,
                    requestId = requestId,
                    message = "Cloud browser action denied."
                )
            }
            val (_, json) = request(
                "POST",
                config,
                token,
                "/api/v1/computers/${config.botId}/actions/$requestId/execute",
                JSONObject()
            )
            CloudActionResponse(
                completed = true,
                pendingApproval = false,
                requestId = requestId,
                message = "Cloud browser action completed.",
                payload = json
            )
        }
    }

    private fun request(
        method: String,
        config: CloudBrowserConfig,
        token: String,
        path: String,
        body: JSONObject?
    ): Pair<Int, JSONObject> {
        val base = config.baseUrl.trimEnd('/')
        require(base.startsWith("http://") || base.startsWith("https://")) {
            "Cloud browser server must be an HTTP(S) URL."
        }
        require(token.isNotBlank()) { "Cloud browser owner token is missing." }
        val connection = URL(base + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use {
                    it.write(body.toString().toByteArray(Charsets.UTF_8))
                }
            }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("Cloud browser request failed with HTTP $code.")
            }
            return code to if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }
}
