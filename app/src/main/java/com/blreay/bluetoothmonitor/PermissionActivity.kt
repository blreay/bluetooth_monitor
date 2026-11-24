package com.blreay.bluetoothmonitor

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Unregistered compatibility stub. It never requests permissions or starts a reset. */
class PermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
