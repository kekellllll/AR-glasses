package com.ultronai.productarglasses.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size

class CameraController(
    private val context: Context,
    private val onFrameAvailable: (ByteArray, Int, Int) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val TAG = "CameraController"
        private const val PREFERRED_WIDTH = 1280
        private const val PREFERRED_HEIGHT = 720
        private const val JPEG_QUALITY = 70
        private const val EXPOSURE_COMPENSATION = 8
    }

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var useJpegFormat = false

    private val handlerThread = HandlerThread("CameraBackground").apply { start() }
    private val backgroundHandler = Handler(handlerThread.looper)

    private val cameraManager: CameraManager by lazy {
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    private val frameEncoder = FrameEncoder()

    @SuppressLint("MissingPermission")
    fun start() {
        try {
            val cameraId = findCameraId()
            if (cameraId == null) {
                onError("No camera found")
                return
            }

            // Check available formats
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val configs = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

            Log.i(TAG, "Checking camera formats...")

            // Check for JPEG support
            val jpegSizes = configs?.getOutputSizes(ImageFormat.JPEG)
            val yuvSizes = configs?.getOutputSizes(ImageFormat.YUV_420_888)

            Log.i(TAG, "JPEG sizes: ${jpegSizes?.joinToString()}")
            Log.i(TAG, "YUV sizes: ${yuvSizes?.joinToString()}")

            // Prefer JPEG if available at our resolution
            val jpegSize = jpegSizes?.findBestSize(PREFERRED_WIDTH, PREFERRED_HEIGHT)
            val yuvSize = yuvSizes?.findBestSize(PREFERRED_WIDTH, PREFERRED_HEIGHT)

            val (format, size) = when {
                jpegSize != null -> {
                    Log.i(TAG, "Using JPEG format: $jpegSize")
                    useJpegFormat = true
                    ImageFormat.JPEG to jpegSize
                }
                yuvSize != null -> {
                    Log.i(TAG, "Using YUV format: $yuvSize")
                    useJpegFormat = false
                    ImageFormat.YUV_420_888 to yuvSize
                }
                else -> {
                    onError("No supported format found")
                    return
                }
            }
            // Check exposure compensation range
            val aeRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            val aeStep = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
            Log.i(TAG, "Exposure compensation range: $aeRange, step: $aeStep")

            imageReader = ImageReader.newInstance(
                size.width, size.height, format, 2
            ).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        val jpeg = if (useJpegFormat) {
                            // Direct JPEG from camera
                            val buffer = image.planes[0].buffer
                            ByteArray(buffer.remaining()).also { buffer.get(it) }
                        } else {
                            // Convert YUV to JPEG
                            frameEncoder.encodeToJpeg(image, JPEG_QUALITY)
                        }

                        if (jpeg != null) {
                            onFrameAvailable(jpeg, image.width, image.height)
                        }
                    } finally {
                        image.close()
                    }
                }, backgroundHandler)
            }

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    Log.i(TAG, "Camera opened")
                    cameraDevice = camera
                    createCaptureSession(camera)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "Camera disconnected")
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    camera.close()
                    cameraDevice = null
                    onError("Camera error: $error")
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start camera", e)
            onError("Camera start failed: ${e.message}")
        }
    }

    private fun Array<Size>.findBestSize(targetWidth: Int, targetHeight: Int): Size? {
        // First try exact match
        find { it.width == targetWidth && it.height == targetHeight }?.let { return it }

        // Then find closest smaller size
        return filter { it.width <= targetWidth && it.height <= targetHeight }
            .maxByOrNull { it.width * it.height }
            ?: firstOrNull() // Fallback to any size
    }

    private fun findCameraId(): String? {
        // On AR glasses (e.g. RayNeo), "back" may be world-facing, "front" may be user-facing.
        // Some devices report world camera as FRONT. Prefer BACK first (standard world cam), then FRONT.
        var backId: String? = null
        var frontId: String? = null
        for (id in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            Log.i(TAG, "Camera $id: facing=$facing")
            when (facing) {
                CameraCharacteristics.LENS_FACING_BACK -> backId = id
                CameraCharacteristics.LENS_FACING_FRONT -> frontId = id
                else -> Unit
            }
        }
        // On RayNeo etc., BACK can be phone-mirror and FRONT the world camera. Prefer FRONT for world view.
        return frontId ?: backId ?: cameraManager.cameraIdList.firstOrNull()
    }

    private fun createCaptureSession(camera: CameraDevice) {
        try {
            val surfaces = listOf(imageReader!!.surface)

            camera.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        Log.i(TAG, "Capture session configured")
                        captureSession = session
                        startCapture(session)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Capture session configuration failed")
                        onError("Capture session configuration failed")
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create capture session", e)
            onError("Session creation failed: ${e.message}")
        }
    }

    private fun startCapture(session: CameraCaptureSession) {
        try {
            val requestBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)?.apply {
                addTarget(imageReader!!.surface)

                // Auto focus
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)

                // Auto exposure ON
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)

                // Exposure compensation (increase brightness)
                // Range is typically -12 to +12, each step is usually 1/3 or 1/2 EV
                // Positive = brighter, Negative = darker
                set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, EXPOSURE_COMPENSATION)

                if (useJpegFormat) {
                    set(CaptureRequest.JPEG_QUALITY, JPEG_QUALITY.toByte())
                }
            } ?: return

            session.setRepeatingRequest(
                requestBuilder.build(),
                null,
                backgroundHandler
            )

            Log.i(TAG, "Capture started (format=${if (useJpegFormat) "JPEG" else "YUV"})")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start capture", e)
            onError("Capture start failed: ${e.message}")
        }
    }

    fun stop() {
        Log.i(TAG, "Stopping camera")
        try {
            val session = captureSession
            captureSession = null
            session?.close()

            val camera = cameraDevice
            cameraDevice = null
            camera?.close()

            val reader = imageReader
            imageReader = null
            reader?.close()

            handlerThread.quitSafely()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping camera", e)
        }
    }
}