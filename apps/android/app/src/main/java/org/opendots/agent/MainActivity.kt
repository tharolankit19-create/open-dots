package org.opendots.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.opendots.agent.device.InstalledAppResolver
import org.opendots.agent.model.ProviderClient
import org.opendots.agent.storage.AppStore
import org.opendots.agent.storage.SecretStore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = AppStore(applicationContext)
        val secrets = SecretStore(applicationContext)
        val resolver = InstalledAppResolver(applicationContext)
        val provider = ProviderClient()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var config by remember { mutableStateOf(store.loadProvider()) }
                    if (config == null) {
                        OnboardingScreen(
                            existingKey = secrets.get("provider_api_key"),
                            provider = provider,
                            onSave = { newConfig, apiKey ->
                                if (apiKey.isNotBlank()) secrets.put("provider_api_key", apiKey)
                                store.saveProvider(newConfig)
                                config = newConfig
                            }
                        )
                    } else {
                        WorkspaceScreen(
                            store = store,
                            secrets = secrets,
                            resolver = resolver,
                            provider = provider,
                            config = config!!,
                            onReconfigure = { config = null }
                        )
                    }
                }
            }
        }
    }
}
