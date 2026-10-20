package dev.lorenzo.s200.bridge

import android.app.PendingIntent
import androidx.work.PeriodicWorkRequest
import kotlin.test.*

class ScanPolicyTest {
    @Test fun scanPendingIntentIsMutableAndNeverCancelCurrent() {
        assertNotEquals(0, SCAN_PI_FLAGS and PendingIntent.FLAG_MUTABLE)
        assertEquals(0, SCAN_PI_FLAGS and PendingIntent.FLAG_CANCEL_CURRENT)
    }

    @Test fun rearmsOnBootUpdateAndAclConnectOnly() {
        assertTrue(shouldRearm("android.intent.action.BOOT_COMPLETED"))
        assertTrue(shouldRearm("android.intent.action.MY_PACKAGE_REPLACED"))
        assertTrue(shouldRearm("android.bluetooth.device.action.ACL_CONNECTED"))
        assertFalse(shouldRearm("android.bluetooth.adapter.action.STATE_CHANGED"))
        assertFalse(shouldRearm("android.bluetooth.device.action.ACL_DISCONNECTED"))
        assertFalse(shouldRearm(null))
    }

    @Test fun watchdogUsesTheShortestPlainPeriodicInterval() {
        assertEquals(PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS, WATCHDOG_MINUTES * 60_000)
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
