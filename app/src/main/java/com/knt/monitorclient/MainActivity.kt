package com.knt.monitorclient

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        const val DEFAULT_SERVER = "http://192.168.1.100:8080"
        const val DEFAULT_KEY    = "knt-crow-9002"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val etServer = findViewById<EditText>(R.id.etServer)
        val etKey    = findViewById<EditText>(R.id.etKey)
        val btnStart = findViewById<Button>(R.id.btnStart)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)

        etServer.setText(DEFAULT_SERVER)
        etKey.setText(DEFAULT_KEY)

        btnStart.setOnClickListener {
            val server = etServer.text.toString().trim()
            val key = etKey.text.toString().trim()
            if (server.isEmpty() || key.isEmpty()) {
                tvStatus.text = "server + key required"
                return@setOnClickListener
            }
            requestPerms()
            val i = Intent(this, MonitorService::class.java).apply {
                putExtra("server", server)
                putExtra("key", key)
                putExtra("device_id", deviceId())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i)
            } else {
                startService(i)
            }
            tvStatus.text = "monitoring started — device id: ${deviceId()}"
        }
    }

    private fun deviceId(): String {
        return Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ANDROID_ID
        )?.take(8) ?: "dev-unknown"
    }

    private fun requestPerms() {
        val perms = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) !=
                PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this, missing.toTypedArray(), 101
            )
        }
    }
}
