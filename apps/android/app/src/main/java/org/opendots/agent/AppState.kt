package org.opendots.agent

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.opendots.agent.cloud.CloudActionResponse
import org.opendots.agent.cloud.CloudBrowserClient
import org.opendots.agent.cloud.CloudBrowserConfig
import org.opendots.agent.cloud.CloudFrame
import org.opendots.agent.data.AuditEntry
import org.opendots.agent.data.ChatMessage
import org.opendots.agent.data.LocalStore
import org.opendots.agent.data.MemoryItem
import org.opendots.agent.device.ActionScope
import org.opendots.agent.device.AgentCancellation
import org.opendots.agent.device.AgentControlService
import org.opendots.agent.device.AppTarget
import org.opendots.agent.device.DeviceController
import org.opendots.agent.device.PermissionEngine
import org.opendots.agent.device.PermissionState
import org.opendots.agent.device.PolicyStore
import org.opendots.agent.device.RiskLevel
import org.opendots.agent.model.ProviderClient
import org.opendots.agent.model.ProviderConfig
import org.opendots.agent.scheduler.RoutineWorker
import org.opendots.agent.security.SecretStore
import org.json.JSONObject

data class PendingDeviceApproval(val scope: ActionScope, val target: AppTarget)
data class PendingCloudApproval(
    val requestId: String,
    val capability: String,
    val target: String,
    val description: String
)

class AppState(private val context: Context) {
    private val prefs = context.getSharedPreferences("open_dots_settings", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context)
    private val store = LocalStore(context)
    private val providerClient = ProviderClient()
    private val device = DeviceController(context)
    private val cloud = CloudBrowserClient()

    private val policyStore = object : PolicyStore {
        override fun read(scope: ActionScope): PermissionState? =
            store.readPolicy(scope.capability, scope.target)?.let {
                runCatching { PermissionState.valueOf(it) }.getOrNull()
            }

        override fun write(scope: ActionScope, state: PermissionState) =
            store.writePolicy(scope.capability, scope.target, state.name)

        override fun clear(scope: ActionScope) =
            store.clearPolicy(scope.capability, scope.target)
    }

    private val permissionEngine = PermissionEngine(policyStore)

    var providerConfig by mutableStateOf(
        ProviderConfig(
            provider = prefs.getString("provider", "openai") ?: "openai",
            baseUrl = prefs.getString("provider_base_url", "https://api.openai.com/v1")
                ?: "https://api.openai.com/v1",
            model = prefs.getString("provider_model", "gpt-5-mini") ?: "gpt-5-mini"
        )
    )
        private set

    var onboardingComplete by mutableStateOf(
        providerConfig.model.isNotBlank() && !secrets.get("provider_api_key").isNullOrBlank()
    )
        private set

    var providerStatus by mutableStateOf("")
        private set

    val messages = mutableStateListOf<ChatMessage>().apply { addAll(store.listMessages()) }
    val audits = mutableStateListOf<AuditEntry>().apply { addAll(store.listAudit()) }
    val memories = mutableStateListOf<MemoryItem>().apply { addAll(store.listMemory()) }

    var pendingDeviceApproval by mutableStateOf<PendingDeviceApproval?>(null)
        private set
    var pendingCloudApproval by mutableStateOf<PendingCloudApproval?>(null)
        private set

    var cloudConfig by mutableStateOf(
        CloudBrowserConfig(
            baseUrl = prefs.getString("cloud_base_url", "") ?: "",
            botId = prefs.getString("cloud_bot_id", "bot-open-dots-1") ?: "bot-open-dots-1"
        )
    )
        private set
    var cloudStatus by mutableStateOf("Not connected")
        private set
    var cloudFrame by mutableStateOf<CloudFrame?>(null)
        private set

    fun defaultBase(provider: String): String = when (provider) {
        "openai" -> "https://api.openai.com/v1"
        "anthropic" -> "https://api.anthropic.com"
        "gemini" -> "https://generativelanguage.googleapis.com/v1beta"
        "openrouter" -> "https://openrouter.ai/api/v1"
        else -> ""
    }

    fun saveProvider(config: ProviderConfig, apiKey: String) {
        providerConfig = config
        prefs.edit()
            .putString("provider", config.provider)
            .putString("provider_base_url", config.baseUrl)
            .putString("provider_model", config.model)
            .apply()
        if (apiKey.isNotBlank()) secrets.put("provider_api_key", apiKey)
        onboardingComplete = config.model.isNotBlank() &&
            !secrets.get("provider_api_key").isNullOrBlank()
    }

    suspend fun testProvider(config: ProviderConfig, apiKey: String) {
        providerStatus = "Testing connection…"
        val key = apiKey.ifBlank { secrets.get("provider_api_key").orEmpty() }
        providerStatus = providerClient.test(config, key)
            .fold(
                onSuccess = { "Connected: ${it.take(120)}" },
                onFailure = { "Connection failed: ${it.message ?: it.javaClass.simpleName}" }
            )
    }

    fun saveCloudConfig(config: CloudBrowserConfig, token: String) {
        cloudConfig = config
        prefs.edit()
            .putString("cloud_base_url", config.baseUrl)
            .putString("cloud_bot_id", config.botId)
            .apply()
        if (token.isNotBlank()) secrets.put("cloud_owner_token", token)
        cloudStatus = "Configuration saved"
    }

    fun hasCloudToken(): Boolean = !secrets.get("cloud_owner_token").isNullOrBlank()

    suspend fun refreshCloudStatus() {
        val token = secrets.get("cloud_owner_token").orEmpty()
        cloudStatus = cloud.status(cloudConfig, token).fold(
            onSuccess = {
                val status = it.optJSONObject("status")
                "State: ${status?.optString("state") ?: "unknown"} · " +
                    "Provider: ${status?.optString("provider") ?: "unknown"}"
            },
            onFailure = { "Cloud browser unavailable: ${it.message}" }
        )
    }

    suspend fun startCloudBrowser() {
        val token = secrets.get("cloud_owner_token").orEmpty()
        cloudStatus = "Starting cloud browser…"
        cloudStatus = cloud.ensureRunning(cloudConfig, token).fold(
            onSuccess = { "Cloud browser running" },
            onFailure = { "Start failed: ${it.message}" }
        )
        if (cloudStatus == "Cloud browser running") {
            refreshCloudStatus()
            refreshCloudFrame()
        }
    }

    suspend fun refreshCloudFrame() {
        val token = secrets.get("cloud_owner_token").orEmpty()
        if (cloudConfig.baseUrl.isBlank() || token.isBlank()) {
            cloudStatus = "Cloud Browser is not configured"
            return
        }
        cloud.screenshot(cloudConfig, token).fold(
            onSuccess = { frame ->
                cloudFrame = frame
                if (frame.available) {
                    cloudStatus = frame.url?.let { "Frame: $it" } ?: "Cloud browser frame refreshed"
                } else {
                    cloudStatus = frame.message ?: "Cloud browser frame unavailable"
                }
            },
            onFailure = { cloudStatus = "Frame failed: ${it.message}" }
        )
    }

    fun addMemory(content: String) {
        if (content.isBlank()) return
        memories.add(0, store.addMemory(content))
    }

    fun clearMemory() {
        store.clearMemory()
        memories.clear()
    }

    fun clearDevicePolicies() {
        store.clearPolicies()
        addAudit("permissions.reset", "device", "user request", "success", "All saved device policies cleared.")
    }

    fun scheduleReminder(text: String, minutes: Long) {
        RoutineWorker.schedule(context, text, minutes)
        addAssistant("Scheduled a visible reminder in $minutes minute(s): $text")
        addAudit("schedule.create", "local", "user request", "scheduled", text)
    }

    suspend fun send(text: String) {
        val clean = text.trim()
        if (clean.isBlank()) return
        val userMessage = store.addMessage("user", clean)
        messages += userMessage

        Regex("(?i)^remember(?: that)?\\s+(.+)$").matchEntire(clean)?.let {
            val memory = it.groupValues[1].trim()
            addMemory(memory)
            addAssistant("Saved to local memory.")
            return
        }

        Regex("(?i)^remind me in\\s+(\\d+)\\s+minutes?\\s+(?:to\\s+)?(.+)$")
            .matchEntire(clean)?.let {
                scheduleReminder(it.groupValues[2], it.groupValues[1].toLong())
                return
            }

        Regex("(?i)^(?:browse|open)\\s+(https?://\\S+)$").matchEntire(clean)?.let {
            prepareCloudNavigation(it.groupValues[1])
            return
        }

        Regex("(?i)^open\\s+(.+)$").matchEntire(clean)?.let {
            requestOpenApp(it.groupValues[1])
            return
        }

        if (Regex("(?i)^(?:message|send(?: a)? message)\\b").containsMatchIn(clean)) {
            addAssistant(
                "Sending an external message is a separate high-impact capability. " +
                    "The current Android build will not treat an open-app permission as permission to send."
            )
            addAudit("send_message", "external", "not granted", "blocked", "No send executor ran.")
            return
        }

        val key = secrets.get("provider_api_key").orEmpty()
        if (key.isBlank()) {
            addAssistant("Configure a model provider and API key in Agent settings first.")
            return
        }
        val memory = store.searchMemory(clean, 6)
        val memoryContext = if (memory.isEmpty()) "" else {
            memory.joinToString(
                prefix = "\nRelevant local memory:\n",
                separator = "\n"
            ) { "- ${it.content}" }
        }
        val system = """
            You are Open Dots, a local-first personal AI agent.
            Device actions are executed only through explicit normalized tools and permissions.
            Never claim a device or browser action happened unless the tool layer reports success.
            The open_app permission is scoped separately from sending messages, typing, deleting, or other actions.
            $memoryContext
        """.trimIndent()
        val history = messages.map { it.role to it.content }
        val result = providerClient.chat(providerConfig, key, history, system)
        val finalText = result.fold(
            onSuccess = { it },
            onFailure = { "Model request failed: ${it.message ?: it.javaClass.simpleName}" }
        )
        messages += store.addMessage("assistant", finalText)
    }

    private fun requestOpenApp(query: String) {
        val matches = device.resolveApp(query)
        if (matches.isEmpty()) {
            addAssistant("I could not find a launchable app matching “$query”.")
            addAudit("open_app", query, "none", "failed", "App not installed or not visible.")
            return
        }
        if (matches.size > 1) {
            addAssistant(
                "Multiple apps match that name: " +
                    matches.joinToString { "${it.label} (${it.packageName})" } +
                    ". Please use the exact app name."
            )
            return
        }
        val target = matches.first()
        val scope = ActionScope("open_app", target.packageName, RiskLevel.WRITE)
        when (permissionEngine.decision(scope)) {
            PermissionState.DENY -> {
                addAssistant("Opening ${target.label} is denied by your saved policy.")
                addAudit("open_app", target.packageName, "persistent deny", "blocked")
            }
            PermissionState.ALWAYS_ALLOW -> {
                pendingDeviceApproval = PendingDeviceApproval(scope, target)
            }
            PermissionState.ASK_EVERY_TIME,
            PermissionState.ALLOW_ONCE -> pendingDeviceApproval = PendingDeviceApproval(scope, target)
        }
    }

    fun deviceApprovalNeedsPrompt(): Boolean {
        val pending = pendingDeviceApproval ?: return false
        return permissionEngine.decision(pending.scope) != PermissionState.ALWAYS_ALLOW
    }

    suspend fun resolveDeviceApproval(state: PermissionState) {
        val pending = pendingDeviceApproval ?: return
        pendingDeviceApproval = null
        if (state == PermissionState.DENY) {
            permissionEngine.resolve(pending.scope, PermissionState.DENY)
            addAudit("open_app", pending.target.packageName, "user denied", "denied")
            addAssistant("Denied. ${pending.target.label} was not opened.")
            return
        }
        permissionEngine.resolve(pending.scope, state)
        val source = if (state == PermissionState.ALWAYS_ALLOW) {
            "persistent policy"
        } else {
            "allow once"
        }
        executeOpenApp(pending.target, source)
    }

    suspend fun executePersistentlyAllowedDeviceAction() {
        val pending = pendingDeviceApproval ?: return
        if (permissionEngine.decision(pending.scope) != PermissionState.ALWAYS_ALLOW) return
        pendingDeviceApproval = null
        executeOpenApp(pending.target, "persistent policy")
    }

    private suspend fun executeOpenApp(target: AppTarget, approvalSource: String) {
        AgentControlService.start(context)
        try {
            if (AgentCancellation.isCancelled()) {
                addAudit("open_app", target.packageName, approvalSource, "cancelled")
                addAssistant("Device action cancelled.")
                return
            }
            val result = device.openApp(target)
            addAudit(
                "open_app",
                target.packageName,
                approvalSource,
                if (result.success) "success" else "failed",
                result.message
            )
            addAssistant(result.message)
        } finally {
            AgentControlService.stop(context)
        }
    }

    private suspend fun prepareCloudNavigation(url: String) {
        val token = secrets.get("cloud_owner_token").orEmpty()
        if (cloudConfig.baseUrl.isBlank() || token.isBlank()) {
            addAssistant(
                "Cloud Browser is not configured. Open the Cloud Browser tab and add your Open Dots server URL and owner token."
            )
            return
        }
        val started = cloud.ensureRunning(cloudConfig, token)
        if (started.isFailure) {
            addAssistant("Cloud Browser could not start: ${started.exceptionOrNull()?.message}")
            return
        }
        cloud.navigate(cloudConfig, token, url).fold(
            onSuccess = { response ->
                handleCloudActionResponse(
                    capability = "browser.navigate",
                    target = url,
                    description = "Navigate Cloud Browser to $url",
                    response = response
                )
            },
            onFailure = {
                addAssistant("Cloud Browser navigation failed: ${it.message}")
                addAudit("browser.navigate", url, "gateway", "failed", it.message.orEmpty())
            }
        )
    }

    suspend fun navigateCloud(url: String) = prepareCloudNavigation(url)

    suspend fun cloudClick(x: Int, y: Int) {
        sendCloudInput(
            target = "click($x,$y)",
            description = "Click Cloud Browser at x=$x, y=$y",
            event = JSONObject().put("type", "click").put("x", x).put("y", y)
        )
    }

    suspend fun cloudType(text: String) {
        if (text.isBlank()) return
        sendCloudInput(
            target = "type",
            description = "Type into the focused Cloud Browser control",
            event = JSONObject().put("type", "type").put("text", text)
        )
    }

    suspend fun cloudKeypress(key: String) {
        if (key.isBlank()) return
        sendCloudInput(
            target = "keypress:$key",
            description = "Press $key in Cloud Browser",
            event = JSONObject().put("type", "keypress").put("key", key)
        )
    }

    suspend fun cloudScroll(deltaY: Int) {
        sendCloudInput(
            target = "scroll:$deltaY",
            description = "Scroll Cloud Browser by $deltaY",
            event = JSONObject()
                .put("type", "scroll")
                .put("deltaX", 0)
                .put("deltaY", deltaY)
        )
    }

    private suspend fun sendCloudInput(
        target: String,
        description: String,
        event: JSONObject
    ) {
        val token = secrets.get("cloud_owner_token").orEmpty()
        if (cloudConfig.baseUrl.isBlank() || token.isBlank()) {
            addAssistant("Cloud Browser is not configured.")
            return
        }
        cloud.sendInput(cloudConfig, token, event).fold(
            onSuccess = { response ->
                handleCloudActionResponse("browser.input", target, description, response)
            },
            onFailure = {
                addAudit("browser.input", target, "gateway", "failed", it.message.orEmpty())
                cloudStatus = "Input failed: ${it.message}"
            }
        )
    }

    private suspend fun handleCloudActionResponse(
        capability: String,
        target: String,
        description: String,
        response: CloudActionResponse
    ) {
        if (response.pendingApproval) {
            val id = response.requestId
            if (id.isNullOrBlank()) {
                addAssistant("Cloud Browser requested approval but returned no request id.")
                return
            }
            pendingCloudApproval = PendingCloudApproval(id, capability, target, description)
            return
        }
        addAudit(capability, target, "server policy", "success")
        cloudStatus = response.message
        refreshCloudFrame()
    }

    suspend fun resolveCloudApproval(allow: Boolean) {
        val pending = pendingCloudApproval ?: return
        pendingCloudApproval = null
        val token = secrets.get("cloud_owner_token").orEmpty()
        cloud.resolveAndExecute(
            cloudConfig,
            token,
            pending.requestId,
            allow
        ).fold(
            onSuccess = {
                val result = if (allow && it.completed) "success" else "denied"
                addAudit(
                    pending.capability,
                    pending.target,
                    if (allow) "user approval" else "user denied",
                    result
                )
                addAssistant(
                    if (allow && it.completed) {
                        "Cloud Browser action completed: ${pending.description}"
                    } else {
                        "Cloud Browser action denied."
                    }
                )
                cloudStatus = it.message
                if (allow && it.completed) refreshCloudFrame()
            },
            onFailure = {
                addAudit(
                    pending.capability,
                    pending.target,
                    "user approval",
                    "failed",
                    it.message.orEmpty()
                )
                addAssistant("Cloud Browser approval failed: ${it.message}")
            }
        )
    }

    private fun addAssistant(text: String) {
        messages += store.addMessage("assistant", text)
    }

    private fun addAudit(
        capability: String,
        target: String,
        source: String,
        result: String,
        details: String = ""
    ) {
        audits.add(
            0,
            store.addAudit(capability, target, source, result, details)
        )
    }
}
