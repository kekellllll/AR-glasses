// app/src/main/java/com/ultronai/productarmobile/MainActivity.kt
package com.ultronai.productarmobile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ultronai.productarmobile.databinding.ActivityMainBinding
import com.ultronai.productarmobile.ml.Detection
import com.ultronai.productarmobile.ml.RTMDetDetector
import com.ultronai.productarmobile.network.MjpegClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private var mjpegClient: MjpegClient? = null
    private var detector: RTMDetDetector? = null

    private var lastBitmap: Bitmap? = null
    private var isDetectorReady = false
    private var runDetection = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        initDetector()
    }

    private fun setupUI() {
        binding.btnConnect.setOnClickListener {
            val ip = binding.etIpAddress.text.toString().trim()
            if (ip.isNotEmpty()) {
                connect(ip)
            }
        }
    }

    private fun initDetector() {
        binding.tvStatus.text = "Status: Loading model..."

        lifecycleScope.launch(Dispatchers.Default) {
            try {
                detector = RTMDetDetector(
                    context = this@MainActivity,
                    modelPath = "rtmdet_pico_fp16.tflite",
                    backend = RTMDetDetector.Backend.GPU_FP16
                )
                isDetectorReady = true

                withContext(Dispatchers.Main) {
                    binding.tvStatus.text = "Status: Ready (${detector?.getActualBackend()})"
                    Log.i(TAG, "Detector initialized")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to init detector", e)
                withContext(Dispatchers.Main) {
                    binding.tvStatus.text = "Status: Model load failed - ${e.message}"
                }
            }
        }
    }

    private fun connect(ip: String) {
        mjpegClient?.disconnect()

        binding.tvStatus.text = "Status: Connecting..."
        binding.btnConnect.isEnabled = false

        val parts = ip.split(":")
        val host = parts[0]
        val port = if (parts.size > 1) parts[1].toIntOrNull() ?: 8080 else 8080

        mjpegClient = MjpegClient(
            onFrameReceived = { bitmap ->
                processFrame(bitmap)
            },
            onConnectionChanged = { connected ->
                runOnUiThread {
                    if (connected) {
                        binding.tvStatus.text = "Status: Connected"
                        binding.tvStatus.setTextColor(0xFF00FF00.toInt())
                        binding.connectPanel.visibility = View.GONE
                    } else {
                        binding.tvStatus.text = "Status: Disconnected"
                        binding.tvStatus.setTextColor(0xFFFF0000.toInt())
                        binding.connectPanel.visibility = View.VISIBLE
                        binding.btnConnect.isEnabled = true
                    }
                }
            },
            onFpsUpdate = { fps ->
                runOnUiThread {
                    binding.tvFps.text = "Stream: $fps FPS"
                }
            }
        )

        mjpegClient?.connect(host, port)
    }

    private fun processFrame(bitmap: Bitmap) {
        if (!isDetectorReady || !runDetection) {
            runOnUiThread {
                lastBitmap?.recycle()
                lastBitmap = bitmap
                binding.ivPreview.setImageBitmap(bitmap)
            }
            return
        }

        lifecycleScope.launch(Dispatchers.Default) {
            try {
                // Use detectWithSkip - returns null if busy
                val result = detector?.detectWithSkip(bitmap)

                if (result == null) {
                    // Frame was skipped, recycle bitmap
                    bitmap.recycle()
                    return@launch
                }

                val detections = result.detections
                val annotated = drawDetections(bitmap, detections)

                withContext(Dispatchers.Main) {
                    lastBitmap?.recycle()
                    lastBitmap = annotated
                    binding.ivPreview.setImageBitmap(annotated)

                    // Update FPS and detection info
                    val fps = detector?.getFps() ?: 0f
                    val timing = "pre:${result.preprocessMs} inf:${result.inferenceMs} post:${result.postprocessMs}ms"
                    binding.tvDetections.text = "Detect: %.1f FPS | %d obj | %s".format(fps, detections.size, timing)
                }

                if (annotated != bitmap) {
                    bitmap.recycle()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Detection error: ${e.message}", e)
                bitmap.recycle()
            }
        }
    }

    private fun drawDetections(bitmap: Bitmap, detections: List<Detection>): Bitmap {
        if (detections.isEmpty()) return bitmap

        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)

        val boxPaint = Paint().apply {
            color = Color.GREEN
            style = Paint.Style.STROKE
            strokeWidth = 3f
            isAntiAlias = true
        }

        val textBgPaint = Paint().apply {
            color = Color.GREEN
            style = Paint.Style.FILL
        }

        val textPaint = Paint().apply {
            color = Color.BLACK
            textSize = 24f
            isAntiAlias = true
        }

        for (det in detections) {
            val corners = getCorners(det)
            val path = Path().apply {
                moveTo(corners[0], corners[1])
                lineTo(corners[2], corners[3])
                lineTo(corners[4], corners[5])
                lineTo(corners[6], corners[7])
                close()
            }
            canvas.drawPath(path, boxPaint)

            val label = "%.2f".format(det.score)
            val textWidth = textPaint.measureText(label)
            val textX = det.cx - textWidth / 2
            val textY = det.cy - det.height / 2 - 10

            canvas.drawRect(textX - 2, textY - 20, textX + textWidth + 2, textY + 4, textBgPaint)
            canvas.drawText(label, textX, textY, textPaint)
        }

        return result
    }

    private fun getCorners(det: Detection): FloatArray {
        val cosA = cos(det.angle)
        val sinA = sin(det.angle)
        val hw = det.width / 2
        val hh = det.height / 2

        return floatArrayOf(
            det.cx - cosA * hw + sinA * hh, det.cy - sinA * hw - cosA * hh,
            det.cx + cosA * hw + sinA * hh, det.cy + sinA * hw - cosA * hh,
            det.cx + cosA * hw - sinA * hh, det.cy + sinA * hw + cosA * hh,
            det.cx - cosA * hw - sinA * hh, det.cy - sinA * hw + cosA * hh
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        mjpegClient?.disconnect()
        detector?.close()
        lastBitmap?.recycle()
    }
}