package org.opendots.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.opendots.agent.model.ProviderClient
import org.opendots.agent.model.ProviderResult
import org.opendots.agent.storage.ProviderConfig
import org.opendots.agent.storage.ProviderKind

@Composable
fun OnboardingScreen(
    existingKey: String?,
    provider: ProviderClient,
    onSave: (ProviderConfig, String) -> Unit
) {
    var kind by rememberSaveable { mutableStateOf(ProviderKind.OPENAI) }
    var model by rememberSaveable { mutableStateOf(defaultModel(kind)) }
    var baseUrl by rememberSaveable { mutableStateOf("") }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var testedOk by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Open Dots", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(
            "Local-first device agent. Model requests go only to the provider you configure. " +
                "Device actions stay behind scoped permissions."
        )
        HorizontalDivider()

        Text("1. Choose provider", fontWeight = FontWeight.SemiBold)
        ProviderKind.values().toList().chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { item ->
                    FilterChip(
                        selected = kind == item,
                        onClick = {
                            kind = item
                            model = defaultModel(item)
                            baseUrl = ""
                            testedOk = false
                        },
                        label = { Text(item.title) }
                    )
                }
            }
        }

        OutlinedTextField(
            value = model,
            onValueChange = { model = it; testedOk = false },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Model ID") },
            singleLine = true
        )

        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it; testedOk = false },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Custom API base URL (optional for hosted providers)") },
            singleLine = true
        )

        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it; testedOk = false },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(if (existingKey == null) "API key" else "API key (blank keeps saved key)") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true
        )

        status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                enabled = !testing && model.isNotBlank() && (apiKey.isNotBlank() || existingKey != null),
                onClick = {
                    testing = true
                    status = "Testing provider…"
                    val testConfig = ProviderConfig(kind, model.trim(), baseUrl.trim())
                    scope.launch {
                        when (val result = provider.test(testConfig, apiKey.ifBlank { existingKey.orEmpty() })) {
                            is ProviderResult.Success -> {
                                testedOk = true
                                status = "Connection verified."
                            }
                            is ProviderResult.Error -> {
                                testedOk = false
                                status = result.message
                            }
                        }
                        testing = false
                    }
                }
            ) { Text(if (testing) "Testing…" else "Test connection") }

            Button(
                enabled = model.isNotBlank() && (apiKey.isNotBlank() || existingKey != null),
                onClick = {
                    if (!testedOk) status = "Saved without a live test. Test it later in Settings."
                    onSave(ProviderConfig(kind, model.trim(), baseUrl.trim()), apiKey)
                }
            ) { Text("Save & continue") }
        }

        Text(
            "API keys are encrypted with Android Keystore. They are not stored in plaintext preferences, logs, or the app bundle.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private fun defaultModel(kind: ProviderKind): String = when (kind) {
    ProviderKind.OPENAI -> "gpt-4o-mini"
    ProviderKind.ANTHROPIC -> "claude-3-5-sonnet-latest"
    ProviderKind.GEMINI -> "gemini-1.5-flash"
    ProviderKind.OPENROUTER -> "openai/gpt-4o-mini"
    ProviderKind.OPENAI_COMPATIBLE -> ""
}
