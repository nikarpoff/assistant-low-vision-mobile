package io.github.nikarpoff.alv.controller

/**
 * The passive mode switch (MOB-14, MOB-9).
 *
 * Off on first launch; the last choice of the user is saved to [store] and restored
 * on the next launch. The switch only keeps the choice: the timer and the phrase
 * announcing the new state belong to the Android layer.
 */
class PassiveModeSetting(private val store: Store) {
    /** Storage of the choice; the Android layer keeps it in DataStore. */
    interface Store {
        /** Saved choice, or `null` when nothing was saved yet (first launch). */
        suspend fun load(): Boolean?

        /** Saves the choice. */
        suspend fun save(enabled: Boolean)
    }

    /** Whether the passive mode is on. Off until [restore]. */
    var enabled: Boolean = false
        private set

    /** Reads the saved choice; off when there is none. */
    suspend fun restore(): Boolean {
        enabled = store.load() ?: false
        return enabled
    }

    /** Turns the mode on or off and saves the choice. */
    suspend fun set(value: Boolean): Boolean {
        enabled = value
        store.save(value)
        return enabled
    }

    /** Switches the mode (swipe up or a voice command) and returns the new state. */
    suspend fun toggle(): Boolean = set(!enabled)
}
