package dev.lorenzo.s200.bridge

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import dev.lorenzo.s200.S200Parser
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

object Bridge {
    private const val TAG = "S200Bridge"
    private val lock = Mutex() // receiver + companion service may race

    /** Throws on HC failure so the caller can retry. Idempotent via clientRecordId. */
    suspend fun handle(ctx: Context, mac: String, data: ByteArray) = lock.withLock {
        val cfg = Config(ctx)
        val key = cfg.key ?: return@withLock
        when (val r = S200Parser.parse(data, mac, key)) {
            is S200Parser.Result.Ignored -> Log.d(TAG, "ignored: ${r.reason}")
            is S200Parser.Result.Ok -> {
                val m = r.m
                if (cfg.profile >= 0 && m.profileId != cfg.profile) return@withLock
                if (m.timestampEpochSec <= cfg.lastTs) return@withLock // cheap dedupe
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
                cfg.lastStatus = "wrote ${m.weightKg} kg @ $t (profile ${m.profileId})"
                Log.i(TAG, cfg.lastStatus)
            }
        }
    }
}
