package io.github.nikarpoff.alv.controller

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PassiveModeSettingTest {
    /** Storage that outlives the switch, as DataStore outlives the app process. */
    private class MemoryStore : PassiveModeSetting.Store {
        var value: Boolean? = null
        var saves = 0

        override suspend fun load(): Boolean? = value

        override suspend fun save(enabled: Boolean) {
            value = enabled
            saves++
        }
    }

    private val store = MemoryStore()

    @Test
    fun offOnFirstLaunch() = runBlocking {
        val setting = PassiveModeSetting(store)

        assertFalse(setting.enabled)
        assertFalse(setting.restore())
        assertNull(store.value)
    }

    @Test
    fun choiceIsSavedAndRestoredOnNextLaunch() = runBlocking {
        val first = PassiveModeSetting(store)
        first.restore()
        assertTrue(first.toggle())
        assertEquals(true, store.value)

        val next = PassiveModeSetting(store)

        assertTrue(next.restore())
        assertTrue(next.enabled)
    }

    @Test
    fun turningOffIsRestoredToo() = runBlocking {
        store.value = true
        val first = PassiveModeSetting(store)
        assertTrue(first.restore())
        assertFalse(first.toggle())

        assertFalse(PassiveModeSetting(store).restore())
    }

    @Test
    fun setSavesEveryChoice() = runBlocking {
        val setting = PassiveModeSetting(store)
        setting.restore()

        setting.set(true)
        setting.set(false)

        assertEquals(2, store.saves)
        assertEquals(false, store.value)
    }
}
