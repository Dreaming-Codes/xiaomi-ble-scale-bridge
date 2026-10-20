package dev.lorenzo.s200.bridge

import android.companion.DevicePresenceEvent
import java.time.ZoneId
import java.util.Locale
import kotlin.test.*

class PresenceTestFormatTest {
    private val ams = ZoneId.of("Europe/Amsterdam")

    @Test fun notificationTextNamesPathAndTimes() {
        val appeared = 1792519265000L
        val received = appeared + 4_000
        assertEquals("Presence test: 60.55 kg", presenceTestTitle(60.55, Locale.ROOT))
        assertEquals("via presence burst, appeared 20:01:05, received 20:01:09", presenceTestDetail("presence burst", appeared, received, ams))
        assertEquals("via retry, appeared never, received 20:01:09", presenceTestDetail("retry", null, received, ams))
    }

    @Test fun presenceLogRoundTrips() {
        val e = PresenceEntry(1792519265000L, "BLE appeared")
        assertEquals(e, PresenceEntry.decode(e.encode()))
        assertEquals("1|a b c", PresenceEntry(1, "a|b;c").encode())
        assertNull(PresenceEntry.decode("garbage"))
    }

    @Test fun presenceEventNames() {
        assertEquals(PRESENCE_APPEARED, presenceEventName(DevicePresenceEvent.EVENT_BLE_APPEARED))
        assertEquals("BLE disappeared", presenceEventName(DevicePresenceEvent.EVENT_BLE_DISAPPEARED))
        assertEquals("event 99", presenceEventName(99))
    }
}
