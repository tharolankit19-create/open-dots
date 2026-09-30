package org.opendots.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.opendots.agent.device.InstalledAppResolver
import org.opendots.agent.device.OpenDotsAccessibilityService
import org.opendots.agent.model.ProviderClient
import org.opendots.agent.permission.PermissionEngine
import org.opendots.agent.storage.AppStore
import org.opendots.agent.storage.ProviderConfig
import org.opendots.agent.storage.SecretStore

@Composable
fun WorkspaceScreen(
    store: AppStore,
    secrets: SecretStore,
    resolver: InstalledAppResolver,
    provider: ProviderClient,
    config: ProviderConfig,
    onReconfigure: () -> Unit
) {
    var tab by rememberSaveable { mutableStateOf(0) }
    val tabs = listOf("Chat", "Activity", "Memory", "Settings")
    val permissionEngine = remember { PermissionEngine(store.policyBackend()) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 900.dp
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier.width(190.dp).fillMaxHeight().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Open Dots", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Local agent", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    tabs.forEachIndexed { index, title ->
                        if (tab == index) {
                            Button(onClick = { tab = index }, modifier = Modifier.fillMaxWidth()) {
                                Text(title)
                            }
                        } else {
                            OutlinedButton(onClick = { tab = index }, modifier = Modifier.fillMaxWidth()) {
                                Text(title)
                            }
                        }
                    }
                }

                Column(Modifier.weight(1f).fillMaxHeight()) {
                    WorkspaceContent(
                        tab, store, secrets, resolver, provider, config, permissionEngine, onReconfigure
                    )
                }

                if (tab == 0) {
                    DeviceContextPanel(Modifier.width(270.dp).fillMaxHeight())
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    tabs.forEachIndexed { index, title ->
                        TextButton(onClick = { tab = index }) {
                            Text(if (tab == index) "• " + title else title)
                        }
                    }
                }

                WorkspaceContent(
                    tab, store, secrets, resolver, provider, config, permissionEngine, onReconfigure
                )
            }
        }
    }
}

@Composable
private fun WorkspaceContent(
    tab: Int,
    store: AppStore,
    secrets: SecretStore,
    resolver: InstalledAppResolver,
    provider: ProviderClient,
    config: ProviderConfig,
    permissionEngine: PermissionEngine,
    onReconfigure: () -> Unit
) {
    when (tab) {
        0 -> ChatScreen(store, secrets, resolver, provider, config, permissionEngine)
        1 -> ActivityScreen(store)
        2 -> MemoryScreen(store)
        else -> SettingsScreen(config, provider, secrets, onReconfigure)
    }
}

@Composable
private fun DeviceContextPanel(modifier: Modifier = Modifier) {
    Column(
        modifier.padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Device", fontWeight = FontWeight.Bold)
        Text(
            if (OpenDotsAccessibilityService.isActive()) {
                "Accessibility control: enabled"
            } else {
                "Accessibility control: off"
            }
        )
        Text(
            "Open-app actions use Android launch intents. Rich UI control is optional.",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "Permissions are scoped per capability + target app.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
