package com.ultronai.productarmobile.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class OcrProcessor {

    companion object {
        private const val TAG = "OcrProcessor"

        private val ALLERGEN_KEYWORDS = setOf(
            "milk", "egg", "peanut", "tree nut", "soy", "wheat", "fish",
            "shellfish", "sesame", "gluten", "lactose", "casein", "whey",
            "almond", "walnut", "pecan", "cashew", "hazelnut", "pistachio"
        )

        private val NUTRITION_PATTERN = Regex(
            """(\d+\.?\d*)\s*(g|mg|kcal|cal|%|oz|ml)""", RegexOption.IGNORE_CASE
        )
    }

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.Builder().build())

    suspend fun recognizeText(bitmap: Bitmap): OcrResult = suspendCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { text ->
                val rawText = text.text
                val allergens = extractAllergens(rawText)
                val nutritionLines = extractNutritionFacts(rawText)
                Log.i(TAG, "OCR: ${rawText.length} chars, ${allergens.size} allergens")
                cont.resume(OcrResult(rawText, allergens, nutritionLines))
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "OCR failed", e)
                cont.resume(OcrResult("", emptyList(), emptyList()))
            }
    }

    suspend fun recognizeFromCrop(bitmap: Bitmap, box: Rect): OcrResult {
        val safeLeft = box.left.coerceIn(0, bitmap.width - 1)
        val safeTop = box.top.coerceIn(0, bitmap.height - 1)
        val safeWidth = (box.width()).coerceAtMost(bitmap.width - safeLeft).coerceAtLeast(1)
        val safeHeight = (box.height()).coerceAtMost(bitmap.height - safeTop).coerceAtLeast(1)

        val cropped = Bitmap.createBitmap(bitmap, safeLeft, safeTop, safeWidth, safeHeight)
        val result = recognizeText(cropped)
        if (cropped != bitmap) cropped.recycle()
        return result
    }

    private fun extractAllergens(text: String): List<String> {
        val lower = text.lowercase()
        return ALLERGEN_KEYWORDS.filter { lower.contains(it) }
    }

    private fun extractNutritionFacts(text: String): List<String> {
        return text.lines().filter { line ->
            NUTRITION_PATTERN.containsMatchIn(line)
        }
    }

    fun close() {
        recognizer.close()
    }
}

data class OcrResult(
    val rawText: String,
    val allergens: List<String>,
    val nutritionLines: List<String>
)
