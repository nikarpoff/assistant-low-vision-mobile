package io.github.nikarpoff.alv.view

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.nikarpoff.alv.R
import java.util.Locale

/**
 * Debug panel with the delays (MOB-13): the last E2E (`trigger_to_first_audio_ms`), the phone metrics
 * of `client.metrics`, the gateway stages of `answer.end` (PLT-14), the gesture confirmation
 * (`confirm_ms`, MOB-4) and the start of the fallback speech (`tts_start_ms`, MOB-11).
 * Shown when the setting is on (MOB-9).
 *
 * @param values last value of every record of the debug journal, ms
 */
@Composable
fun DebugPanel(values: Map<String, Double>, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.debug_title),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = if (values.isEmpty()) stringResource(R.string.debug_empty) else format(values),
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

/** Records in a fixed order: E2E first, then the phone, then the gateway; one line, wrapped. */
private fun format(values: Map<String, Double>): String {
    val known = ORDER.filter { it in values }
    val rest = values.keys.filter { it !in ORDER }.sorted()
    return (known + rest).joinToString("  ") { name ->
        "${LABELS[name] ?: name.removeSuffix("_ms")} ${"%.0f".format(Locale.ROOT, values.getValue(name))}"
    }
}

private val ORDER = listOf(
    "trigger_to_first_audio_ms",
    "capture_ms",
    "encode_ms",
    "trigger_to_send_ms",
    "trigger_to_accepted_ms",
    "playback_underruns",
    "confirm_ms",
    "tts_start_ms",
    "server_ttfa_ms",
    "server_total_ms",
)

/** Short labels of the most watched records; the rest show their stage names. */
private val LABELS = mapOf(
    "trigger_to_first_audio_ms" to "E2E",
    "trigger_to_send_ms" to "send",
    "trigger_to_accepted_ms" to "accepted",
    "playback_underruns" to "underruns",
)
