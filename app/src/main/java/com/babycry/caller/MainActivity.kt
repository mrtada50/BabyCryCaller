package com.babycry.caller

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var phone: EditText
    private lateinit var status: TextView
    private lateinit var toggle: Button

    private val prefListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> runOnUiThread { refresh() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        phone = findViewById(R.id.phone)
        status = findViewById(R.id.status)
        toggle = findViewById(R.id.toggle)
        phone.setText(Prefs.number(this))

        toggle.setOnClickListener { onToggle() }
        findViewById<Button>(R.id.battery).setOnClickListener {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        findViewById<Button>(R.id.overlay).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
    }

    override fun onResume() {
        super.onResume()
        Prefs.sp(this).registerOnSharedPreferenceChangeListener(prefListener)
        refresh()
    }

    override fun onPause() {
        super.onPause()
        Prefs.sp(this).unregisterOnSharedPreferenceChangeListener(prefListener)
    }

    private fun refresh() {
        val on = ListenService.alive
        toggle.text = if (on) "إيقاف" else "تشغيل"
        toggle.setBackgroundColor(if (on) Color.parseColor("#C62828") else Color.parseColor("#2E7D32"))
        phone.isEnabled = !on
        status.text = if (on) {
            Prefs.status(this)
        } else {
            val reason = Prefs.lastStop(this)
            if (reason.isEmpty()) "متوقف" else "متوقف\n$reason"
        }
    }

    private fun onToggle() {
        if (ListenService.alive) {
            startService(Intent(this, ListenService::class.java).setAction(ListenService.ACTION_STOP))
            return
        }
        val number = phone.text.toString().trim()
        if (number.length < 3) {
            Toast.makeText(this, "اكتب رقم الهاتف أولاً", Toast.LENGTH_SHORT).show()
            return
        }
        Prefs.setNumber(this, number)

        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG
        )
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startListen() else requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) {
            startListen()
        } else {
            Toast.makeText(this, "لازم تسمح بكل الصلاحيات حتى يشتغل", Toast.LENGTH_LONG).show()
        }
    }

    private fun startListen() {
        startForegroundService(Intent(this, ListenService::class.java).setAction(ListenService.ACTION_START))
    }
}
