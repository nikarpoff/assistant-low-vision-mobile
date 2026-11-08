package io.github.nikarpoff.alv.view

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.nikarpoff.alv.R
import io.github.nikarpoff.alv.controller.AssistantController

/**
 * Screen for low vision users (MOB-12): black background and the assistant status in large type.
 *
 * The scaffold shows only the status; the answer text, gestures and TalkBack come later.
 */
@Composable
fun AssistantScreen(status: AssistantController.Status, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(status.labelRes()),
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center,
        )
    }
}

@StringRes
private fun AssistantController.Status.labelRes(): Int = when (this) {
    AssistantController.Status.IDLE -> R.string.status_idle
    AssistantController.Status.LISTENING -> R.string.status_listening
    AssistantController.Status.LOOKING -> R.string.status_looking
    AssistantController.Status.SPEAKING -> R.string.status_speaking
    AssistantController.Status.OFFLINE -> R.string.status_offline
}
