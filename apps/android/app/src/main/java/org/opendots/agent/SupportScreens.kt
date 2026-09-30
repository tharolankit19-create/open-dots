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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.opendots.agent.device.OpenDotsAccessibilityService
import org.opendots.agent.device.accessibilitySettingsIntent
import org.opendots.agent.model.ProviderClient
import org.opendots.agent.model.ProviderResult
import org.opendots.agent.storage.AppStore
import org.opendots.agent.storage.ProviderConfig
import org.opendots.agent.storage.SecretStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ActivityScreen(store: AppStore) {
    var entries by remember { mutableStateOf(store.audits()) }

    Column(Modifier.fillMaxSize().padding(18.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "Activity & receipts",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            OutlinedButton(onClick = { entries = store.audits() }) {
                Text("Refresh")
            }
        }

        Spacer(Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(entries, key = { it.id }) { item ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(item.action + " · " + item.result, fontWeight = FontWeight.Bold)
                        Text(item.target)
                        Text(
                            "Approval: " + item.approvalSource,
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (item.detail.isNotBlank()) {
                            Text(item.detail, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            formatTime(item.timestamp),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MemoryScreen(store: AppStore) {
    var entries by remember { mutableStateOf(store.memories()) }

    Column(Modifier.fillMaxSize().padding(18.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    "Memory",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Local, inspectable memories saved on this device.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            OutlinedButton(
                onClick = {
                    store.clearMemory()
                    entries = emptyList()
                }
            ) {
                Text("Clear all")
            }
        }

        Spacer(Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(entries, key = { it.id }) { item ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(item.text)
                        Text(
                            formatTime(item.createdAt),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    config: ProviderConfig,
    provider: ProviderClient,
    secrets: SecretStore,
    onReconfigure: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Model provider", fontWeight = FontWeight.Bold)
                Text(config.kind.title)
                Text(config.modelId)
                if (config.baseUrl.isNotBlank()) {
                    Text(config.baseUrl, style = MaterialTheme.typography.bodySmall)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onReconfigure) {
                        Text("Edit provider")
                    }

                    OutlinedButton(
                        enabled = !testing,
                        onClick = {
                            testing = true
                            scope.launch {
                                when (
                                    val result = provider.test(
                                        config,
                                        secrets.get("provider_api_key").orEmpty()
                                    )
                                ) {
                                    is ProviderResult.Success -> status = "Connection verified."
                                    is ProviderResult.Error -> status = result.message
                                }
                                testing = false
                            }
                        }
                    ) {
                        Text(if (testing) "Testing…" else "Test")
                    }
                }

                status?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Device control", fontWeight = FontWeight.Bold)
                Text(
                    if (OpenDotsAccessibilityService.isActive()) {
                        "Accessibility Service is enabled."
                    } else {
                        "Accessibility Service is optional and currently disabled."
                    }
                )
                Text(
                    "Enable it only for richer visible UI interaction. Android keeps this permission in system Settings and you can turn it off at any time.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(
                    onClick = { context.startActivity(accessibilitySettingsIntent()) }
                ) {
                    Text("Open Accessibility Settings")
                }
            }
        }

        Text(
            "Conversations, permissions, activity history and memory are stored locally. " +
                "Remote model requests are sent only to your configured provider.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private fun formatTime(timestamp: Long): String =
    SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
