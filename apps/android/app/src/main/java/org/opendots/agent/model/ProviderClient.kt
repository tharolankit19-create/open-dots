package org.opendots.agent.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class ProviderConfig(
    val provider: String = "openai",
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "gpt-5-mini"
)

class ProviderClient {
    suspend fun test(config: ProviderConfig, apiKey: String): Result<String> =
        chat(
            config,
            apiKey,
            listOf("user" to "Reply with the single word OK."),
            "This is a connection test."
        )

    suspend fun chat(
        config: ProviderConfig,
        apiKey: String,
        messages: List<Pair<String, String>>,
        systemPrompt: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(apiKey.isNotBlank()) { "API key is missing." }
            when (config.provider.lowercase()) {
                "anthropic" -> anthropic(config, apiKey, messages, systemPrompt)
                "gemini" -> gemini(config, apiKey, messages, systemPrompt)
                else -> openAiCompatible(config, apiKey, messages, systemPrompt)
            }
        }
    }

    private fun openAiCompatible(
        config: ProviderConfig,
        apiKey: String,
        messages: List<Pair<String, String>>,
        systemPrompt: String
    ): String {
        val base = config.baseUrl.trimEnd('/')
        val bodyMessages = JSONArray()
        if (systemPrompt.isNotBlank()) {
            bodyMessages.put(JSONObject().put("role", "system").put("content", systemPrompt))
        }
        messages.takeLast(30).forEach { (role, content) ->
            bodyMessages.put(JSONObject().put("role", role).put("content", content))
        }
        val body = JSONObject()
            .put("model", config.model)
            .put("messages", bodyMessages)
            .put("stream", false)
        val json = request(
            "$base/chat/completions",
            mapOf("Authorization" to "Bearer $apiKey"),
            body
        )
        val text = json.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?.trim()
        require(!text.isNullOrBlank()) { "Provider returned no message content." }
        return text
    }

    private fun anthropic(
        config: ProviderConfig,
        apiKey: String,
        messages: List<Pair<String, String>>,
        systemPrompt: String
    ): String {
        val base = config.baseUrl.trimEnd('/').ifBlank { "https://api.anthropic.com" }
        val bodyMessages = JSONArray()
        messages.takeLast(30).forEach { (role, content) ->
            bodyMessages.put(
                JSONObject()
                    .put("role", if (role == "assistant") "assistant" else "user")
                    .put("content", content)
            )
        }
        val body = JSONObject()
            .put("model", config.model)
            .put("max_tokens", 2048)
            .put("system", systemPrompt)
            .put("messages", bodyMessages)
        val json = request(
            "$base/v1/messages",
            mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to "2023-06-01"
            ),
            body
        )
        val text = json.optJSONArray("content")
            ?.optJSONObject(0)
            ?.optString("text")
            ?.trim()
        require(!text.isNullOrBlank()) { "Anthropic returned no text content." }
        return text
    }

    private fun gemini(
        config: ProviderConfig,
        apiKey: String,
        messages: List<Pair<String, String>>,
        systemPrompt: String
    ): String {
        val base = config.baseUrl.trimEnd('/').ifBlank {
            "https://generativelanguage.googleapis.com/v1beta"
        }
        val contents = JSONArray()
        if (systemPrompt.isNotBlank()) {
            contents.put(
                JSONObject()
                    .put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            )
        }
        messages.takeLast(30).forEach { (role, content) ->
            contents.put(
                JSONObject()
                    .put("role", if (role == "assistant") "model" else "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", content)))
            )
        }
        val encodedKey = URLEncoder.encode(apiKey, Charsets.UTF_8.name())
        val url = "$base/models/${config.model}:generateContent?key=$encodedKey"
        val json = request(url, emptyMap(), JSONObject().put("contents", contents))
        val text = json.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?.optJSONObject(0)
            ?.optString("text")
            ?.trim()
        require(!text.isNullOrBlank()) { "Gemini returned no text content." }
        return text
    }

    private fun request(
        url: String,
        headers: Map<String, String>,
        body: JSONObject
    ): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            connection.readTimeout = 90_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("Provider request failed with HTTP $code.")
            }
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }
}
