package org.opendots.agent.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.opendots.agent.storage.ChatMessage
import org.opendots.agent.storage.ProviderConfig
import org.opendots.agent.storage.ProviderKind
import java.net.HttpURLConnection
import java.net.URL

sealed class ProviderResult {
    data class Success(val text: String) : ProviderResult()
    data class Error(val message: String) : ProviderResult()
}

class ProviderClient {
    suspend fun test(config: ProviderConfig, apiKey: String): ProviderResult =
        chat(config, apiKey, listOf(ChatMessage(0, "user", "Reply with exactly OK.", 0)))

    suspend fun chat(
        config: ProviderConfig,
        apiKey: String,
        messages: List<ChatMessage>
    ): ProviderResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext ProviderResult.Error("API key is required.")
        if (config.modelId.isBlank()) return@withContext ProviderResult.Error("Model ID is required.")

        runCatching {
            when (config.kind) {
                ProviderKind.ANTHROPIC -> anthropic(config, apiKey, messages)
                ProviderKind.GEMINI -> gemini(config, apiKey, messages)
                ProviderKind.OPENAI,
                ProviderKind.OPENAI_COMPATIBLE,
                ProviderKind.OPENROUTER -> openAiCompatible(config, apiKey, messages)
            }
        }.getOrElse { ProviderResult.Error(normalizeError(it)) }
    }

    private fun defaultBase(kind: ProviderKind): String = when (kind) {
        ProviderKind.OPENAI -> "https://api.openai.com/v1"
        ProviderKind.OPENROUTER -> "https://openrouter.ai/api/v1"
        ProviderKind.ANTHROPIC -> "https://api.anthropic.com/v1"
        ProviderKind.GEMINI -> "https://generativelanguage.googleapis.com/v1beta"
        ProviderKind.OPENAI_COMPATIBLE -> ""
    }

    private fun base(config: ProviderConfig): String =
        (config.baseUrl.ifBlank { defaultBase(config.kind) }).trimEnd('/')

    private fun openAiCompatible(
        config: ProviderConfig,
        apiKey: String,
        messages: List<ChatMessage>
    ): ProviderResult {
        val base = base(config)
        if (base.isBlank()) return ProviderResult.Error("Base URL is required for a custom provider.")
        val body = JSONObject()
            .put("model", config.modelId)
            .put("messages", JSONArray().apply {
                messages.takeLast(20).forEach {
                    put(JSONObject().put("role", it.role).put("content", it.content))
                }
            })
            .put("temperature", 0.2)

        val (code, raw) = post(
            "${base}/chat/completions",
            mapOf("Authorization" to "Bearer $apiKey", "Content-Type" to "application/json"),
            body.toString()
        )
        if (code !in 200..299) return ProviderResult.Error(httpError(code, raw))
        val text = JSONObject(raw).optJSONArray("choices")
            ?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
            ?.takeIf { it.isNotBlank() }
            ?: return ProviderResult.Error("Provider returned no message content.")
        return ProviderResult.Success(text)
    }

    private fun anthropic(
        config: ProviderConfig,
        apiKey: String,
        messages: List<ChatMessage>
    ): ProviderResult {
        val body = JSONObject()
            .put("model", config.modelId)
            .put("max_tokens", 1024)
            .put("messages", JSONArray().apply {
                messages.takeLast(20).forEach {
                    put(
                        JSONObject()
                            .put("role", if (it.role == "assistant") "assistant" else "user")
                            .put("content", it.content)
                    )
                }
            })

        val (code, raw) = post(
            "${base(config)}/messages",
            mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to "2023-06-01",
                "Content-Type" to "application/json"
            ),
            body.toString()
        )
        if (code !in 200..299) return ProviderResult.Error(httpError(code, raw))
        val text = JSONObject(raw).optJSONArray("content")?.optJSONObject(0)?.optString("text")
            ?.takeIf { it.isNotBlank() }
            ?: return ProviderResult.Error("Anthropic returned no message content.")
        return ProviderResult.Success(text)
    }

    private fun gemini(
        config: ProviderConfig,
        apiKey: String,
        messages: List<ChatMessage>
    ): ProviderResult {
        val body = JSONObject().put("contents", JSONArray().apply {
            messages.takeLast(20).forEach {
                put(
                    JSONObject()
                        .put("role", if (it.role == "assistant") "model" else "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", it.content)))
                )
            }
        })

        val endpoint = "${base(config)}/models/${config.modelId}:generateContent?key=${urlEncode(apiKey)}"
        val (code, raw) = post(endpoint, mapOf("Content-Type" to "application/json"), body.toString())
        if (code !in 200..299) return ProviderResult.Error(httpError(code, raw))
        val text = JSONObject(raw).optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text")
            ?.takeIf { it.isNotBlank() }
            ?: return ProviderResult.Error("Gemini returned no message content.")
        return ProviderResult.Success(text)
    }

    private fun post(url: String, headers: Map<String, String>, body: String): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 45_000
            doOutput = true
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }

        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        return code to raw
    }

    private fun httpError(code: Int, raw: String): String {
        val message = runCatching {
            val json = JSONObject(raw)
            json.optJSONObject("error")?.optString("message")
                ?: json.optString("message")
        }.getOrNull().orEmpty()

        return when (code) {
            401, 403 -> "Provider authentication failed. Check the API key."
            429 -> "Provider rate limit reached. Try again shortly."
            else -> "Provider request failed (HTTP $code)" +
                if (message.isNotBlank()) ": " + message.take(240) else "."
        }
    }

    private fun normalizeError(t: Throwable): String = when (t) {
        is java.net.SocketTimeoutException -> "Provider request timed out."
        is java.net.UnknownHostException -> "Network unavailable or provider host could not be resolved."
        else -> t.message?.take(240) ?: "Provider request failed."
    }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())
}
