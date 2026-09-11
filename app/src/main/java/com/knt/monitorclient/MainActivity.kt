package com.knt.monitorclient

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
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
        private const val REQ_PERMS = 101
        private const val REQ_PROJ  = 102
    }

    private var pendingServer = ""
    private var pendingKey = ""

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
            pendingServer = server
            pendingKey = key
            requestPerms()
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
                this, missing.toTypedArray(), REQ_PERMS
            )
        } else {
            askScreenProjection()
        }
    }

    private fun askScreenProjection() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE)
            as MediaProjectionManager
        startActivityForResult(
            mpm.createScreenCaptureIntent(), REQ_PROJ
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) {
            askScreenProjection()
        }
    }

    @Deprecated("deprecated in API 34 but still works on all versions")
    override fun onActivityResult(
        requestCode: Int, resultCode: Int, data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PROJ) return

        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        if (resultCode != Activity.RESULT_OK || data == null) {
            tvStatus.text = "screen permission denied"
            return
        }

        val i = Intent(this, MonitorService::class.java).apply {
            putExtra("server", pendingServer)
            putExtra("key", pendingKey)
            putExtra("device_id", deviceId())
            putExtra(MonitorService.EXTRA_RESULT_CODE, resultCode)
            putExtra(MonitorService.EXTRA_RESULT_DATA, data)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i)
        } else {
            startService(i)
        }
        tvStatus.text = "monitoring started — device id: ${deviceId()}"
    }
}
