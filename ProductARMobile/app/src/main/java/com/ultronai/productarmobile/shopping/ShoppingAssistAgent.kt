package com.ultronai.productarmobile.shopping

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.ultronai.productarmobile.llm.LlmClient
import com.ultronai.productarmobile.ml.Detection
import com.ultronai.productarmobile.ocr.OcrProcessor
import com.ultronai.productarmobile.rag.RagPipeline
import com.ultronai.productarmobile.stt.WhisperStt
import com.ultronai.productarmobile.tts.TtsManager

/**
 * Orchestrates the Shopping Assist demo flow:
 *   voice question -> STT -> OCR (on detected product) -> RAG -> LLM -> TTS
 */
class ShoppingAssistAgent(
    private val stt: WhisperStt,
    private val tts: TtsManager,
    private val ocr: OcrProcessor,
    private val rag: RagPipeline,
    private val llm: LlmClient,
    private val onStatusUpdate: (String) -> Unit
) {

    companion object {
        private const val TAG = "ShoppingAssist"
    }

    data class AssistResult(
        val query: String,
        val ocrText: String,
        val ragContext: String,
        val answer: String
    )

    suspend fun processQuery(
        spokenQuery: String,
        currentFrame: Bitmap?,
        topDetection: Detection?
    ): AssistResult {
        onStatusUpdate("Processing query...")

        var ocrText = ""
        if (currentFrame != null && topDetection != null) {
            onStatusUpdate("Running OCR on product...")
            val box = detectionToRect(topDetection)
            val ocrResult = ocr.recognizeFromCrop(currentFrame, box)
            ocrText = ocrResult.rawText
            Log.i(TAG, "OCR: ${ocrText.take(100)}...")
        }

        onStatusUpdate("Searching knowledge base...")
        val ragContext = rag.retrieve(spokenQuery, ocrText)
        Log.i(TAG, "RAG context: ${ragContext.take(200)}...")

        onStatusUpdate("Generating answer...")
        val answer = if (llm.isLoaded) {
            llm.generate(spokenQuery, ocrText, ragContext)
        } else {
            generateFallbackAnswer(spokenQuery, ocrText, ragContext)
        }
        Log.i(TAG, "Answer: $answer")

        onStatusUpdate("Speaking answer...")
        tts.speak(answer)

        return AssistResult(spokenQuery, ocrText, ragContext, answer)
    }

    private fun generateFallbackAnswer(query: String, ocr: String, rag: String): String {
        if (rag.isBlank() && ocr.isBlank()) {
            return "I don't have information about that product yet."
        }

        val lowerQuery = query.lowercase()
        if (lowerQuery.contains("allergen") || lowerQuery.contains("contain") || lowerQuery.contains("nut") || lowerQuery.contains("milk") || lowerQuery.contains("gluten")) {
            val allergenLine = rag.lines().find { it.lowercase().contains("allergen") }
            return if (allergenLine != null) {
                "Based on the product information: $allergenLine"
            } else if (ocr.isNotBlank()) {
                "From the label I can read: ${ocr.take(200)}"
            } else {
                "I couldn't find allergen information for this product."
            }
        }

        if (lowerQuery.contains("calorie") || lowerQuery.contains("nutrition") || lowerQuery.contains("fat") || lowerQuery.contains("sugar") || lowerQuery.contains("protein")) {
            val nutritionLine = rag.lines().find { it.lowercase().contains("nutrition") || it.lowercase().contains("calori") }
            return nutritionLine ?: "Nutrition information not available."
        }

        if (lowerQuery.contains("price") || lowerQuery.contains("cost") || lowerQuery.contains("how much")) {
            val priceLine = rag.lines().find { it.lowercase().contains("price") }
            return priceLine ?: "Price information not available."
        }

        return if (rag.isNotBlank()) {
            "Here's what I found: ${rag.lines().take(3).joinToString(" ")}"
        } else {
            "I found some text on the label: ${ocr.take(150)}"
        }
    }

    private fun detectionToRect(det: Detection): Rect {
        val halfW = det.width / 2
        val halfH = det.height / 2
        return Rect(
            (det.cx - halfW).toInt(),
            (det.cy - halfH).toInt(),
            (det.cx + halfW).toInt(),
            (det.cy + halfH).toInt()
        )
    }
}
