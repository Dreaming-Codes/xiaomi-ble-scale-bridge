package dev.lorenzo.s200.bridge

import android.app.PendingIntent
import android.companion.DevicePresenceEvent
import android.os.SystemClock

const val SCAN_PI_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE

enum class PresenceReaction { REARM_AND_BURST, STOP, IGNORE }

fun presenceReaction(event: Int): PresenceReaction = when (event) {
    DevicePresenceEvent.EVENT_BLE_APPEARED -> PresenceReaction.REARM_AND_BURST
    DevicePresenceEvent.EVENT_BLE_DISAPPEARED -> PresenceReaction.STOP
    else -> PresenceReaction.IGNORE
}

class ArmDebounce(private val windowMs: Long = 10_000, private val now: () -> Long = SystemClock::elapsedRealtime) {
    private var last = 0L
    fun recent(): Boolean = last != 0L && now() - last < windowMs
    fun mark() { last = now() }
}
