package dev.lorenzo.s200.bridge

import android.app.PendingIntent
import android.companion.DevicePresenceEvent
import kotlin.test.*

class ScanPolicyTest {
    @Test fun scanPendingIntentIsMutableAndNeverCancelCurrent() {
        assertNotEquals(0, SCAN_PI_FLAGS and PendingIntent.FLAG_MUTABLE)
        assertEquals(0, SCAN_PI_FLAGS and PendingIntent.FLAG_CANCEL_CURRENT)
    }

    @Test fun presenceAppearedRearmsBackgroundScan() {
        assertEquals(PresenceReaction.REARM_AND_BURST, presenceReaction(DevicePresenceEvent.EVENT_BLE_APPEARED))
        assertEquals(PresenceReaction.STOP, presenceReaction(DevicePresenceEvent.EVENT_BLE_DISAPPEARED))
        assertEquals(PresenceReaction.IGNORE, presenceReaction(DevicePresenceEvent.EVENT_BT_CONNECTED))
    }

    @Test fun armStaysUnderFiveStartsPer30s() {
        var t = 1L
        val debounce = ArmDebounce(now = { t })
        val starts = mutableListOf<Long>()
        while (t < 120_000) {
            if (!debounce.recent()) { debounce.mark(); starts += t }
            t += 100
        }
        assertTrue(starts.all { s -> starts.count { it in s until s + 30_000 } < 5 })
    }
}
