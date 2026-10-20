package dev.lorenzo.s200.bridge

import kotlin.test.*

class ModeTest {
    @Test fun fullFlavorKeepsBackgroundScanAndWrites() {
        assertFalse(Mode.presenceOnly)
        assertEquals("dev.lorenzo.s200.bridge", BuildConfig.APPLICATION_ID)
    }
}
