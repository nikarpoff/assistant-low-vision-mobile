package io.github.nikarpoff.alv.view

import android.view.MotionEvent
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.nikarpoff.alv.R
import io.github.nikarpoff.alv.controller.AssistantController

/**
 * Screen for low vision users (MOB-12, MOB-3, MOB-10).
 *
 * Black background, the status and the text of the last answer in large white and yellow type.
 * The whole screen is the gesture surface: raw touches go to the controller, which recognizes them.
 * For TalkBack the surface is one element «Ассистент. Двойное касание - описать сцену» with the
 * actions «Задать вопрос», «Повторить», «Пассивный режим», «Стоп» and «Настройки»; the texts
 * inside are hidden from TalkBack, so it never reads the spoken answer again.
 *
 * @param debug values of the debug panel, `null` when the panel is hidden
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AssistantScreen(
    screen: AssistantController.Screen,
    debug: Map<String, Double>?,
    controller: AssistantController,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.surface_description)
    val actions = listOf(
        CustomAccessibilityAction(stringResource(R.string.action_ask)) { controller.onAccessibilityAsk(); true },
        CustomAccessibilityAction(stringResource(R.string.action_repeat)) { controller.onAccessibilityRepeat(); true },
        CustomAccessibilityAction(stringResource(R.string.action_passive)) { controller.onAccessibilityPassive(); true },
        CustomAccessibilityAction(stringResource(R.string.action_stop)) { controller.onAccessibilityStop(); true },
        CustomAccessibilityAction(stringResource(R.string.action_settings)) { onSettings(); true },
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pointerInteropFilter { event -> onTouch(controller, event) }
            .clearAndSetSemantics {
                contentDescription = description
                onClick { controller.onAccessibilityDescribe(); true }
                customActions = actions
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(
                text = stringResource(screen.status.labelRes()),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            if (screen.passiveMode) {
                Text(
                    text = stringResource(R.string.phrase_passive_on),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                text = screen.answerText,
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            if (debug != null) DebugPanel(debug)
        }
    }
}

/** Passes the first finger to the controller; times are `MotionEvent.getEventTime`, the monotonic clock. */
private fun onTouch(controller: AssistantController, event: MotionEvent): Boolean {
    when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> controller.onTouchDown(event.x, event.y, event.eventTime)
        MotionEvent.ACTION_MOVE -> controller.onTouchMove(event.x, event.y, event.eventTime)
        MotionEvent.ACTION_UP -> controller.onTouchUp(event.x, event.y, event.eventTime)
        MotionEvent.ACTION_CANCEL -> controller.onTouchCancel()
    }
    return true
}

@StringRes
private fun AssistantController.Status.labelRes(): Int = when (this) {
    AssistantController.Status.IDLE -> R.string.status_idle
    AssistantController.Status.LISTENING -> R.string.status_listening
    AssistantController.Status.LOOKING -> R.string.status_looking
    AssistantController.Status.SPEAKING -> R.string.status_speaking
    AssistantController.Status.OFFLINE -> R.string.status_offline
}
