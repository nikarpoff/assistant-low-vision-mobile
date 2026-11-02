package io.github.nikarpoff.alv.view

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.nikarpoff.alv.controller.AssistantController

/** The only activity: hosts the assistant screen. */
class MainActivity : ComponentActivity() {
    private val controller = AssistantController()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Light system bar icons on the black screen regardless of the system theme
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            AlvTheme {
                val status by controller.status.collectAsState()
                AssistantScreen(status = status)
            }
        }
    }
}
