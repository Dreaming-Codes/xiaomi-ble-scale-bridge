package dev.lorenzo.s200.bridge

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import dev.lorenzo.s200.OfflineRecord
import dev.lorenzo.s200.S200Parser
import dev.lorenzo.s200.ScaleMac
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import kotlin.math.abs

object Bridge {
    private const val TAG = "S200Bridge"
    private val lock = Mutex()

    suspend fun handle(ctx: Context, address: String, data: ByteArray) = lock.withLock {
        val cfg = Config(ctx)
        val key = cfg.key ?: return@withLock
        val mac = ScaleMac.parse(address) ?: return@withLock
        when (val r = S200Parser.parse(data, mac, key)) {
            is S200Parser.Result.Ignored -> Log.d(TAG, "ignored: ${r.reason}")
            is S200Parser.Result.Ok -> {
                val m = r.m
                val reading = Reading(m.timestampEpochSec, m.weightKg, m.profileId)
                if (!acceptsBroadcast(reading, cfg.profile, cfg.lastTs)) return@withLock
                write(ctx, cfg, listOf(reading))
                ScaleAck.enqueue(ctx)
            }
        }
    }

    suspend fun storeOfflineReturningConfirmable(ctx: Context, records: List<OfflineRecord>): List<Int> = lock.withLock {
        val cfg = Config(ctx)
        val sel = selectOffline(records, cfg.profile, cfg.history)
        if (sel.fresh.isNotEmpty()) write(ctx, cfg, sel.fresh)
        sel.confirm
    }

    private suspend fun write(ctx: Context, cfg: Config, readings: List<Reading>) {
        HealthConnectClient.getOrCreate(ctx).insertRecords(readings.map(::weightRecord))
        val latest = readings.maxBy { it.epochSec }
        val prev = cfg.history.filter { it.profile == latest.profile && it.epochSec < latest.epochSec }.maxByOrNull { it.epochSec }
        readings.forEach(cfg::addReading)
        if (latest.epochSec > cfg.lastTs) cfg.lastTs = latest.epochSec
        Log.i(TAG, "wrote ${latest.kg} kg @ ${Instant.ofEpochSecond(latest.epochSec)} (profile ${latest.profile})" +
            if (readings.size > 1) " +${readings.size - 1} more" else "")
        runCatching { WeighInNotifier.post(ctx, latest, prev) }.onFailure { Log.w(TAG, "notify failed", it) }
    }

    internal fun weightRecord(r: Reading) = WeightRecord(
        time = Instant.ofEpochSecond(r.epochSec), zoneOffset = null, weight = Mass.kilograms(r.kg),
        metadata = Metadata.autoRecorded(
            device = Device(type = Device.TYPE_SCALE, manufacturer = "Xiaomi", model = "MJTZC02YM"),
            clientRecordId = "s200-${r.epochSec}-${r.profile}", clientRecordVersion = 1,
        ),
    )
}

internal fun acceptsBroadcast(r: Reading, profile: Int?, lastTs: Long) = (profile == null || r.profile == profile) && r.epochSec > lastTs

internal data class OfflineSelection(val fresh: List<Reading>, val confirm: List<Int>)

private const val SAME_READING_SEC = 5

internal fun selectOffline(records: List<OfflineRecord>, profile: Int?, history: List<Reading>): OfflineSelection {
    val mine = records.filter { profile == null || it.duid == profile }
    val known = history.map { it.epochSec }
    val fresh = mine
        .filter { it.weightKg > 0 && known.none { k -> abs(k - it.epochSec) <= SAME_READING_SEC } }
        .distinctBy { it.epochSec }
        .map { Reading(it.epochSec, it.weightKg, it.duid) }
    return OfflineSelection(fresh, mine.map { it.idx })
}
