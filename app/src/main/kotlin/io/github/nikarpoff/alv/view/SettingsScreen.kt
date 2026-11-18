package io.github.nikarpoff.alv.view

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.nikarpoff.alv.R
import io.github.nikarpoff.alv.model.Settings

/**
 * Settings screen (MOB-9): server address and device token, passive mode, trial participant code
 * and the debug panel. Every field has a visible label, which TalkBack reads; switches are whole rows.
 * Text is never smaller than 28 sp (MOB-12).
 *
 * @param onSave called with the edited settings
 * @param onCancel called on «Отмена» and on the system back
 */
@Composable
fun SettingsScreen(
    settings: Settings,
    onSave: (Settings) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var serverUrl by rememberSaveable { mutableStateOf(settings.serverUrl) }
    var deviceToken by rememberSaveable { mutableStateOf(settings.deviceToken) }
    var passiveMode by rememberSaveable { mutableStateOf(settings.passiveMode) }
    var label by rememberSaveable { mutableStateOf(settings.participantLabel) }
    var debugPanel by rememberSaveable { mutableStateOf(settings.showDebugPanel) }

    BackHandler(onBack = onCancel)
    ProvideTextStyle(MaterialTheme.typography.bodyLarge) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_title),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.semantics { heading() },
            )
            Field(R.string.settings_server_url, serverUrl, KeyboardType.Uri) { serverUrl = it }
            Field(R.string.settings_device_token, deviceToken, KeyboardType.Password, secret = true) { deviceToken = it }
            Toggle(R.string.settings_passive_mode, passiveMode) { passiveMode = it }
            Field(R.string.settings_label, label, KeyboardType.Ascii) { label = it }
            Toggle(R.string.settings_debug_panel, debugPanel) { debugPanel = it }
            Button(
                onClick = {
                    onSave(
                        settings.copy(
                            serverUrl = serverUrl.trim(),
                            deviceToken = deviceToken.trim(),
                            passiveMode = passiveMode,
                            participantLabel = label.trim(),
                            showDebugPanel = debugPanel,
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_save))
            }
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_cancel), color = MaterialTheme.colorScheme.onBackground)
            }
        }
    }
}

@Composable
private fun Field(
    @StringRes label: Int,
    value: String,
    keyboard: KeyboardType,
    secret: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Toggle(@StringRes label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(label),
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = null)
    }
}
