package com.gestureshield

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.google.mediapipe.framework.image.BitmapImageBuilder
import android.graphics.Bitmap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class GestureService : AccessibilityService(), LifecycleOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private var windowManager: WindowManager? = null
    private var overlayView: android.view.View? = null
    private var handLandmarker: HandLandmarker? = null
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var lastGesture = ""
    private var lastGestureTime = 0L
    private val GESTURE_COOLDOWN = 1500L

    private var screenWidth = 1080
    private var screenHeight = 1920

    companion object {
        const val CHANNEL_ID = "GestureShieldChannel"
        const val NOTIF_ID = 1
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        lifecycleRegistry.currentState = Lifecycle.State.STARTED

        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())

        val dm = DisplayMetrics()
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getMetrics(dm)
        screenWidth = dm.widthPixels
        screenHeight = dm.heightPixels

        setupOverlay()
        setupMediaPipe()
        startCamera()
    }

    private fun setupOverlay() {
        val params = WindowManager.LayoutParams(
            120, 120,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.END
        params.x = 16
        params.y = 100

        // Simple floating indicator view
        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.overlay_indicator, null)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager?.addView(overlayView, params)
    }

    private fun setupMediaPipe() {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("hand_landmarker.task")
                .build()

            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setNumHands(1)
                .setMinHandDetectionConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .build()

            handLandmarker = HandLandmarker.createFromOptions(this, options)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val imageAnalyzer = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(320, 240))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImage(imageProxy)
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalyzer)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processImage(imageProxy: ImageProxy) {
        try {
            val bitmap = imageProxy.toBitmap()
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = handLandmarker?.detect(mpImage)
            result?.let { detectGesture(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            imageProxy.close()
        }
    }

    private fun detectGesture(result: HandLandmarkerResult) {
        if (result.landmarks().isEmpty()) return

        val landmarks = result.landmarks()[0]
        val now = System.currentTimeMillis()
        if (now - lastGestureTime < GESTURE_COOLDOWN) return

        // Key landmarks
        val wrist = landmarks[0]
        val thumbTip = landmarks[4]
        val indexTip = landmarks[8]
        val middleTip = landmarks[12]
        val ringTip = landmarks[16]
        val pinkyTip = landmarks[20]
        val indexMcp = landmarks[5]
        val middleMcp = landmarks[9]

        // Finger extended checks
        val indexUp = indexTip.y() < indexMcp.y()
        val middleUp = middleTip.y() < middleMcp.y()
        val ringUp = ringTip.y() < landmarks[13].y()
        val pinkyUp = pinkyTip.y() < landmarks[17].y()
        val thumbUp = thumbTip.y() < wrist.y()

        val gesture = when {
            // ✋ Open hand — Pause/Play
            indexUp && middleUp && ringUp && pinkyUp && thumbUp -> "OPEN_HAND"

            // ☝️ Index only up — Scroll Up
            indexUp && !middleUp && !ringUp && !pinkyUp -> "INDEX_UP"

            // 👇 Index pointing down
            !indexUp && !middleUp && !ringUp && !pinkyUp && !thumbUp -> "FIST_DOWN"

            // ✌️ Peace — Back
            indexUp && middleUp && !ringUp && !pinkyUp -> "PEACE"

            // 👍 Thumb up
            thumbUp && !indexUp && !middleUp -> "THUMB_UP"

            // 👌 OK gesture
            kotlin.math.abs(thumbTip.x() - indexTip.x()) < 0.05f &&
                    kotlin.math.abs(thumbTip.y() - indexTip.y()) < 0.05f -> "OK"

            // ✊ Fist — Screenshot
            !indexUp && !middleUp && !ringUp && !pinkyUp && !thumbUp -> "FIST"

            else -> ""
        }

        if (gesture.isNotEmpty() && gesture != lastGesture) {
            lastGesture = gesture
            lastGestureTime = now
            mainHandler.post { executeGesture(gesture) }
        }
    }

    private fun executeGesture(gesture: String) {
        when (gesture) {
            "INDEX_UP" -> performScroll(true)
            "FIST_DOWN" -> performScroll(false)
            "OPEN_HAND" -> performMediaAction()
            "PEACE" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "THUMB_UP" -> performVolumeChange(true)
            "OK" -> performClick()
            "FIST" -> performScreenshot()
        }
        updateOverlayGesture(gesture)
    }

    private fun performScroll(up: Boolean) {
        val path = Path()
        val startY = if (up) screenHeight * 0.7f else screenHeight * 0.3f
        val endY = if (up) screenHeight * 0.3f else screenHeight * 0.7f
        path.moveTo(screenWidth / 2f, startY)
        path.lineTo(screenWidth / 2f, endY)

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun performClick() {
        val path = Path()
        path.moveTo(screenWidth / 2f, screenHeight / 2f)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun performMediaAction() {
        // Send media play/pause
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
        intent.putExtra(Intent.EXTRA_KEY_EVENT,
            android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        sendBroadcast(intent)
    }

    private fun performVolumeChange(increase: Boolean) {
        val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        if (increase) {
            am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                android.media.AudioManager.ADJUST_RAISE,
                android.media.AudioManager.FLAG_SHOW_UI)
        } else {
            am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                android.media.AudioManager.ADJUST_LOWER,
                android.media.AudioManager.FLAG_SHOW_UI)
        }
    }

    private fun performScreenshot() {
        performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
    }

    private fun updateOverlayGesture(gesture: String) {
        val emoji = when (gesture) {
            "INDEX_UP" -> "☝️"
            "FIST_DOWN" -> "👇"
            "OPEN_HAND" -> "✋"
            "PEACE" -> "✌️"
            "THUMB_UP" -> "👍"
            "OK" -> "👌"
            "FIST" -> "✊"
            else -> "👁️"
        }
        overlayView?.findViewById<android.widget.TextView>(R.id.tvGestureEmoji)?.text = emoji
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "GestureShield", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Gesture detection running"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("✋ GestureShield चालू आहे")
            .setContentText("Gesture detection active — background मध्ये")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        windowManager?.removeView(overlayView)
        handLandmarker?.close()
        cameraExecutor.shutdown()
    }
}
