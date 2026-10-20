package dev.lorenzo.s200.bridge

import android.app.PendingIntent
import android.os.SystemClock

const val SCAN_PI_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE

const val WATCHDOG_MINUTES = 15L

val REARM_ACTIONS = setOf(
    "android.intent.action.BOOT_COMPLETED",
    "android.intent.action.MY_PACKAGE_REPLACED",
    "android.bluetooth.device.action.ACL_CONNECTED",
)

fun shouldRearm(action: String?) = action in REARM_ACTIONS

class ArmDebounce(private val windowMs: Long = 10_000, private val now: () -> Long = SystemClock::elapsedRealtime) {
    private var last = 0L
    fun recent(): Boolean = last != 0L && now() - last < windowMs
    fun mark() { last = now() }
}
