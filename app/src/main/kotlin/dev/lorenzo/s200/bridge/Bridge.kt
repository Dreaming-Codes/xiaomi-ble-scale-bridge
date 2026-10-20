package dev.lorenzo.s200.bridge

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import dev.lorenzo.s200.S200Parser
import dev.lorenzo.s200.ScaleMac
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

object Bridge {
    private const val TAG = "S200Bridge"
    private val lock = Mutex() // receiver + companion service may race

    /** Throws on HC failure so the caller can retry; clientRecordId makes a retried insert idempotent. */
    suspend fun handle(ctx: Context, address: String, data: ByteArray) = lock.withLock {
        val cfg = Config(ctx)
        val key = cfg.key ?: return@withLock
        val mac = ScaleMac.parse(address) ?: return@withLock
        when (val r = S200Parser.parse(data, mac, key)) {
            is S200Parser.Result.Ignored -> Log.d(TAG, "ignored: ${r.reason}")
            is S200Parser.Result.Ok -> {
                val m = r.m
                if (cfg.profile.let { it != null && it != m.profileId }) return@withLock
                if (m.timestampEpochSec <= cfg.lastTs) return@withLock
                val t = Instant.ofEpochSecond(m.timestampEpochSec)
                val rec = WeightRecord(
                    time = t, zoneOffset = null, weight = Mass.kilograms(m.weightKg),
                    metadata = Metadata.autoRecorded(
                        device = Device(type = Device.TYPE_SCALE, manufacturer = "Xiaomi", model = "MJTZC02YM"),
                        clientRecordId = "s200-${m.timestampEpochSec}-${m.profileId}", clientRecordVersion = 1,
                    ),
                )
                HealthConnectClient.getOrCreate(ctx).insertRecords(listOf(rec))
                cfg.lastTs = m.timestampEpochSec
                val reading = Reading(m.timestampEpochSec, m.weightKg, m.profileId)
                val prev = cfg.history.firstOrNull { it.profile == m.profileId }
                cfg.addReading(reading)
                cfg.lastStatus = "wrote ${m.weightKg} kg @ $t (profile ${m.profileId})"
                Log.i(TAG, cfg.lastStatus)
                runCatching { WeighInNotifier.post(ctx, reading, prev) }.onFailure { Log.w(TAG, "notify failed", it) }
            }
        }
    }
}
