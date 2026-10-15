package dev.lorenzo.s200.bridge

import android.content.Context

/** Bind key stored in app-private prefs (allowBackup=false). */
class Config(ctx: Context) {
    private val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    var keyHex: String get() = p.getString("key", "")!!; set(v) = p.edit().putString("key", v).apply()
    var mac: String get() = p.getString("mac", "")!!; set(v) = p.edit().putString("mac", v).apply()
    var profile: Int get() = p.getInt("profile", -1); set(v) = p.edit().putInt("profile", v).apply() // -1 = any
    var associationId: Int get() = p.getInt("assoc", -1); set(v) = p.edit().putInt("assoc", v).apply()
    var lastTs: Long get() = p.getLong("lastTs", 0); set(v) = p.edit().putLong("lastTs", v).apply()
    var lastStatus: String get() = p.getString("status", "-")!!; set(v) = p.edit().putString("status", v).apply()
    /** Recent readings as "epochSec|kg|profile", newest first, max 20. */
    var history: List<String>
        get() = p.getString("hist", "")!!.split(";").filter { it.isNotBlank() }
        set(v) = p.edit().putString("hist", v.take(20).joinToString(";")).apply()
    fun addReading(ts: Long, kg: Double, profile: Int) { history = listOf("$ts|$kg|$profile") + history }
    val key: ByteArray? get() = keyHex.takeIf { it.matches(Regex("[0-9a-fA-F]{32}")) }?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()
    val configured get() = key != null && mac.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}"))
}
