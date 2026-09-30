package org.opendots.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.opendots.agent.device.AppMatch
import org.opendots.agent.device.AutomationController
import org.opendots.agent.device.InstalledAppResolver
import org.opendots.agent.device.OpenDotsAccessibilityService
import org.opendots.agent.model.ProviderClient
import org.opendots.agent.model.ProviderResult
import org.opendots.agent.permission.Capability
import org.opendots.agent.permission.PermissionEngine
import org.opendots.agent.permission.PermissionState
import org.opendots.agent.storage.AppStore
import org.opendots.agent.storage.ChatMessage
import org.opendots.agent.storage.ProviderConfig
import org.opendots.agent.storage.SecretStore

@Composable
fun ChatScreen(
    store: AppStore,
    secrets: SecretStore,
    resolver: InstalledAppResolver,
    provider: ProviderClient,
    config: ProviderConfig,
    permissionEngine: PermissionEngine
) {
    val messages = remember {
        mutableStateListOf<ChatMessage>().apply { addAll(store.messages()) }
    }
    var input by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var pendingOpen by remember { mutableStateOf<AppMatch?>(null) }
    var pendingSend by remember { mutableStateOf<SendMessageDraft?>(null) }
    var automationRunning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun reload() {
        messages.clear()
        messages.addAll(store.messages())
    }

    fun assistant(text: String) {
        store.addMessage("assistant", text)
        reload()
    }

    fun runOpen(match: AppMatch, approval: String) {
        scope.launch {
            busy = true
            automationRunning = true
            AutomationController.begin()
            store.audit("device.open_app", match.label, approval, "started", match.packageName)

            if (AutomationController.isCancelled()) {
                store.audit("device.open_app", match.label, approval, "cancelled")
                assistant("Cancelled before opening " + match.label + ".")
            } else {
                val launched = resolver.launch(match)
                delay(700)
                val observed = OpenDotsAccessibilityService.foregroundPackage()
                val verified = observed == match.packageName
                val detail = when {
                    !launched -> "No launchable activity found."
                    verified -> "Foreground package verified through AccessibilityService."
                    OpenDotsAccessibilityService.isActive() ->
                        "Launch requested; foreground package was " + (observed ?: "unknown") + "."
                    else ->
                        "Launch requested; foreground verification is unavailable until AccessibilityService is enabled."
                }
                store.audit(
                    "device.open_app",
                    match.label,
                    approval,
                    if (launched) "success" else "failed",
                    detail
                )
                if (launched) {
                    assistant("Opened " + match.label + if (verified) "." else ". " + detail)
                } else {
                    assistant("I could not open " + match.label + ".")
                }
            }

            AutomationController.finish()
            automationRunning = false
            busy = false
        }
    }

    fun handleSend() {
        val text = input.trim()
        if (text.isBlank() || busy) return
        input = ""
        store.addMessage("user", text)
        reload()

        AgentParser.memoryText(text)?.let { memory ->
            store.addMemory(memory)
            assistant("Saved to local memory. You can inspect or clear it from Memory.")
            return
        }

        AgentParser.sendMessageDraft(text)?.let { draft ->
            pendingSend = draft
            return
        }

        AgentParser.openAppTarget(text)?.let { target ->
            val matches = resolver.resolve(target)
            if (matches.isEmpty()) {
                store.audit(
                    "device.open_app",
                    target,
                    "policy",
                    "failed",
                    "AppNotInstalled or not visible to launcher resolver"
                )
                assistant("I could not find an installed launcher app matching “" + target + "”.")
                return
            }

            val match = matches.first()
            if (matches.size > 1 && !match.label.equals(target, ignoreCase = true)) {
                assistant(
                    "I found multiple matches: " +
                        matches.take(4).joinToString { it.label } +
                        ". Please use the exact app name."
                )
                return
            }

            when (permissionEngine.decision(Capability.OPEN_APP, match.packageName)) {
                PermissionState.ALWAYS_ALLOW -> runOpen(match, "persistent_policy")
                PermissionState.ALLOW_ONCE -> runOpen(match, "allow_once")
                PermissionState.DENY -> {
                    store.audit("device.open_app", match.label, "persistent_policy", "denied")
                    assistant("Opening " + match.label + " is denied by your current policy.")
                }
                PermissionState.ASK_EVERY_TIME -> pendingOpen = match
            }
            return
        }

        busy = true
        scope.launch {
            val key = secrets.get("provider_api_key").orEmpty()
            when (val result = provider.chat(config, key, store.messages(30))) {
                is ProviderResult.Success -> assistant(result.text)
                is ProviderResult.Error -> assistant(result.message)
            }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Agent", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    config.kind.title + " · " + config.modelId,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (automationRunning) {
                Button(
                    onClick = {
                        AutomationController.stop()
                        automationRunning = false
                        store.audit("agent.stop", "device_loop", "user", "cancelled")
                    }
                ) {
                    Text("STOP")
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (messages.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Try: “Open WhatsApp”", fontWeight = FontWeight.SemiBold)
                            Text(
                                "The first open asks for a scoped permission. Always Allow applies only to opening that app."
                            )
                        }
                    }
                }
            }

            items(messages, key = { it.id }) { message ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            if (message.role == "user") "You" else "Open Dots",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelMedium
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(message.content)
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                label = { Text("Ask or control this device") },
                maxLines = 4
            )
            Button(
                enabled = !busy && input.isNotBlank(),
                onClick = { handleSend() }
            ) {
                Text(if (busy) "Working…" else "Send")
            }
        }
    }

    pendingOpen?.let { match ->
        AlertDialog(
            onDismissRequest = { pendingOpen = null },
            title = { Text("Open " + match.label + "?") },
            text = {
                Text(
                    "Capability: open_app\nTarget: " + match.label + "\n\n" +
                        "Always Allow applies only to opening this app. It does not allow messages, typing, screen reading, or other control."
                )
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            pendingOpen = null
                            runOpen(match, "allow_once")
                        }
                    ) { Text("Allow once") }

                    Button(
                        onClick = {
                            permissionEngine.setPersistent(
                                Capability.OPEN_APP,
                                match.packageName,
                                PermissionState.ALWAYS_ALLOW
                            )
                            pendingOpen = null
                            runOpen(match, "persistent_policy")
                        }
                    ) { Text("Always allow") }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        permissionEngine.setPersistent(
                            Capability.OPEN_APP,
                            match.packageName,
                            PermissionState.DENY
                        )
                        store.audit("device.open_app", match.label, "user", "denied")
                        pendingOpen = null
                        assistant("Denied opening " + match.label + ".")
                    }
                ) { Text("Deny") }
            }
        )
    }

    pendingSend?.let { draft ->
        AlertDialog(
            onDismissRequest = { pendingSend = null },
            title = { Text("Ready to send?") },
            text = {
                Text(
                    "To: " + draft.recipient + "\n\n“" + draft.body + "”\n\n" +
                        "Sending is a separate consequential capability and is never authorized by open_app."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        store.audit(
                            "send_message",
                            draft.recipient,
                            "explicit_confirmation",
                            "blocked",
                            "v0.1 deliberately has no automatic send implementation"
                        )
                        pendingSend = null
                        assistant(
                            "You approved the draft, but automatic sending is not enabled in this first build yet. I did not send anything."
                        )
                    }
                ) { Text("Approve") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        store.audit("send_message", draft.recipient, "user", "cancelled")
                        pendingSend = null
                        assistant("Message cancelled. Nothing was sent.")
                    }
                ) { Text("Cancel") }
            }
        )
    }
}
