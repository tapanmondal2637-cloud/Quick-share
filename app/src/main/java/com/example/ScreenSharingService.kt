package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class ScreenSharingService : Service() {

    companion object {
        private const val TAG = "ScreenSharingService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "screen_share_channel"
        
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_ROOM_CODE = "extra_room_code"
        
        const val ACTION_STOP_SHARING = "com.example.STOP_SHARING"
        
        // Dynamic event callbacks
        var isSharingRunning = false
        var roomCode = ""
        var activeViewerCount = 0
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // infinite for websockets
        .build()
        
    private var feedbackWebSocket: WebSocket? = null
    private var lastFrameTime = 0L
    private val frameIntervalMs = 250 // rate limits host to 4 FPS, perfect for documents, presentations, web views

    override fun onCreate() {
        super.onCreate()
        Log.e(TAG, "ScreenSharingService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SHARING) {
            stopSharing()
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        }
        val code = intent?.getStringExtra(EXTRA_ROOM_CODE) ?: ""

        if (resultCode != -1 && resultData != null && code.isNotEmpty()) {
            roomCode = code
            isSharingRunning = true
            createNotificationChannel()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
            
            startBackgroundThread()
            setupFeedbackWebSocket(code)
            setupScreenCapture(resultCode, resultData)
            
            // Broadcast initial active state
            scope.launch {
                sendSignalingEvent(roomCode, "host_joined", JSONObject())
            }
        } else {
            Log.e(TAG, "Invalid starter parameters for ScreenSharingService: resultCode=$resultCode, hasResultData=${resultData != null}, code=$code")
            // Always call startForeground first to prevent ForegroundServiceDidNotStartInTimeException, then stop
            createNotificationChannel()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, createNotification(), 0)
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
            stopSharing()
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("ScreenCaptureBackground").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun setupScreenCapture(resultCode: Int, resultData: Intent) {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        
        // Downscale capture window for maximum low-latency performance and beautiful dynamic sharing
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val scale = 0.4f // Optimal downscale multiplier for web screens
        val captureWidth = (screenWidth * scale).toInt()
        val captureHeight = (screenHeight * scale).toInt()
        val density = metrics.densityDpi

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, resultData)

        imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)
        
        mediaProjection?.createVirtualDisplay(
            "ScreenShareDisplay",
            captureWidth,
            captureHeight,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            object : VirtualDisplay.Callback() {
                override fun onStopped() {
                    Log.e(TAG, "Virtual display stopped")
                }
            },
            backgroundHandler
        ).also { virtualDisplay = it }

        imageReader?.setOnImageAvailableListener({ reader ->
            val now = System.currentTimeMillis()
            if (now - lastFrameTime >= frameIntervalMs) {
                lastFrameTime = now
                processAndSendFrame(reader)
            } else {
                // Instantly consume and discard to avoid buffering frames
                val img = reader.acquireLatestImage()
                img?.close()
            }
        }, backgroundHandler)
    }

    private fun processAndSendFrame(reader: ImageReader) {
        var image: Image? = null
        try {
            image = reader.acquireLatestImage() ?: return
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val width = image.width
            val height = image.height

            // Account for any potential row padding in buffer
            val bitmap = Bitmap.createBitmap(
                width + (rowStride - pixelStride * width) / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            val outStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 45, outStream) // Balanced high compaction
            val jpegBytes = outStream.toByteArray()
            val base64Frame = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)

            // Release early
            bitmap.recycle()

            scope.launch {
                val payload = JSONObject().apply {
                    put("type", "frame")
                    put("base64", base64Frame)
                }
                sendSignalingEvent(roomCode, "host_frame", payload)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing frame", e)
        } finally {
            image?.close()
        }
    }

    private fun setupFeedbackWebSocket(code: String) {
        // Simple websocket feed reader for viewer inputs (Join/Leave)
        val url = "wss://ntfy.sh/qs_${code}_feedback/ws"
        val request = Request.Builder().url(url).build()

        feedbackWebSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val root = JSONObject(text)
                    val rawMessage = root.optString("message")
                    if (!rawMessage.isNullOrEmpty()) {
                        val payload = JSONObject(rawMessage)
                        when (payload.optString("type")) {
                            "client_joined" -> {
                                activeViewerCount = 1
                                updateNotification()
                                Log.e(TAG, "Viewer joined room: $code")
                            }
                            "client_left" -> {
                                activeViewerCount = 0
                                updateNotification()
                                Log.e(TAG, "Viewer left room: $code")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing viewer WebSocket messaging feed", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Feedback websocket failed, re-establishing", t)
                if (isSharingRunning) {
                    backgroundHandler?.postDelayed({ setupFeedbackWebSocket(code) }, 4000)
                }
            }
        })
    }

    private suspend fun sendSignalingEvent(code: String, endpoint: String, payload: JSONObject) {
        try {
            payload.put("ts", System.currentTimeMillis())
            val request = Request.Builder()
                .url("https://ntfy.sh/qs_${code}_host")
                .post(payload.toString().toRequestBody())
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed posting screen stream frame: ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network sharing stream failure", e)
        }
    }

    private fun stopSharing() {
        Log.e(TAG, "Stopping screen sharing...")
        isSharingRunning = false
        roomCode = ""
        activeViewerCount = 0
        
        scope.launch {
            val endPayload = JSONObject().apply { put("type", "host_left") }
            sendSignalingEvent(roomCode, "host_left", endPayload)
            feedbackWebSocket?.close(1000, "Host stopped sharing")
            scope.cancel()
        }

        try {
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error disposing hardware layers", e)
        }

        backgroundThread?.quitSafely()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Sharing Notification Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Shows live connection status for QuickShare Screen sharing."
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, ScreenSharingService::class.java).apply {
            action = ACTION_STOP_SHARING
        }
        val pStopIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val mainIntent = Intent(this, MainActivity::class.java)
        val pMainIntent = PendingIntent.getActivity(
            this, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = if (activeViewerCount > 0) "Status: Connected (Viewer active)" else "Status: Waiting for viewer (Code $roomCode)"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("QuickShare Screen Sharing")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pMainIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Sharing", pStopIntent)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopSharing()
        super.onDestroy()
    }
}
