package dev.lorenzo.s200.bridge

import java.time.ZoneId
import kotlin.test.*

class AckStatusTest {
    private val ams = ZoneId.of("Europe/Amsterdam")
    private val at = 1_792_499_000L

    @Test fun labelsMatchTheStatusChip() {
        assertEquals("Scale synced 14:23", AckStatus.Synced(at, 0).label(ams))
        assertEquals("Scale synced 14:23 · 3 stored readings imported", AckStatus.Synced(at, 3).label(ams))
        assertEquals("Scale sync failed 14:23: LoginException scale rejected login", AckStatus.Failed(at, "LoginException scale rejected login").label(ams))
        assertEquals("needs Bluetooth connect permission", AckStatus.NeedsPermission.label(ams))
    }

    @Test fun survivesStorage() {
        listOf(AckStatus.Synced(at, 3), AckStatus.Failed(at, "RxferException a|b"), AckStatus.NeedsPermission)
            .forEach { assertEquals(it, AckStatus.decode(it.encode())) }
    }

    @Test fun unknownStoredTextReadsAsNeverSynced() {
        assertNull(AckStatus.decode(""))
        assertNull(AckStatus.decode("Scale synced 14:23"))
        assertNull(AckStatus.decode("synced|x|1"))
    }
}
