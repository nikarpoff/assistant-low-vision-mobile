package io.github.nikarpoff.alv.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {
    @Test
    fun passiveModeIsOffByDefault() {
        assertFalse(Settings().passiveMode)
    }

    @Test
    fun defaultsAreEmptyUntilAssigned() {
        val settings = Settings()

        assertEquals("", settings.deviceId)
        assertEquals("", settings.serverUrl)
        assertEquals("", settings.deviceToken)
        assertEquals("", settings.participantLabel)
        assertFalse(settings.showDebugPanel)
    }

    @Test
    fun toStringMasksDeviceToken() {
        val text = Settings(serverUrl = "wss://alv.example", deviceToken = "secret-token").toString()

        assertFalse(text.contains("secret-token"))
        assertTrue(text.contains("wss://alv.example"))
    }
}
