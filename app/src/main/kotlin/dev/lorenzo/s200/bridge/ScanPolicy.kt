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

fun presenceEventName(event: Int): String = when (event) {
    DevicePresenceEvent.EVENT_BLE_APPEARED -> "BLE appeared"
    DevicePresenceEvent.EVENT_BLE_DISAPPEARED -> "BLE disappeared"
    DevicePresenceEvent.EVENT_BT_CONNECTED -> "BT connected"
    DevicePresenceEvent.EVENT_BT_DISCONNECTED -> "BT disconnected"
    else -> "event $event"
}

object Mode {
    val presenceOnly: Boolean get() = BuildConfig.PRESENCE_ONLY
}

class ArmDebounce(private val windowMs: Long = 10_000, private val now: () -> Long = SystemClock::elapsedRealtime) {
    private var last = 0L
    fun recent(): Boolean = last != 0L && now() - last < windowMs
    fun mark() { last = now() }
}
