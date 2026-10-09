package radar.robot

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import androidx.core.content.ContextCompat
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Streams the back camera as IP-camera-ish JPEG frames to the Node.js server:
 * POST /cam/<robot>   (image/jpeg body)
 * The web app shows it live via /cam/<robot>/mjpeg.
 */
class CameraController(
    private val activity: Activity,
    private val textureView: TextureView,
    private val onLog: (String) -> Unit,
    private val onStatus: (String) -> Unit
) {

    @Volatile private var enabled = false
    @Volatile private var serverUrl = ""
    @Volatile private var intervalMs = 1000L

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private val uploader: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile private var lastUpload = 0L

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            if (enabled) openCamera()
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture) = false

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            handler?.let { createSession(camera) }
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            onStatus("camera error $error")
            camera.close()
            cameraDevice = null
        }
    }

    private val onImageAvailable = ImageReader.OnImageAvailableListener { reader ->
        val now = System.currentTimeMillis()
        if (now - lastUpload < intervalMs) {
            try { reader.acquireLatestImage()?.close() } catch (e: Exception) {}
            return@OnImageAvailableListener
        }
        lastUpload = now
        try {
            val image = reader.acquireLatestImage() ?: return@OnImageAvailableListener
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            image.close()
            if (serverUrl.isNotEmpty()) upload(bytes)
        } catch (e: Exception) { /* drop frame */ }
    }

    fun start(name: String, host: String, httpPort: Int, interval: Long) {
        val safeName = name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        serverUrl = "http://$host:$httpPort/cam/$safeName"
        intervalMs = interval
        enabled = true
        thread = HandlerThread("camera").also { it.start() }
        handler = Handler(thread!!.looper)
        if (textureView.isAvailable) openCamera()
        else textureView.surfaceTextureListener = surfaceListener
        onStatus("camera starting -> $serverUrl")
    }

    private fun findBackCameraId(cm: CameraManager): String? {
        for (id in cm.cameraIdList) {
            val ch = cm.getCameraCharacteristics(id)
            if (ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) return id
        }
        return cm.cameraIdList.firstOrNull()
    }

    private fun openCamera() {
        try {
            if (ContextCompat.checkSelfPermission(activity, android.Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED) {
                onStatus("camera permission missing")
                return
            }
            val cm = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = findBackCameraId(cm) ?: run { onStatus("no back camera"); return }
            imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener(onImageAvailable, handler)
            }
            cm.openCamera(id, stateCallback, handler)
        } catch (e: Exception) {
            onStatus("camera open error: ${e.message}")
        }
    }

    private val captureLoop = object : Runnable {
        override fun run() {
            if (!enabled) return
            val s = session
            val ir = imageReader
            if (s == null || ir == null) {
                handler?.postDelayed(this, 200)
                return
            }
            try {
                val builder = s.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                builder.addTarget(ir.surface)
                s.capture(builder.build(), null, handler)
            } catch (e: Exception) { /* ignore */ }
            handler?.postDelayed(this, intervalMs)
        }
    }

    private fun createSession(camera: CameraDevice) {
        try {
            val texture = textureView.surfaceTexture ?: return
            val surface = Surface(texture)
            val ir = imageReader ?: return
            val surfaces = listOf(surface, ir.surface)
            camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    this@CameraController.session = session
                    try {
                        val preview = session.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        preview.addTarget(surface)
                        session.setRepeatingRequest(preview.build(), null, handler)
                    } catch (e: Exception) { /* ignore */ }
                    handler?.post(captureLoop)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    onStatus("camera session configure failed")
                }
            }, handler)
        } catch (e: Exception) {
            onStatus("camera session error: ${e.message}")
        }
    }

    private fun upload(bytes: ByteArray) {
        val url = serverUrl
        uploader.execute {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "image/jpeg")
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.outputStream.use { it.write(bytes) }
                conn.responseCode
                conn.disconnect()
            } catch (e: Exception) { /* streaming camera: network blips are normal */ }
        }
    }

    fun stop() {
        enabled = false
        try { session?.close() } catch (e: Exception) {}
        session = null
        try { cameraDevice?.close() } catch (e: Exception) {}
        cameraDevice = null
        try { imageReader?.close() } catch (e: Exception) {}
        imageReader = null
        try { thread?.quitSafely() } catch (e: Exception) {}
        thread = null
        handler = null
        uploader.shutdownNow()
        onStatus("camera stopped")
    }
}