package dev.lorenzo.s200.bridge

import androidx.health.connect.client.records.metadata.Device
import dev.lorenzo.s200.OfflineRecord
import dev.lorenzo.s200.Reading
import java.time.Instant
import kotlin.test.*

class BridgeRulesTest {
    @Test fun broadcastNeedsNewerTimestampAndMatchingProfile() {
        val r = Reading(1_000, 72.35, 1)
        assertTrue(acceptsBroadcast(r, profile = null, lastTs = 999))
        assertTrue(acceptsBroadcast(r, profile = 1, lastTs = 999))
        assertFalse(acceptsBroadcast(r, profile = 2, lastTs = 999))
        assertFalse(acceptsBroadcast(r, profile = null, lastTs = 1_000))
    }

    @Test fun offlineImportSkipsKnownZeroAndOtherProfilesButConfirmsAllOfMine() {
        val records = listOf(
            OfflineRecord(1, 0, 1, 72.35, 0, 2_000),
            OfflineRecord(2, 0, 1, 72.10, 0, 1_003),
            OfflineRecord(3, 0, 1, 0.0, 0, 3_000),
            OfflineRecord(4, 0, 2, 60.00, 0, 4_000),
            OfflineRecord(5, 0, 1, 72.40, 0, 2_000),
        )
        val sel = selectOffline(records, profile = 1, history = listOf(Reading(1_000, 72.0, 1)))
        assertEquals(listOf(Reading(2_000, 72.35, 1)), sel.fresh)
        assertEquals(listOf(1, 2, 3, 5), sel.confirm)
        assertEquals(listOf(1, 2, 3, 4, 5), selectOffline(records, profile = null, history = emptyList()).confirm)
    }

    @Test fun weightRecordIsAnIdempotentScaleUpsert() {
        val rec = Bridge.weightRecord(Reading(1_792_499_000, 72.35, 1))
        assertEquals("s200-1792499000-1", rec.metadata.clientRecordId)
        assertEquals(1L, rec.metadata.clientRecordVersion)
        assertEquals(Instant.ofEpochSecond(1_792_499_000), rec.time)
        assertEquals(72.35, rec.weight.inKilograms, 1e-9)
        assertEquals(Device.TYPE_SCALE, rec.metadata.device?.type)
        assertEquals("MJTZC02YM", rec.metadata.device?.model)
    }

    @Test fun historyFormatStaysReadable() {
        assertEquals(listOf(Reading(1_792_499_000, 72.35, 1), Reading(1_792_400_000, 72.5, 0)), History.decode("1792499000|72.35|1;garbage;1792400000|72.5|0"))
        assertEquals("1792499000|72.35|1;1792400000|72.5|0", History.encode(listOf(Reading(1_792_499_000, 72.35, 1), Reading(1_792_400_000, 72.5, 0))))
        assertEquals(emptyList(), History.decode(""))
    }

    @Test fun historyKeepsTheNewestTwentyNewestFirst() {
        val h = (1L..20L).map { Reading(it * 10, 70.0, 1) }.reversed()
        val after = History.add(h, Reading(105, 71.0, 1))
        assertEquals(20, after.size)
        assertEquals(listOf(200L, 190L), after.take(2).map { it.epochSec })
        assertTrue(Reading(105, 71.0, 1) in after)
        assertFalse(after.any { it.epochSec == 10L })
    }
}
