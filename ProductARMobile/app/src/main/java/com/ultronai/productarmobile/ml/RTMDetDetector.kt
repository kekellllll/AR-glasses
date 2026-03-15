// app/src/main/java/com/ultronai/productarmobile/ml/RTMDetDetector.kt
package com.ultronai.productarmobile.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

class RTMDetDetector(
    context: Context,
    modelPath: String = "rtmdet_retail_int8.tflite",
    private val backend: Backend = Backend.CPU,
    private val scoreThreshold: Float = 0.3f,
    private val nmsThreshold: Float = 0.45f
) {
    enum class Backend {
        CPU,
        GPU_FP16,
        NNAPI
    }

    companion object {
        private const val TAG = "RTMDetDetector"

        const val INPUT_SIZE = 640
        private val STRIDES = intArrayOf(8, 16, 32)

        private val MEAN_BGR = floatArrayOf(103.53f, 116.28f, 123.675f)
        private val STD_BGR = floatArrayOf(57.375f, 57.12f, 58.395f)

        private const val PAD_VALUE = 114
    }

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var nnApiDelegate: NnApiDelegate? = null

    private var inputScale = 1f
    private var inputZeroPoint = 0
    private var isInputQuantized = false

    private val anchors = mutableListOf<AnchorLevel>()
    private lateinit var inputBuffer: ByteBuffer
    private lateinit var floatArray: FloatArray

    private data class OutputInfo(
        val index: Int,
        val name: String,
        val shape: IntArray,
        val isQuantized: Boolean,
        val scale: Float,
        val zeroPoint: Int
    )
    private val outputInfos = mutableListOf<OutputInfo>()
    private val outputBuffersCache = mutableMapOf<Int, ByteBuffer>()

    private var actualBackend: Backend = Backend.CPU

    // Frame skip and FPS monitoring
    private var isProcessing = AtomicBoolean(false)
    private var frameCount = 0
    private var lastFpsTime = System.currentTimeMillis()
    private var currentFps = 0f
    private var skippedFrames = 0

    data class AnchorLevel(
        val stride: Int,
        val px: FloatArray,
        val py: FloatArray,
        val count: Int
    )

    init {
        Log.i(TAG, "=== Initializing RTMDetDetector (Requested: $backend) ===")
        loadModel(context, modelPath)
        initAnchors()
        allocateBuffers()
        Log.i(TAG, "=== Initialization complete (Actual: $actualBackend) ===")
    }

    private fun loadModel(context: Context, modelPath: String) {
        Log.i(TAG, "Loading model: $modelPath")

        val modelFile = copyAssetToFile(context, modelPath)
        val modelBuffer = loadModelFile(modelFile)

        Log.i(TAG, "Model buffer size: ${modelBuffer.capacity()} bytes")

        val options = Interpreter.Options().apply {
            setNumThreads(4)
        }

        when (backend) {
            Backend.GPU_FP16 -> {
                try {
                    Log.i(TAG, "Creating GPU delegate (forced, skip compat check)...")

                    val delegateOptions = GpuDelegate.Options()
                    delegateOptions.setPrecisionLossAllowed(true)
                    delegateOptions.setQuantizedModelsAllowed(true)

                    gpuDelegate = GpuDelegate(delegateOptions)
                    options.addDelegate(gpuDelegate)
                    actualBackend = Backend.GPU_FP16
                    Log.i(TAG, "GPU delegate forced")
                } catch (e: Exception) {
                    Log.e(TAG, "GPU delegate failed: ${e.message}", e)
                    actualBackend = Backend.CPU
                }
            }
            Backend.NNAPI -> {
                try {
                    Log.i(TAG, "Configuring NNAPI delegate...")
                    val nnApiOptions = NnApiDelegate.Options()
                        .setAllowFp16(true)
                        .setUseNnapiCpu(false)
                        .setExecutionPreference(NnApiDelegate.Options.EXECUTION_PREFERENCE_SUSTAINED_SPEED)

                    nnApiDelegate = NnApiDelegate(nnApiOptions)
                    options.addDelegate(nnApiDelegate)
                    actualBackend = Backend.NNAPI
                    Log.i(TAG, "NNAPI delegate configured")
                } catch (e: Exception) {
                    Log.e(TAG, "NNAPI delegate failed: ${e.message}", e)
                    actualBackend = Backend.CPU
                }
            }
            Backend.CPU -> {
                Log.i(TAG, "Using CPU backend (XNNPACK)")
                actualBackend = Backend.CPU
            }
        }

        Log.i(TAG, "Creating interpreter with backend: $actualBackend")

        try {
            interpreter = Interpreter(modelBuffer, options)
        } catch (e: Exception) {
            Log.e(TAG, "Interpreter creation failed: ${e.message}")

            if (actualBackend != Backend.CPU) {
                Log.w(TAG, "Retrying with CPU...")
                gpuDelegate?.close()
                gpuDelegate = null
                nnApiDelegate?.close()
                nnApiDelegate = null
                actualBackend = Backend.CPU

                val cpuOptions = Interpreter.Options().apply {
                    setNumThreads(4)
                }
                interpreter = Interpreter(modelBuffer, cpuOptions)
            } else {
                throw e
            }
        }

        val interp = interpreter!!

        val inputTensor = interp.getInputTensor(0)
        val inputShape = inputTensor.shape()
        val inputType = inputTensor.dataType()
        isInputQuantized = inputType.name.contains("INT") || inputType.name.contains("UINT")

        Log.i(TAG, "Input shape: ${inputShape.contentToString()}")
        Log.i(TAG, "Input type: $inputType, quantized: $isInputQuantized")

        if (isInputQuantized) {
            val params = inputTensor.quantizationParams()
            inputScale = params.scale
            inputZeroPoint = params.zeroPoint
            Log.i(TAG, "Input quant: scale=$inputScale, zp=$inputZeroPoint")
        } else {
            Log.i(TAG, "Input is FLOAT (FP16/FP32), no quantization")
        }

        val numOutputs = interp.outputTensorCount
        Log.i(TAG, "Number of outputs: $numOutputs")

        for (i in 0 until numOutputs) {
            val tensor = interp.getOutputTensor(i)
            val name = tensor.name() ?: "output_$i"
            val shape = tensor.shape()
            val type = tensor.dataType()
            val isOutQuantized = type.name.contains("INT") || type.name.contains("UINT")

            var scale = 1f
            var zp = 0
            if (isOutQuantized) {
                val params = tensor.quantizationParams()
                scale = params.scale
                zp = params.zeroPoint
            }

            outputInfos.add(OutputInfo(i, name, shape, isOutQuantized, scale, zp))
            Log.i(TAG, "Output[$i] '$name': ${shape.contentToString()}, type=$type, quantized=$isOutQuantized")
        }
    }

    private fun copyAssetToFile(context: Context, assetName: String): File {
        val outFile = File(context.cacheDir, assetName)
        val versionFile = File(context.cacheDir, "$assetName.version")
        val currentVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toString()
        } catch (_: Exception) { "0" }

        val needsCopy = !outFile.exists() ||
                !versionFile.exists() ||
                versionFile.readText().trim() != currentVersion

        if (needsCopy) {
            context.assets.open(assetName).use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
            versionFile.writeText(currentVersion)
            Log.i(TAG, "Copied $assetName to ${outFile.absolutePath} (version=$currentVersion)")
        }
        return outFile
    }

    private fun loadModelFile(file: File): MappedByteBuffer {
        return FileInputStream(file).use { inputStream ->
            inputStream.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
        }
    }

    private fun initAnchors() {
        for (stride in STRIDES) {
            val fh = INPUT_SIZE / stride
            val fw = INPUT_SIZE / stride
            val count = fh * fw

            val px = FloatArray(count)
            val py = FloatArray(count)

            var idx = 0
            for (y in 0 until fh) {
                for (x in 0 until fw) {
                    px[idx] = (x * stride).toFloat()
                    py[idx] = (y * stride).toFloat()
                    idx++
                }
            }

            anchors.add(AnchorLevel(stride, px, py, count))
            Log.i(TAG, "Anchor stride=$stride: $count points")
        }
    }

    private fun allocateBuffers() {
        val numPixels = INPUT_SIZE * INPUT_SIZE * 3
        val bufferSize = if (isInputQuantized) {
            numPixels
        } else {
            numPixels * 4
        }

        inputBuffer = ByteBuffer.allocateDirect(bufferSize).apply {
            order(ByteOrder.nativeOrder())
        }

        if (!isInputQuantized) {
            floatArray = FloatArray(numPixels)
        }

        Log.i(TAG, "Input buffer allocated: $bufferSize bytes (quantized=$isInputQuantized)")
    }

    fun getActualBackend(): Backend = actualBackend

    fun getFps(): Float = currentFps

    /**
     * Detect with frame skipping - returns null if previous frame still processing
     */
    fun detectWithSkip(bitmap: Bitmap): DetectionResult? {
        if (!isProcessing.compareAndSet(false, true)) {
            skippedFrames++
            return null
        }

        return try {
            val result = detect(bitmap)
            updateFps()
            result
        } finally {
            isProcessing.set(false)
        }
    }

    private fun updateFps() {
        frameCount++
        val now = System.currentTimeMillis()
        val elapsed = now - lastFpsTime

        if (elapsed >= 1000) {
            currentFps = frameCount * 1000f / elapsed
            Log.i(TAG, "=== FPS: %.1f | Processed: %d | Skipped: %d ===".format(currentFps, frameCount, skippedFrames))
            frameCount = 0
            skippedFrames = 0
            lastFpsTime = now
        }
    }

    @Synchronized
    fun detect(bitmap: Bitmap): DetectionResult {
        val startTotal = System.currentTimeMillis()

        // Preprocess
        val startPre = System.currentTimeMillis()
        val scale = preprocess(bitmap)
        val preprocessMs = System.currentTimeMillis() - startPre

        // Inference
        val startInf = System.currentTimeMillis()
        val outputs = runInference()
        val inferenceMs = System.currentTimeMillis() - startInf
        Log.d(TAG, "Inference: ${inferenceMs}ms ($actualBackend)")

        // Postprocess
        val startPost = System.currentTimeMillis()
        val rawDetections = decodeOutputs(outputs)
        val keepIndices = rotatedNms(rawDetections, scoreThreshold, nmsThreshold)

        val detections = keepIndices.map { idx ->
            val d = rawDetections[idx]
            Detection(
                cx = d.cx / scale,
                cy = d.cy / scale,
                width = d.width / scale,
                height = d.height / scale,
                angle = d.angle,
                score = d.score,
                classId = d.classId
            )
        }
        val postprocessMs = System.currentTimeMillis() - startPost

        Log.d(TAG, "Total: ${System.currentTimeMillis() - startTotal}ms, ${detections.size} detections")

        return DetectionResult(detections, preprocessMs, inferenceMs, postprocessMs)
    }

    private fun preprocess(bitmap: Bitmap): Float {
        val t1 = System.currentTimeMillis()

        val srcW = bitmap.width
        val srcH = bitmap.height

        val scale = minOf(INPUT_SIZE.toFloat() / srcW, INPUT_SIZE.toFloat() / srcH)
        val newW = (srcW * scale + 0.5f).toInt()
        val newH = (srcH * scale + 0.5f).toInt()

        val resized = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        val t2 = System.currentTimeMillis()

        val padded = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(padded)
        canvas.drawColor(Color.rgb(PAD_VALUE, PAD_VALUE, PAD_VALUE))
        canvas.drawBitmap(resized, 0f, 0f, null)
        val t3 = System.currentTimeMillis()

        if (resized != bitmap) resized.recycle()

        if (isInputQuantized) {
            fillInputBufferQuantized(padded)
        } else {
            fillInputBufferFloat(padded)
        }
        val t4 = System.currentTimeMillis()

        padded.recycle()

        Log.d(TAG, "Preprocess: resize=${t2-t1}ms, pad=${t3-t2}ms, fill=${t4-t3}ms")

        return scale
    }

    private fun fillInputBufferQuantized(bitmap: Bitmap) {
        inputBuffer.clear()

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        val invScale = 1f / inputScale
        val coeffA = FloatArray(3)
        val coeffB = FloatArray(3)
        for (c in 0..2) {
            coeffA[c] = invScale / STD_BGR[c]
            coeffB[c] = -MEAN_BGR[c] * coeffA[c] + inputZeroPoint
        }

        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF

            val qB = ((b * coeffA[0] + coeffB[0]).roundToInt()).coerceIn(-128, 127).toByte()
            val qG = ((g * coeffA[1] + coeffB[1]).roundToInt()).coerceIn(-128, 127).toByte()
            val qR = ((r * coeffA[2] + coeffB[2]).roundToInt()).coerceIn(-128, 127).toByte()

            inputBuffer.put(qB)
            inputBuffer.put(qG)
            inputBuffer.put(qR)
        }
    }

    private fun fillInputBufferFloat(bitmap: Bitmap) {
        inputBuffer.clear()

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        val invStdB = 1f / STD_BGR[0]
        val invStdG = 1f / STD_BGR[1]
        val invStdR = 1f / STD_BGR[2]
        val meanB = MEAN_BGR[0]
        val meanG = MEAN_BGR[1]
        val meanR = MEAN_BGR[2]

        var idx = 0
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF

            floatArray[idx++] = (b - meanB) * invStdB
            floatArray[idx++] = (g - meanG) * invStdG
            floatArray[idx++] = (r - meanR) * invStdR
        }

        inputBuffer.asFloatBuffer().put(floatArray)
    }

    private fun runInference(): Map<Int, FloatArray> {
        inputBuffer.rewind()

        val interp = interpreter ?: throw IllegalStateException("Interpreter null")

        val outputBuffers = mutableMapOf<Int, Any>()
        for (info in outputInfos) {
            val size = info.shape.reduce { acc, v -> acc * v }
            val byteSize = if (info.isQuantized) size else size * 4
            val buf = outputBuffersCache.getOrPut(info.index) {
                ByteBuffer.allocateDirect(byteSize).order(ByteOrder.nativeOrder())
            }
            buf.clear()
            outputBuffers[info.index] = buf
        }

        interp.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputBuffers)

        val outputs = mutableMapOf<Int, FloatArray>()
        for (info in outputInfos) {
            val size = info.shape.reduce { acc, v -> acc * v }
            val buffer = outputBuffers[info.index] as ByteBuffer
            buffer.rewind()

            outputs[info.index] = if (info.isQuantized) {
                FloatArray(size) {
                    val raw = buffer.get().toInt() and 0xFF
                    (raw - info.zeroPoint) * info.scale
                }
            } else {
                val floatBuffer = buffer.asFloatBuffer()
                FloatArray(size).also { floatBuffer.get(it) }
            }
        }

        return outputs
    }

    private fun decodeOutputs(outputs: Map<Int, FloatArray>): List<RawDetection> {
        val detections = mutableListOf<RawDetection>()

        val firstOutputName = outputInfos.firstOrNull()?.name ?: ""
        val isPicoModel = firstOutputName.startsWith("Identity")

        data class StrideConfig(
            val anchorIdx: Int,
            val bboxIdx: Int,
            val clsIdx: Int,
            val angleIdx: Int
        )

        val configs = if (isPicoModel) {
            Log.d(TAG, "Using PICO model output mapping")
            listOf(
                StrideConfig(anchorIdx = 0, bboxIdx = 1, clsIdx = 0, angleIdx = 2),
                StrideConfig(anchorIdx = 1, bboxIdx = 4, clsIdx = 3, angleIdx = 5),
                StrideConfig(anchorIdx = 2, bboxIdx = 7, clsIdx = 6, angleIdx = 8)
            )
        } else {
            Log.d(TAG, "Using RETAIL model output mapping")
            listOf(
                StrideConfig(anchorIdx = 0, bboxIdx = 1, clsIdx = 5, angleIdx = 6),
                StrideConfig(anchorIdx = 1, bboxIdx = 0, clsIdx = 4, angleIdx = 3),
                StrideConfig(anchorIdx = 2, bboxIdx = 2, clsIdx = 8, angleIdx = 7)
            )
        }

        for (config in configs) {
            val anchor = anchors.getOrNull(config.anchorIdx) ?: continue

            val bboxData = outputs[config.bboxIdx] ?: continue
            val clsData = outputs[config.clsIdx] ?: continue
            val angleData = outputs[config.angleIdx] ?: continue

            val numClasses = clsData.size / anchor.count

            for (i in 0 until anchor.count) {
                if (i * 4 + 3 >= bboxData.size) continue
                if (i >= angleData.size) continue

                val l = bboxData[i * 4]
                val t = bboxData[i * 4 + 1]
                val r = bboxData[i * 4 + 2]
                val b = bboxData[i * 4 + 3]

                val boxW = l + r
                val boxH = t + b
                val angle = angleData[i]

                val offsetX = (r - l) * 0.5f
                val offsetY = (b - t) * 0.5f
                val cosA = cos(angle)
                val sinA = sin(angle)

                val cx = anchor.px[i] + cosA * offsetX - sinA * offsetY
                val cy = anchor.py[i] + sinA * offsetX + cosA * offsetY

                var bestScore = 0f
                var bestClass = 0

                if (numClasses == 1) {
                    bestScore = clsData[i]
                } else {
                    for (c in 0 until numClasses) {
                        val idx = i * numClasses + c
                        if (idx < clsData.size) {
                            val score = clsData[idx]
                            if (score > bestScore) {
                                bestScore = score
                                bestClass = c
                            }
                        }
                    }
                }

                if (bestScore > 0.01f) {
                    detections.add(RawDetection(cx, cy, boxW, boxH, normalizeAngle(angle), bestScore, bestClass))
                }
            }
        }

        return detections
    }

    private fun normalizeAngle(angle: Float): Float {
        var a = angle % PI.toFloat()
        if (a >= PI.toFloat() / 2) a -= PI.toFloat()
        if (a < -PI.toFloat() / 2) a += PI.toFloat()
        return a
    }

    private data class RawDetection(
        val cx: Float, val cy: Float, val width: Float, val height: Float,
        val angle: Float, val score: Float, val classId: Int
    )

    private data class Point(val x: Float, val y: Float)

    private fun rotatedNms(detections: List<RawDetection>, scoreThr: Float, nmsThr: Float): List<Int> {
        val candidates = detections.indices
            .filter { detections[it].score > scoreThr }
            .sortedByDescending { detections[it].score }

        val keep = mutableListOf<Int>()
        val suppressed = BooleanArray(candidates.size)

        for (i in candidates.indices) {
            if (suppressed[i]) continue
            keep.add(candidates[i])

            for (j in (i + 1) until candidates.size) {
                if (!suppressed[j]) {
                    val iou = rotatedIou(detections[candidates[i]], detections[candidates[j]])
                    if (iou > nmsThr) suppressed[j] = true
                }
            }
        }

        return keep
    }

    private fun rotatedIou(a: RawDetection, b: RawDetection): Float {
        val cornersA = getCorners(a)
        val cornersB = getCorners(b)

        var intersection = cornersA.toMutableList()
        for (i in 0..3) {
            if (intersection.isEmpty()) break
            intersection = clipPolygon(intersection, cornersB[i], cornersB[(i + 1) % 4])
        }

        val intersectionArea = polygonArea(intersection)
        val unionArea = a.width * a.height + b.width * b.height - intersectionArea

        return if (unionArea > 0) intersectionArea / unionArea else 0f
    }

    private fun getCorners(d: RawDetection): List<Point> {
        val cosA = cos(d.angle)
        val sinA = sin(d.angle)
        val hw = d.width / 2
        val hh = d.height / 2

        return listOf(
            Point(d.cx - cosA * hw + sinA * hh, d.cy - sinA * hw - cosA * hh),
            Point(d.cx + cosA * hw + sinA * hh, d.cy + sinA * hw - cosA * hh),
            Point(d.cx + cosA * hw - sinA * hh, d.cy + sinA * hw + cosA * hh),
            Point(d.cx - cosA * hw - sinA * hh, d.cy - sinA * hw + cosA * hh)
        )
    }

    private fun clipPolygon(polygon: List<Point>, edgeStart: Point, edgeEnd: Point): MutableList<Point> {
        val result = mutableListOf<Point>()
        val edge = Point(edgeEnd.x - edgeStart.x, edgeEnd.y - edgeStart.y)

        for (i in polygon.indices) {
            val current = polygon[i]
            val previous = polygon[(i + polygon.size - 1) % polygon.size]

            val currentCross = cross(edge, Point(current.x - edgeStart.x, current.y - edgeStart.y))
            val previousCross = cross(edge, Point(previous.x - edgeStart.x, previous.y - edgeStart.y))

            if (currentCross >= 0) {
                if (previousCross < 0) {
                    result.add(intersection(previous, current, previousCross, currentCross))
                }
                result.add(current)
            } else if (previousCross >= 0) {
                result.add(intersection(previous, current, previousCross, currentCross))
            }
        }

        return result
    }

    private fun cross(a: Point, b: Point) = a.x * b.y - a.y * b.x

    private fun intersection(p1: Point, p2: Point, c1: Float, c2: Float): Point {
        val t = c1 / (c1 - c2)
        return Point(p1.x + t * (p2.x - p1.x), p1.y + t * (p2.y - p1.y))
    }

    private fun polygonArea(polygon: List<Point>): Float {
        if (polygon.size < 3) return 0f
        var area = 0f
        for (i in polygon.indices) {
            val j = (i + 1) % polygon.size
            area += polygon[i].x * polygon[j].y - polygon[j].x * polygon[i].y
        }
        return abs(area) / 2
    }

    fun close() {
        interpreter?.close()
        interpreter = null
        gpuDelegate?.close()
        gpuDelegate = null
        nnApiDelegate?.close()
        nnApiDelegate = null
        Log.i(TAG, "Detector closed")
    }
}