package com.knt.monitorclient

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.util.Size
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

class MonitorService : Service() {

    private val TAG = "MonitorService"
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null

    private var ws: WebSocket? = null
    private var serverUrl = ""
    private var clientKey = ""
    private var deviceId = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?, flags: Int, startId: Int
    ): Int {
        serverUrl = intent?.getStringExtra("server") ?: return START_NOT_STICKY
        clientKey = intent?.getStringExtra("key") ?: return START_NOT_STICKY
        deviceId  = intent?.getStringExtra("device_id") ?: "unknown"

        startForegroundNotif()
        bgThread = HandlerThread("cam").also { it.start() }
        bgHandler = Handler(bgThread!!.looper)
        connectWs()
        openCamera()
        return START_STICKY
    }

    private fun startForegroundNotif() {
        val chId = "knt_monitor"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
            if (nm.getNotificationChannel(chId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        chId, "Monitor",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }
        val n = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, chId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
            .setContentTitle("KNT Monitor")
            .setContentText("monitoring active")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(1, n)
        }
    }

    private fun connectWs() {
        val url = serverUrl
            .replace("http://", "ws://")
            .replace("https://", "wss://") +
            "/ws/client/$deviceId?key=$clientKey"

        val req = Request.Builder().url(url).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "ws open")
            }
            override fun onFailure(
                webSocket: WebSocket, t: Throwable, response: Response?
            ) {
                Log.e(TAG, "ws fail: ${t.message}")
                bgHandler?.postDelayed({ connectWs() }, 3000)
            }
            override fun onClosed(
                webSocket: WebSocket, code: Int, reason: String
            ) {
                bgHandler?.postDelayed({ connectWs() }, 3000)
            }
        })
    }

    private fun openCamera() {
        val mgr = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val camId = mgr.cameraIdList.firstOrNull { id ->
            val ch = mgr.getCameraCharacteristics(id)
            ch.get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        } ?: mgr.cameraIdList.firstOrNull() ?: return

        val ch = mgr.getCameraCharacteristics(camId)
        val map = ch.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        ) ?: return
        val size: Size = map.getOutputSizes(ImageFormat.JPEG)
            ?.filter { it.width in 640..1280 }
            ?.minByOrNull { kotlin.math.abs(it.width - 800) }
            ?: Size(640, 480)

        reader = ImageReader.newInstance(
            size.width, size.height, ImageFormat.JPEG, 2
        )
        reader!!.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage()
                ?: return@setOnImageAvailableListener
            try {
                val buf = img.planes[0].buffer
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                ws?.send(ByteString.of(*bytes))
            } catch (e: Exception) {
                Log.e(TAG, "frame error: $e")
            } finally {
                img.close()
            }
        }, bgHandler)

        try {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.CAMERA
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                mgr.openCamera(camId,
                    object : CameraDevice.StateCallback() {
                        override fun onOpened(c: CameraDevice) {
                            camera = c
                            createSession()
                        }
                        override fun onDisconnected(c: CameraDevice) {
                            c.close()
                        }
                        override fun onError(c: CameraDevice, e: Int) {
                            c.close()
                        }
                    }, bgHandler)
            }
        } catch (e: Exception) {
            Log.e(TAG, "openCamera fail: $e")
        }
    }

    private fun createSession() {
        val cam = camera ?: return
        val surf = reader?.surface ?: return
        try {
            cam.createCaptureSession(
                listOf(surf),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        val req = cam.createCaptureRequest(
                            CameraDevice.TEMPLATE_PREVIEW
                        ).apply {
                            addTarget(surf)
                        }.build()
                        s.setRepeatingRequest(req, null, bgHandler)
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) {}
                }, bgHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "session fail: $e")
        }
    }

    override fun onDestroy() {
        try { session?.close() } catch (_: Exception) {}
        try { camera?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        bgThread?.quitSafely()
        super.onDestroy()
    }
}
