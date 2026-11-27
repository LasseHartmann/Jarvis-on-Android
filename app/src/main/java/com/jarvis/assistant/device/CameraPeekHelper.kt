package com.jarvis.assistant.device

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Headless Camera2 single-frame rear-camera capture for Jarvis Camera Peek ("What am I looking at?").
 * Opens the back camera briefly, lets auto-exposure settle, grabs one oriented JPEG <= 1080px, and closes cleanly.
 */
object CameraPeekHelper {

    private const val TAG = "JarvisCameraPeek"

    fun hasCameraPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    suspend fun captureRearCameraJpeg(context: Context, maxDimension: Int = 1080): Pair<ByteArray?, String> =
        withContext(Dispatchers.IO) {
            if (!hasCameraPermission(context)) {
                return@withContext null to "Camera permission is not granted yet. Tap 'Grant permissions' in Jarvis Phone Permissions."
            }

            val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                ?: return@withContext null to "Camera service unavailable on this device."

            val cameraId = findRearCameraId(manager)
                ?: return@withContext null to "No rear camera found."

            val characteristics = runCatching { manager.getCameraCharacteristics(cameraId) }.getOrNull()
            val sensorOrientation = characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val map = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG).orEmpty()
            val chosen = jpegSizes
                .filter { maxOf(it.width, it.height) <= 1600 && minOf(it.width, it.height) >= 480 }
                .minByOrNull { kotlin.math.abs(maxOf(it.width, it.height) - 1280) }
                ?: jpegSizes.minByOrNull { it.width * it.height }
            val capW = chosen?.width ?: 1280
            val capH = chosen?.height ?: 720

            val thread = HandlerThread("JarvisCameraPeekThread").apply { start() }
            val handler = Handler(thread.looper)
            val executor = Executors.newSingleThreadExecutor()
            val imageReader = ImageReader.newInstance(capW, capH, ImageFormat.JPEG, 2)
            var cameraDevice: CameraDevice? = null
            var captureSession: CameraCaptureSession? = null

            try {
                val deviceDeferred = CompletableDeferred<CameraDevice?>()
                manager.openCamera(
                    cameraId,
                    object : CameraDevice.StateCallback() {
                        override fun onOpened(camera: CameraDevice) {
                            deviceDeferred.complete(camera)
                        }

                        override fun onDisconnected(camera: CameraDevice) {
                            camera.close()
                            if (!deviceDeferred.isCompleted) deviceDeferred.complete(null)
                        }

                        override fun onError(camera: CameraDevice, error: Int) {
                            camera.close()
                            if (!deviceDeferred.isCompleted) deviceDeferred.complete(null)
                        }
                    },
                    handler
                )

                cameraDevice = withTimeoutOrNull(3_500L) { deviceDeferred.await() }
                    ?: return@withContext null to "Unable to open rear camera (may be busy or blocked in background)."

                val imageDeferred = CompletableDeferred<ByteArray?>()
                imageReader.setOnImageAvailableListener({ reader ->
                    val img = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                    try {
                        val buf = img.planes[0].buffer
                        val raw = ByteArray(buf.remaining())
                        buf.get(raw)
                        if (!imageDeferred.isCompleted) {
                            imageDeferred.complete(raw)
                        }
                    } finally {
                        img.close()
                    }
                }, handler)

                val sessionDeferred = CompletableDeferred<CameraCaptureSession?>()
                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(OutputConfiguration(imageReader.surface)),
                    executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            sessionDeferred.complete(session)
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            sessionDeferred.complete(null)
                        }
                    }
                )
                cameraDevice.createCaptureSession(sessionConfig)

                captureSession = withTimeoutOrNull(3_000L) { sessionDeferred.await() }
                    ?: return@withContext null to "Camera capture session configuration failed."

                val reqBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(imageReader.surface)
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
                    set(CaptureRequest.JPEG_QUALITY, 82.toByte())
                }
                captureSession.capture(reqBuilder.build(), null, handler)

                val rawJpeg = withTimeoutOrNull(4_000L) { imageDeferred.await() }
                    ?: return@withContext null to "Timed out waiting for camera frame."

                val normalized = normalizeJpeg(rawJpeg, maxDimension)
                Log.i(TAG, "Captured rear camera frame: ${normalized.size / 1024} KB (sensor=${sensorOrientation}deg)")
                normalized to "Captured camera frame (${normalized.size / 1024} KB)."
            } catch (err: Throwable) {
                Log.w(TAG, "Camera peek failed: ${err.message}")
                null to "Camera peek failed: ${err.message}"
            } finally {
                runCatching { captureSession?.close() }
                runCatching { cameraDevice?.close() }
                runCatching { imageReader.close() }
                runCatching { executor.shutdownNow() }
                runCatching { thread.quitSafely() }
            }
        }

    private fun findRearCameraId(manager: CameraManager): String? = runCatching {
        manager.cameraIdList.firstOrNull { id ->
            val facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
            facing == CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.firstOrNull()
    }.getOrNull()

    private fun normalizeJpeg(rawJpeg: ByteArray, maxDim: Int): ByteArray {
        val bmp = BitmapFactory.decodeByteArray(rawJpeg, 0, rawJpeg.size) ?: return rawJpeg
        val longest = maxOf(bmp.width, bmp.height)
        val outBmp = if (longest > maxDim) {
            val scale = maxDim.toFloat() / longest.toFloat()
            val m = Matrix().apply { postScale(scale, scale) }
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true).also {
                if (it !== bmp) bmp.recycle()
            }
        } else bmp

        val baos = ByteArrayOutputStream()
        outBmp.compress(Bitmap.CompressFormat.JPEG, 80, baos)
        outBmp.recycle()
        return baos.toByteArray()
    }
}
