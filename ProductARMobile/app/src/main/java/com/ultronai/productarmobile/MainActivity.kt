package com.ultronai.productarmobile

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ultronai.productarmobile.databinding.ActivityMainBinding
import com.ultronai.productarmobile.llm.LlmClient
import com.ultronai.productarmobile.ml.Detection
import com.ultronai.productarmobile.ml.RTMDetDetector
import com.ultronai.productarmobile.network.MjpegClient
import com.ultronai.productarmobile.ocr.OcrProcessor
import com.ultronai.productarmobile.rag.RagPipeline
import com.ultronai.productarmobile.shelf.ShelfAnalysisAgent
import com.ultronai.productarmobile.shopping.ShoppingAssistAgent
import com.ultronai.productarmobile.stt.WhisperStt
import com.ultronai.productarmobile.tts.TtsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val AUDIO_PERMISSION_CODE = 200
    }

    private lateinit var binding: ActivityMainBinding

    // Core components
    private var mjpegClient: MjpegClient? = null
    private var detector: RTMDetDetector? = null
    private var lastBitmap: Bitmap? = null
    @Volatile private var isDetectorReady = false

    // AI pipeline components
    private lateinit var ttsManager: TtsManager
    private lateinit var ocrProcessor: OcrProcessor
    private lateinit var whisperStt: WhisperStt
    private lateinit var llmClient: LlmClient
    private lateinit var ragPipeline: RagPipeline
    private var shoppingAgent: ShoppingAssistAgent? = null
    private var shelfAgent: ShelfAnalysisAgent? = null

    // State
    private var lastProcessTimeNs = 0L
    private val minFrameIntervalNs = 80L * 1_000_000
    private var latestDetections: List<Detection> = emptyList()
    private var currentFrame: Bitmap? = null
    @Volatile private var isAsking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initAiComponents()
        setupUI()
        initDetector()
    }

    private fun initAiComponents() {
        ttsManager = TtsManager(this)
        ocrProcessor = OcrProcessor()
        whisperStt = WhisperStt(this)
        llmClient = LlmClient(this)
        ragPipeline = RagPipeline(this)

        lifecycleScope.launch(Dispatchers.Default) {
            ragPipeline.initialize()
            withContext(Dispatchers.Main) {
                Log.i(TAG, "RAG initialized")
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                whisperStt.loadModel()
                withContext(Dispatchers.Main) {
                    updateModelStatus()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Whisper load failed", e)
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (llmClient.isModelDownloaded()) {
                    llmClient.loadModel()
                }
                withContext(Dispatchers.Main) {
                    updateModelStatus()
                }
            } catch (e: Exception) {
                Log.e(TAG, "LLM load failed", e)
            }
        }
    }

    private fun setupUI() {
        binding.btnConnect.setOnClickListener {
            val ip = binding.etIpAddress.text.toString().trim()
            if (ip.isNotEmpty()) {
                connect(ip)
            }
        }

        binding.btnAsk.setOnClickListener { onAskClicked() }
        binding.btnOcr.setOnClickListener { onOcrClicked() }
        binding.btnShelf.setOnClickListener { onShelfClicked() }
    }

    private fun initDetector() {
        binding.tvStatus.text = "Status: Loading detector..."

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
                    updateModelStatus()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Detector init failed", e)
                withContext(Dispatchers.Main) {
                    binding.tvStatus.text = "Status: Detector failed - ${e.message}"
                }
            }
        }
    }

    private fun updateModelStatus() {
        val det = if (isDetectorReady) "Det✓" else "Det..."
        val stt = if (whisperStt.isModelLoaded) "STT✓" else "STT✗"
        val llm = if (llmClient.isLoaded) "LLM✓" else if (llmClient.isModelDownloaded()) "LLM..." else "LLM↓"
        val tts = if (ttsManager.isReady) "TTS✓" else "TTS..."
        binding.tvModels.text = "$det $stt $llm $tts"
    }

    // ── Connection ──

    private fun connect(ip: String) {
        mjpegClient?.disconnect()
        binding.tvStatus.text = "Status: Connecting..."
        binding.btnConnect.isEnabled = false

        val parts = ip.split(":")
        val host = parts[0]
        val port = if (parts.size > 1) parts[1].toIntOrNull() ?: 8080 else 8080

        mjpegClient = MjpegClient(
            onFrameReceived = { bitmap -> onFrameFromStream(bitmap) },
            onConnectionChanged = { connected ->
                runOnUiThread {
                    if (connected) {
                        binding.tvStatus.text = "Status: Connected"
                        binding.tvStatus.setTextColor(0xFF00FF00.toInt())
                        binding.connectPanel.visibility = View.GONE
                        binding.actionButtons.visibility = View.VISIBLE
                    } else {
                        binding.tvStatus.text = "Status: Disconnected"
                        binding.tvStatus.setTextColor(0xFFFF0000.toInt())
                        binding.connectPanel.visibility = View.VISIBLE
                        binding.actionButtons.visibility = View.GONE
                        binding.btnConnect.isEnabled = true
                    }
                }
            },
            onFpsUpdate = { fps ->
                runOnUiThread { binding.tvFps.text = "Stream: $fps FPS" }
            },
            onError = { error ->
                runOnUiThread {
                    binding.tvStatus.text = "Status: Failed - $error"
                    binding.tvStatus.setTextColor(0xFFFF0000.toInt())
                }
            }
        )

        mjpegClient?.connect(host, port)

        shoppingAgent = ShoppingAssistAgent(
            whisperStt, ttsManager, ocrProcessor, ragPipeline, llmClient
        ) { status -> runOnUiThread { binding.tvStatus.text = status } }

        shelfAgent = ShelfAnalysisAgent(
            ragPipeline, llmClient, ttsManager
        ) { status -> runOnUiThread { binding.tvStatus.text = status } }
    }

    // ── Frame pipeline ──

    private fun onFrameFromStream(bitmap: Bitmap) {
        if (!isDetectorReady) {
            runOnUiThread {
                val old = lastBitmap
                lastBitmap = bitmap
                binding.ivPreview.setImageBitmap(bitmap)
                old?.recycle()
            }
            return
        }
        val now = System.nanoTime()
        if (now - lastProcessTimeNs < minFrameIntervalNs) {
            bitmap.recycle()
            return
        }
        lastProcessTimeNs = now
        processFrame(bitmap)
    }

    private fun processFrame(bitmap: Bitmap) {
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val result = detector?.detectWithSkip(bitmap)
                if (result == null) {
                    bitmap.recycle()
                    return@launch
                }

                val detections = result.detections
                latestDetections = detections

                synchronized(this@MainActivity) {
                    currentFrame?.recycle()
                    currentFrame = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                }

                val annotated = drawDetections(bitmap, detections)

                withContext(Dispatchers.Main) {
                    val old = lastBitmap
                    lastBitmap = annotated
                    binding.ivPreview.setImageBitmap(annotated)

                    val fps = detector?.getFps() ?: 0f
                    val timing = "pre:${result.preprocessMs} inf:${result.inferenceMs} post:${result.postprocessMs}ms"
                    binding.tvDetections.text = "Det: %.1f FPS | %d obj | %s".format(fps, detections.size, timing)

                    if (old != null && old != annotated) old.recycle()
                    if (annotated != bitmap && !bitmap.isRecycled) bitmap.recycle()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Detection error", e)
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
    }

    // ── Voice Ask flow ──

    private fun onAskClicked() {
        if (!whisperStt.isModelLoaded) {
            Toast.makeText(this, "STT model not ready", Toast.LENGTH_SHORT).show()
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.RECORD_AUDIO), AUDIO_PERMISSION_CODE)
            return
        }

        if (isAsking) {
            stopAskAndProcess()
        } else {
            startListening()
        }
    }

    private fun startListening() {
        val started = whisperStt.startRecording()
        if (started) {
            isAsking = true
            binding.btnAsk.text = "⏹ Stop"
            binding.tvStatus.text = "Listening..."
            binding.tvStatus.setTextColor(0xFFFF4444.toInt())
        }
    }

    private fun stopAskAndProcess() {
        isAsking = false
        binding.btnAsk.text = "🎤 Ask"
        binding.tvStatus.text = "Transcribing..."

        lifecycleScope.launch {
            val transcript = whisperStt.stopAndTranscribe()
            if (transcript.isBlank()) {
                binding.tvStatus.text = "No speech detected"
                return@launch
            }

            binding.tvStatus.text = "You said: $transcript"

            val topDet = latestDetections.maxByOrNull { it.score }
            val frame = synchronized(this@MainActivity) {
                currentFrame?.copy(Bitmap.Config.ARGB_8888, false)
            }

            val result = shoppingAgent?.processQuery(transcript, frame, topDet)
            frame?.recycle()

            withContext(Dispatchers.Main) {
                binding.tvAiResponse.visibility = View.VISIBLE
                binding.tvAiResponse.text = result?.answer ?: "No response"
                binding.tvStatus.text = "Status: Connected"
                binding.tvStatus.setTextColor(0xFF00FF00.toInt())
            }
        }
    }

    // ── OCR flow ──

    private fun onOcrClicked() {
        val topDet = latestDetections.maxByOrNull { it.score }
        val frame = synchronized(this@MainActivity) {
            currentFrame?.copy(Bitmap.Config.ARGB_8888, false)
        }

        if (frame == null || topDet == null) {
            frame?.recycle()
            Toast.makeText(this, "No product detected to OCR", Toast.LENGTH_SHORT).show()
            return
        }

        binding.tvStatus.text = "Running OCR..."

        lifecycleScope.launch {
            val halfW = topDet.width / 2
            val halfH = topDet.height / 2
            val box = android.graphics.Rect(
                (topDet.cx - halfW).toInt().coerceAtLeast(0),
                (topDet.cy - halfH).toInt().coerceAtLeast(0),
                (topDet.cx + halfW).toInt().coerceAtMost(frame.width),
                (topDet.cy + halfH).toInt().coerceAtMost(frame.height)
            )

            val ocrResult = ocrProcessor.recognizeFromCrop(frame, box)
            frame.recycle()

            withContext(Dispatchers.Main) {
                binding.tvAiResponse.visibility = View.VISIBLE
                val text = buildString {
                    append("OCR: ${ocrResult.rawText.take(200)}")
                    if (ocrResult.allergens.isNotEmpty()) {
                        append("\n⚠ Allergens: ${ocrResult.allergens.joinToString(", ")}")
                    }
                }
                binding.tvAiResponse.text = text
                binding.tvStatus.text = "Status: Connected"
                binding.tvStatus.setTextColor(0xFF00FF00.toInt())
            }
        }
    }

    // ── Shelf analysis flow ──

    private fun onShelfClicked() {
        val detections = latestDetections
        if (detections.isEmpty()) {
            Toast.makeText(this, "No products detected on shelf", Toast.LENGTH_SHORT).show()
            return
        }

        val frameH = synchronized(this@MainActivity) { currentFrame?.height ?: 720 }

        lifecycleScope.launch {
            val result = shelfAgent?.analyzeShelf(detections, frameH)

            withContext(Dispatchers.Main) {
                binding.tvAiResponse.visibility = View.VISIBLE
                binding.tvAiResponse.text = result?.summary ?: "No analysis"
                binding.tvStatus.text = "Status: Connected"
                binding.tvStatus.setTextColor(0xFF00FF00.toInt())
            }
        }
    }

    // ── Permissions ──

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == AUDIO_PERMISSION_CODE &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startListening()
        }
    }

    // ── Drawing ──

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

    // ── Lifecycle ──

    override fun onDestroy() {
        super.onDestroy()
        mjpegClient?.disconnect()
        detector?.close()
        lastBitmap?.recycle()
        currentFrame?.recycle()
        ttsManager.shutdown()
        ocrProcessor.close()
        whisperStt.release()
        llmClient.unload()
    }
}
