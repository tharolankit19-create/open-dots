package org.opendots.agent.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.opendots.agent.AppState
import org.opendots.agent.cloud.CloudBrowserConfig
import org.opendots.agent.device.OpenDotsAccessibilityService
import org.opendots.agent.device.PermissionState
import org.opendots.agent.model.ProviderConfig

@Composable
fun OpenDotsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(),
        typography = MaterialTheme.typography,
        content = content
    )
}

private enum class Section(val label: String, val mark: String) {
    CHAT("Chat", "C"),
    AGENT("Agent", "A"),
    ROUTINES("Routines", "R"),
    SKILLS("Skills", "S"),
    MEMORY("Memory", "M"),
    CLOUD("Cloud Browser", "B"),
    ACTIVITY("Activity", "•")
}

@Composable
fun OpenDotsApp(state: AppState) {
    if (!state.onboardingComplete) {
        OnboardingScreen(state)
        return
    }

    val pendingDevice = state.pendingDeviceApproval
    LaunchedEffect(pendingDevice) {
        if (pendingDevice != null && !state.deviceApprovalNeedsPrompt()) {
            state.executePersistentlyAllowedDeviceAction()
        }
    }

    MainShell(state)

    if (state.pendingDeviceApproval != null && state.deviceApprovalNeedsPrompt()) {
        DeviceApprovalDialog(state)
    }
    if (state.pendingCloudApproval != null) {
        CloudApprovalDialog(state)
    }
}

@Composable
private fun OnboardingScreen(state: AppState) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth().width(680.dp),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                modifier = Modifier.padding(28.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("Open Dots Agent", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Runs on this device. Model requests are sent only to the provider you configure. " +
                        "Device actions remain local and permission-gated."
                )
                ProviderEditor(state, initial = true)
            }
        }
    }
}

@Composable
private fun ProviderEditor(state: AppState, initial: Boolean = false) {
    val scope = rememberCoroutineScope()
    var provider by remember(state.providerConfig.provider) { mutableStateOf(state.providerConfig.provider) }
    var baseUrl by remember(state.providerConfig.baseUrl) { mutableStateOf(state.providerConfig.baseUrl) }
    var model by remember(state.providerConfig.model) { mutableStateOf(state.providerConfig.model) }
    var apiKey by remember { mutableStateOf("") }
    val providers = listOf("openai", "anthropic", "gemini", "openrouter", "custom")

    Text("Model provider", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        providers.take(3).forEach { item ->
            AssistChip(
                onClick = {
                    provider = item
                    baseUrl = state.defaultBase(item)
                },
                label = { Text(item) }
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        providers.drop(3).forEach { item ->
            AssistChip(
                onClick = {
                    provider = item
                    baseUrl = state.defaultBase(item)
                },
                label = { Text(item) }
            )
        }
    }
    Text("Selected: $provider")
    OutlinedTextField(
        value = baseUrl,
        onValueChange = { baseUrl = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("API base URL") },
        singleLine = true
    )
    OutlinedTextField(
        value = model,
        onValueChange = { model = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Model ID") },
        singleLine = true
    )
    OutlinedTextField(
        value = apiKey,
        onValueChange = { apiKey = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(if (initial) "API key" else "API key (blank keeps saved key)") },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(
            onClick = {
                scope.launch {
                    state.testProvider(ProviderConfig(provider, baseUrl, model), apiKey)
                }
            }
        ) { Text("Test connection") }
        Button(
            onClick = {
                state.saveProvider(ProviderConfig(provider, baseUrl, model), apiKey)
            }
        ) { Text(if (initial) "Save & continue" else "Save") }
    }
    if (state.providerStatus.isNotBlank()) {
        Text(state.providerStatus, style = MaterialTheme.typography.bodySmall)
    }
    if (initial) {
        Text(
            "You can save configuration before testing. Chat will show a clear provider error until the credentials work.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun MainShell(state: AppState) {
    var sectionName by rememberSaveable { mutableStateOf(Section.CHAT.name) }
    val section = Section.valueOf(sectionName)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 800.dp
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail(
                    modifier = Modifier.fillMaxHeight(),
                    header = {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(vertical = 16.dp)
                        ) {
                            Text("OD", fontWeight = FontWeight.Black)
                            Text("Agent", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                ) {
                    Section.entries.forEach { item ->
                        NavigationRailItem(
                            selected = section == item,
                            onClick = { sectionName = item.name },
                            icon = { Text(item.mark, fontWeight = FontWeight.Bold) },
                            label = { Text(item.label) }
                        )
                    }
                }
                HorizontalDivider(modifier = Modifier.fillMaxHeight().width(1.dp))
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    SectionContent(section, state)
                }
                ContextPanel(state, Modifier.width(290.dp).fillMaxHeight())
            }
        } else {
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        Section.entries.take(5).forEach { item ->
                            NavigationBarItem(
                                selected = section == item,
                                onClick = { sectionName = item.name },
                                icon = { Text(item.mark) },
                                label = { Text(item.label) }
                            )
                        }
                    }
                }
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    SectionContent(section, state)
                }
            }
        }
    }
}

@Composable
private fun SectionContent(section: Section, state: AppState) {
    when (section) {
        Section.CHAT -> ChatScreen(state)
        Section.AGENT -> AgentScreen(state)
        Section.ROUTINES -> RoutinesScreen(state)
        Section.SKILLS -> SkillsScreen()
        Section.MEMORY -> MemoryScreen(state)
        Section.CLOUD -> CloudBrowserScreen(state)
        Section.ACTIVITY -> ActivityScreen(state)
    }
}

@Composable
private fun ScreenHeader(title: String, subtitle: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp)
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium)
    }
    HorizontalDivider()
}

@Composable
private fun ChatScreen(state: AppState) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            "Open Dots",
            "Local agent · try “Open WhatsApp”, “Remember that …”, or “Browse https://example.com”"
        )
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(state.messages, key = { it.id }) { message ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (message.role == "user") Arrangement.End else Arrangement.Start
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(0.84f),
                        colors = CardDefaults.cardColors(
                            containerColor = if (message.role == "user") {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                if (message.role == "user") "You" else "Open Dots",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(message.content)
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                label = { Text("Ask or command…") },
                maxLines = 5
            )
            Button(
                onClick = {
                    val toSend = input
                    input = ""
                    scope.launch { state.send(toSend) }
                },
                enabled = input.isNotBlank()
            ) { Text("Send") }
        }
    }
}

@Composable
private fun AgentScreen(state: AppState) {
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Agent", "Provider, credentials, and device-control permissions")
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ProviderEditor(state)
                }
            }
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Device control", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (OpenDotsAccessibilityService.isEnabled()) {
                            "Accessibility service is enabled."
                        } else {
                            "Accessibility service is optional and currently disabled. App launching works without it; richer UI observation requires explicit Android Settings consent."
                        }
                    )
                    Button(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    ) { Text("Open Accessibility settings") }

                    if (Build.VERSION.SDK_INT >= 33) {
                        OutlinedButton(
                            onClick = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
                        ) { Text("Allow routine notifications") }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoutinesScreen(state: AppState) {
    var text by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("10") }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Routines", "WorkManager-backed reminders that survive normal app process death")
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Reminder") }
            )
            OutlinedTextField(
                value = minutes,
                onValueChange = { minutes = it.filter(Char::isDigit) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Minutes from now") },
                singleLine = true
            )
            Button(
                onClick = {
                    state.scheduleReminder(text, minutes.toLongOrNull() ?: 10)
                    text = ""
                },
                enabled = text.isNotBlank()
            ) { Text("Schedule") }
        }
    }
}

@Composable
private fun SkillsScreen() {
    val skills = listOf(
        Triple("Open installed app", "device.open_app", "Resolves launcher apps and uses Android launch intents."),
        Triple("Cloud Browser", "browser.navigate", "Runs through the authenticated Open Dots computer gateway with approval."),
        Triple("Local memory", "memory.save/search", "Stores durable user notes locally and retrieves relevant notes for chat."),
        Triple("Reminder", "schedule.create", "Creates local WorkManager reminders.")
    )
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Skills", "Built-in skills never grant themselves permissions")
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(skills) { skill ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(skill.first, fontWeight = FontWeight.Bold)
                        Text(skill.second, style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(skill.third)
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryScreen(state: AppState) {
    var note by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Memory", "Inspectable local memory; nothing here grants device permissions")
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                modifier = Modifier.weight(1f),
                label = { Text("Add memory") }
            )
            Button(
                onClick = {
                    state.addMemory(note)
                    note = ""
                },
                enabled = note.isNotBlank()
            ) { Text("Save") }
            OutlinedButton(onClick = { state.clearMemory() }) { Text("Clear") }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(state.memories, key = { it.id }) { memory ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(memory.content)
                        Text(memory.createdAt, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun CloudBrowserScreen(state: AppState) {
    val scope = rememberCoroutineScope()
    var baseUrl by remember(state.cloudConfig.baseUrl) { mutableStateOf(state.cloudConfig.baseUrl) }
    var botId by remember(state.cloudConfig.botId) { mutableStateOf(state.cloudConfig.botId) }
    var token by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("https://example.com") }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            "Cloud Browser",
            "Optional isolated browser node using Open Dots Docker/Playwright or a remote computer provider"
        )
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Gateway", fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Open Dots server URL") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = botId,
                        onValueChange = { botId = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Bot ID") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Text(
                                if (state.hasCloudToken()) {
                                    "Owner token (blank keeps encrypted token)"
                                } else {
                                    "Owner token"
                                }
                            )
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                state.saveCloudConfig(CloudBrowserConfig(baseUrl, botId), token)
                            }
                        ) { Text("Save") }
                        OutlinedButton(
                            onClick = {
                                state.saveCloudConfig(CloudBrowserConfig(baseUrl, botId), token)
                                scope.launch { state.startCloudBrowser() }
                            }
                        ) { Text("Start") }
                        OutlinedButton(
                            onClick = { scope.launch { state.refreshCloudStatus() } }
                        ) { Text("Refresh") }
                    }
                    Text(state.cloudStatus, style = MaterialTheme.typography.bodySmall)
                }
            }

            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Navigate", fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("https://…") },
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            state.saveCloudConfig(CloudBrowserConfig(baseUrl, botId), token)
                            scope.launch { state.navigateCloud(url) }
                        },
                        enabled = url.startsWith("http://") || url.startsWith("https://")
                    ) { Text("Open in Cloud Browser") }
                    Text(
                        "Navigation is not silently trusted. If the server policy requires approval, " +
                            "you will see an approval dialog before execution."
                    )
                }
            }
        }
    }
}

@Composable
private fun ActivityScreen(state: AppState) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Activity", "Local execution receipts and permission sources")
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(state.audits, key = { it.id }) { event ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(event.capability, fontWeight = FontWeight.Bold)
                            Text(event.result, style = MaterialTheme.typography.labelMedium)
                        }
                        Text(event.target)
                        Text("Approval: ${event.approvalSource}", style = MaterialTheme.typography.bodySmall)
                        if (event.details.isNotBlank()) Text(event.details, style = MaterialTheme.typography.bodySmall)
                        Text(event.createdAt, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ContextPanel(state: AppState, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, tonalElevation = 1.dp) {
        Column(
            Modifier.fillMaxSize().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Execution", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            StatusLine(
                "Accessibility",
                if (OpenDotsAccessibilityService.isEnabled()) "Enabled" else "Optional / off"
            )
            StatusLine(
                "Cloud browser",
                if (state.cloudConfig.baseUrl.isBlank()) "Not configured" else state.cloudStatus
            )
            StatusLine("Provider", state.providerConfig.provider + " · " + state.providerConfig.model)
            HorizontalDivider()
            Text("Latest receipt", fontWeight = FontWeight.SemiBold)
            val latest = state.audits.firstOrNull()
            if (latest == null) {
                Text("No tool actions yet.", style = MaterialTheme.typography.bodySmall)
            } else {
                Text(latest.capability)
                Text(latest.target, style = MaterialTheme.typography.bodySmall)
                Text(
                    "${latest.result} · ${latest.approvalSource}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "Open-app permission never implies permission to send messages, read screens, or delete data.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DeviceApprovalDialog(state: AppState) {
    val pending = state.pendingDeviceApproval ?: return
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Open ${pending.target.label}?") },
        text = {
            Text(
                "Capability: open_app\nTarget: ${pending.target.packageName}\n\n" +
                    "This permission does not allow messaging, typing, screen reading, or other actions."
            )
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = {
                        scope.launch { state.resolveDeviceApproval(PermissionState.ALLOW_ONCE) }
                    }
                ) { Text("Allow once") }
                Button(
                    onClick = {
                        scope.launch { state.resolveDeviceApproval(PermissionState.ALWAYS_ALLOW) }
                    }
                ) { Text("Always allow") }
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    scope.launch { state.resolveDeviceApproval(PermissionState.DENY) }
                }
            ) { Text("Deny") }
        }
    )
}

@Composable
private fun CloudApprovalDialog(state: AppState) {
    val pending = state.pendingCloudApproval ?: return
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Allow Cloud Browser navigation?") },
        text = {
            Text(
                "The isolated browser is ready to navigate to:\n${pending.url}\n\n" +
                    "This approval applies only to this server action."
            )
        },
        confirmButton = {
            Button(onClick = { scope.launch { state.resolveCloudApproval(true) } }) {
                Text("Allow")
            }
        },
        dismissButton = {
            TextButton(onClick = { scope.launch { state.resolveCloudApproval(false) } }) {
                Text("Deny")
            }
        }
    )
}
