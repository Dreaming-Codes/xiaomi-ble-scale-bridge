package dev.lorenzo.s200.bridge

import kotlin.test.*

class ModeTest {
    @Test fun presenceFlavorIsSeparateAndPresenceOnly() {
        assertTrue(Mode.presenceOnly)
        assertEquals("dev.lorenzo.s200.bridge.presence", BuildConfig.APPLICATION_ID)
        assertTrue(BuildConfig.VERSION_NAME.endsWith("-presence"))
    }
}
