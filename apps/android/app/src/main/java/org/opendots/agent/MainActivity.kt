package org.opendots.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import org.opendots.agent.ui.OpenDotsApp
import org.opendots.agent.ui.OpenDotsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OpenDotsTheme {
                val state = remember { AppState(applicationContext) }
                OpenDotsApp(state)
            }
        }
    }
}
