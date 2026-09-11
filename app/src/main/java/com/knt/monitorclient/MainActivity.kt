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
    private var autoStarted = false

    private lateinit var etServer: EditText
    private lateinit var etKey: EditText
    private lateinit var btnStart: Button
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etServer = findViewById(R.id.etServer)
        etKey    = findViewById(R.id.etKey)
        btnStart = findViewById(R.id.btnStart)
        tvStatus = findViewById(R.id.tvStatus)

        etServer.setText(DEFAULT_SERVER)
        etKey.setText(DEFAULT_KEY)

        // manual START button still works
        btnStart.setOnClickListener {
            pendingServer = etServer.text.toString().trim()
            pendingKey = etKey.text.toString().trim()
            if (pendingServer.isEmpty() || pendingKey.isEmpty()) {
                tvStatus.text = "server + key required"
                return@setOnClickListener
            }
            beginFlow()
        }

        // AUTO start on launch (server + key already prefilled)
        if (savedInstanceState == null && !autoStarted) {
            autoStarted = true
            pendingServer = DEFAULT_SERVER
            pendingKey = DEFAULT_KEY
            tvStatus.text = "requesting permissions..."
            // small delay to let UI render
            btnStart.postDelayed({ beginFlow() }, 300)
        }
    }

    private fun deviceId(): String {
        return Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ANDROID_ID
        )?.take(8) ?: "dev-unknown"
    }

    private fun beginFlow() {
        requestPerms()
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

    @Deprecated("still needed to support older Android versions")
    override fun onActivityResult(
        requestCode: Int, resultCode: Int, data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PROJ) return

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
