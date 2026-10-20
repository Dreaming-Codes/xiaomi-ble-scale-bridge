package dev.lorenzo.s200.bridge

import android.content.Context
import android.content.pm.PackageManager
import dev.lorenzo.s200.BindKey
import dev.lorenzo.s200.MiToken
import dev.lorenzo.s200.Reading
import dev.lorenzo.s200.ScaleMac

fun Context.granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

internal object History {
    private const val KEEP = 20

    fun encode(rs: List<Reading>) = rs.joinToString(";") { "${it.epochSec}|${it.kg}|${it.profile}" }

    fun decode(s: String): List<Reading> = s.split(";").mapNotNull { e ->
        e.split("|").takeIf { it.size == 3 }?.let { (a, b, c) -> runCatching { Reading(a.toLong(), b.toDouble(), c.toInt()) }.getOrNull() }
    }

    fun add(history: List<Reading>, r: Reading) = (listOf(r) + history).sortedByDescending { it.epochSec }.take(KEEP)
}

class Config(ctx: Context) {
    private val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    var keyHex: String get() = p.getString("key", "")!!; set(v) = p.edit().putString("key", v).apply()
    var macText: String get() = p.getString("mac", "")!!; set(v) = p.edit().putString("mac", v).apply()
    var profileSlot: Int get() = p.getInt("profile", -1); set(v) = p.edit().putInt("profile", v).apply()
    var lastTs: Long get() = p.getLong("lastTs", 0); set(v) = p.edit().putLong("lastTs", v).apply()
    var tokenHex: String get() = p.getString("token", "")!!; set(v) = p.edit().putString("token", v).apply()
    var ack: AckStatus? get() = AckStatus.decode(p.getString("ackStatus", "")!!); set(v) = p.edit().putString("ackStatus", v?.encode()).apply()

    val key: BindKey? get() = BindKey.parse(keyHex)
    val mac: ScaleMac? get() = ScaleMac.parse(macText)
    val token: MiToken? get() = MiToken.parse(tokenHex)
    val profile: Int? get() = profileSlot.takeIf { it >= 0 }
    val configured get() = key != null && mac != null

    val history: List<Reading> get() = History.decode(p.getString("hist", "")!!)
    fun addReading(r: Reading) = p.edit().putString("hist", History.encode(History.add(history, r))).apply()
}
