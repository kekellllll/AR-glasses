package com.ultronai.productarmobile.stt

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class WhisperStt(private val context: Context) {

    companion object {
        private const val TAG = "WhisperStt"
        private const val SAMPLE_RATE = 16000
        private const val MAX_RECORD_SECONDS = 15
    }

    private val jni = WhisperJni()
    @Volatile var isModelLoaded = false
        private set

    @Volatile var isRecording = false
        private set

    private var audioRecord: AudioRecord? = null
    private val audioBuffer = mutableListOf<Float>()
    private var recordThread: Thread? = null

    suspend fun loadModel() = withContext(Dispatchers.IO) {
        val modelFile = copyAssetIfNeeded("ggml-tiny.bin")
        val handle = jni.initContext(modelFile.absolutePath)
        isModelLoaded = handle != 0L
        Log.i(TAG, "Whisper model loaded: $isModelLoaded")
    }

    fun startRecording(): Boolean {
        if (!isModelLoaded) return false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) return false

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize.coerceAtLeast(SAMPLE_RATE * 2)
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed")
            return false
        }

        audioBuffer.clear()
        isRecording = true
        audioRecord?.startRecording()

        recordThread = Thread {
            try {
                val buffer = ShortArray(SAMPLE_RATE)
                val maxSamples = SAMPLE_RATE * MAX_RECORD_SECONDS
                while (isRecording && audioBuffer.size < maxSamples) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                    if (read > 0) {
                        synchronized(audioBuffer) {
                            for (i in 0 until read) {
                                audioBuffer.add(buffer[i].toFloat() / 32768f)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Recording thread interrupted", e)
            }
        }
        recordThread?.start()

        Log.i(TAG, "Recording started")
        return true
    }

    suspend fun stopAndTranscribe(): String = withContext(Dispatchers.IO) {
        isRecording = false
        recordThread?.join(2000)
        recordThread = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        val samples: FloatArray
        synchronized(audioBuffer) {
            samples = audioBuffer.toFloatArray()
        }

        if (samples.isEmpty()) return@withContext ""

        Log.i(TAG, "Transcribing ${samples.size} samples (${samples.size / SAMPLE_RATE}s)")
        val result = jni.transcribe(samples)
        result.trim()
    }

    private fun copyAssetIfNeeded(assetName: String): File {
        val outFile = File(context.cacheDir, assetName)
        if (!outFile.exists()) {
            context.assets.open(assetName).use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return outFile
    }

    fun release() {
        isRecording = false
        try { recordThread?.join(1000) } catch (_: Exception) {}
        recordThread = null
        audioRecord?.release()
        audioRecord = null
        jni.freeContext()
        isModelLoaded = false
    }
}
