package com.ultronai.productarmobile.llm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class LlmClient(private val context: Context) {

    companion object {
        private const val TAG = "LlmClient"
        private const val MODEL_FILENAME = "phi-3-mini-4k-instruct-q4_k_m.gguf"
        private const val MAX_TOKENS = 256

        private const val SYSTEM_PROMPT = """You are a retail shopping assistant. Answer ONLY from the provided context. If the information is not in the context, say "I don't have that information." Be concise and helpful."""
    }

    private val jni = LlamaJni()
    @Volatile var isLoaded = false
        private set

    suspend fun loadModel() = withContext(Dispatchers.IO) {
        val modelFile = getModelFile()
        if (!modelFile.exists()) {
            Log.e(TAG, "Model file not found: ${modelFile.absolutePath}")
            Log.i(TAG, "Download it with: download_models.sh")
            return@withContext
        }

        val handle = jni.loadModel(modelFile.absolutePath)
        isLoaded = handle != 0L
        Log.i(TAG, "LLM loaded: $isLoaded (file=${modelFile.length() / 1_000_000}MB)")
    }

    suspend fun generate(
        userQuery: String,
        ocrContext: String = "",
        ragContext: String = ""
    ): String = withContext(Dispatchers.IO) {
        if (!isLoaded) return@withContext "[LLM not loaded]"

        val prompt = buildPrompt(userQuery, ocrContext, ragContext)
        Log.i(TAG, "Prompt length: ${prompt.length}")

        val result = jni.generate(prompt, MAX_TOKENS)
        result.trim()
    }

    private fun buildPrompt(query: String, ocr: String, rag: String): String {
        val sb = StringBuilder()
        sb.append("<|system|>\n$SYSTEM_PROMPT")
        if (rag.isNotBlank()) {
            sb.append("\n\nContext:\n$rag")
        }
        if (ocr.isNotBlank()) {
            sb.append("\n\nOCR from product label:\n$ocr")
        }
        sb.append("<|end|>\n")
        sb.append("<|user|>\n$query<|end|>\n")
        sb.append("<|assistant|>\n")
        return sb.toString()
    }

    fun getModelFile(): File {
        return File(context.getExternalFilesDir(null), "models/$MODEL_FILENAME")
    }

    fun isModelDownloaded(): Boolean = getModelFile().exists()

    fun unload() {
        if (isLoaded) {
            jni.unload()
            isLoaded = false
        }
    }
}
