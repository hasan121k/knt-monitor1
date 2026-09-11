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
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.util.Size
import android.view.WindowManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MonitorService : Service() {

    companion object {
        private const val TAG = "MonitorService"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    // camera
    private var camera: CameraDevice? = null
    private var camSession: CameraCaptureSession? = null
    private var camReader: ImageReader? = null
    private var lensFacing = CameraCharacteristics.LENS_FACING_BACK
    private var curMode = "camera"

    // screen
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var scrReader: ImageReader? = null
    private var screenW = 0
    private var screenH = 0
    private var screenDpi = 0

    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null

    private var ws: WebSocket? = null
    private var serverUrl = ""
    private var clientKey = ""
    private var deviceId = ""
    private var resultCode = 0
    private var resultData: Intent? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?, flags: Int, startId: Int
    ): Int {
        serverUrl = intent?.getStringExtra("server") ?: return START_NOT_STICKY
        clientKey = intent?.getStringExtra("key") ?: return START_NOT_STICKY
        deviceId  = intent?.getStringExtra("device_id") ?: "unknown"
        resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

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
        val n = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, chId)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        n.setContentTitle("KNT Monitor")
            .setContentText("monitoring active")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                    or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    // ---------- WebSocket ----------

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
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    when {
                        text == "mode:camera" -> switchMode("camera")
                        text == "mode:screen" -> switchMode("screen")
                        text == "flip" -> flipCamera()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "cmd error: $e")
                }
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

    private fun notifyMode(mode: String) {
        curMode = mode
        try {
            val j = JSONObject()
            j.put("type", "mode")
            j.put("mode", mode)
            ws?.send(j.toString())
        } catch (_: Exception) {}
    }

    private fun switchMode(mode: String) {
        bgHandler?.post {
            if (mode == curMode) {
                notifyMode(mode)
                return@post
            }
            if (mode == "screen") {
                stopCamera()
                startScreen()
            } else {
                stopScreen()
                openCamera()
            }
            notifyMode(mode)
        }
    }

    // ---------- Camera ----------

    private fun openCamera() {
        try {
            val mgr = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val camId = mgr.cameraIdList.firstOrNull { id ->
                val ch = mgr.getCameraCharacteristics(id)
                ch.get(CameraCharacteristics.LENS_FACING) == lensFacing
            } ?: mgr.cameraIdList.firstOrNull() ?: return

            val ch = mgr.getCameraCharacteristics(camId)
            val map = ch.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            ) ?: return
            val size: Size = map.getOutputSizes(ImageFormat.JPEG)
                ?.filter { it.width in 640..1280 }
                ?.minByOrNull { kotlin.math.abs(it.width - 800) }
                ?: Size(640, 480)

            camReader?.close()
            camReader = ImageReader.newInstance(
                size.width, size.height, ImageFormat.JPEG, 2
            )
            camReader!!.setOnImageAvailableListener({ r ->
                val img = r.acquireLatestImage()
                    ?: return@setOnImageAvailableListener
                try {
                    val buf = img.planes[0].buffer
                    val bytes = ByteArray(buf.remaining())
                    buf.get(bytes)
                    ws?.send(ByteString.of(*bytes))
                } catch (e: Exception) {
                    Log.e(TAG, "frame err: $e")
                } finally {
                    img.close()
                }
            }, bgHandler)

            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.CAMERA
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                mgr.openCamera(camId,
                    object : CameraDevice.StateCallback() {
                        override fun onOpened(c: CameraDevice) {
                            camera = c
                            createCamSession()
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

    private fun createCamSession() {
        val cam = camera ?: return
        val surf = camReader?.surface ?: return
        try {
            cam.createCaptureSession(
                listOf(surf),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        camSession = s
                        try {
                            val req = cam.createCaptureRequest(
                                CameraDevice.TEMPLATE_PREVIEW
                            ).apply {
                                addTarget(surf)
                            }.build()
                            s.setRepeatingRequest(req, null, bgHandler)
                        } catch (e: Exception) {
                            Log.e(TAG, "req fail: $e")
                        }
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) {}
                }, bgHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "session fail: $e")
        }
    }

    private fun flipCamera() {
        lensFacing = if (lensFacing ==
            CameraCharacteristics.LENS_FACING_BACK
        ) CameraCharacteristics.LENS_FACING_FRONT
        else CameraCharacteristics.LENS_FACING_BACK
        bgHandler?.post {
            stopCamera()
            openCamera()
        }
    }

    private fun stopCamera() {
        try { camSession?.close() } catch (_: Exception) {}
        try { camera?.close() } catch (_: Exception) {}
        try { camReader?.close() } catch (_: Exception) {}
        camSession = null; camera = null; camReader = null
    }

    // ---------- Screen ----------

    private fun startScreen() {
        if (resultData == null || resultCode == 0) {
            Log.e(TAG, "no screen permission data")
            // fallback to camera
            bgHandler?.post { openCamera(); notifyMode("camera") }
            return
        }
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
            projection = mpm.getMediaProjection(resultCode, resultData!!)

            val wm = getSystemService(Context.WINDOW_SERVICE)
                as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            screenW = metrics.widthPixels
            screenH = metrics.heightPixels
            screenDpi = metrics.densityDpi

            scrReader?.close()
            scrReader = ImageReader.newInstance(
                screenW, screenH, ImageFormat.JPEG, 2
            )
            scrReader!!.setOnImageAvailableListener({ r ->
                val img = r.acquireLatestImage()
                    ?: return@setOnImageAvailableListener
                try {
                    val buf = img.planes[0].buffer
                    val bytes = ByteArray(buf.remaining())
                    buf.get(bytes)
                    ws?.send(ByteString.of(*bytes))
                } catch (e: Exception) {
                    Log.e(TAG, "scr err: $e")
                } finally {
                    img.close()
                }
            }, bgHandler)

            virtualDisplay = projection?.createVirtualDisplay(
                "knt-screen",
                screenW, screenH, screenDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                scrReader!!.surface, null, bgHandler
            )
            Log.i(TAG, "screen started $screenW x $screenH")
        } catch (e: Exception) {
            Log.e(TAG, "screen fail: $e")
            bgHandler?.post { openCamera(); notifyMode("camera") }
        }
    }

    private fun stopScreen() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { scrReader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        virtualDisplay = null; scrReader = null; projection = null
    }

    override fun onDestroy() {
        stopCamera()
        stopScreen()
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        bgThread?.quitSafely()
        super.onDestroy()
    }
}
