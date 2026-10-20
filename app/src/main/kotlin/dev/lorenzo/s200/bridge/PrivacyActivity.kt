package dev.lorenzo.s200.bridge

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class PrivacyActivity : Activity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(TextView(this).apply {
            setPadding(48, 96, 48, 48)
            text = "Xiaomi BLE Scale Bridge only writes weight readings from your own Xiaomi scale to Health Connect. " +
                "It reads nothing from Health Connect and sends no data off the device."
        })
    }
}
