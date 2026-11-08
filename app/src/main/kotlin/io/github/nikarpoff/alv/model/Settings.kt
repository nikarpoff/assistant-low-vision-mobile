package io.github.nikarpoff.alv.model

/**
 * Settings of the user and the device (KM-1, MOB-9).
 *
 * The defaults are the state on first launch: the passive mode is off (MOB-14).
 * Build-time defaults of the server address and the device token are passed in
 * by the Android layer, so the class stays free of `android.*`.
 *
 * @property deviceId device pseudonym for `hello`: a random UUID assigned on first launch, empty until then
 * @property serverUrl gateway address, `wss://...`
 * @property deviceToken device token sent as `Authorization: Bearer`
 * @property passiveMode passive mode, the last choice of the user
 * @property participantLabel trial participant code sent as `label` (EXP-6), empty outside trials
 * @property showDebugPanel show the debug panel with latencies
 */
data class Settings(
    val deviceId: String = "",
    val serverUrl: String = "",
    val deviceToken: String = "",
    val passiveMode: Boolean = false,
    val participantLabel: String = "",
    val showDebugPanel: Boolean = false,
) {
    /** Same as the generated one, but the device token is masked: settings may end up in a log. */
    override fun toString(): String =
        "Settings(deviceId=$deviceId, serverUrl=$serverUrl, deviceToken=${if (deviceToken.isEmpty()) "" else "***"}, " +
            "passiveMode=$passiveMode, participantLabel=$participantLabel, showDebugPanel=$showDebugPanel)"
}
