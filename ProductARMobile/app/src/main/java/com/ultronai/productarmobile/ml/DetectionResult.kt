// app/src/main/java/com/ultronai/productarmobile/ml/Detection.kt
package com.ultronai.productarmobile.ml

data class Detection(
    val cx: Float,
    val cy: Float,
    val width: Float,
    val height: Float,
    val angle: Float,
    val score: Float,
    val classId: Int
)

data class DetectionResult(
    val detections: List<Detection>,
    val preprocessMs: Long,
    val inferenceMs: Long,
    val postprocessMs: Long
) {
    val totalMs: Long get() = preprocessMs + inferenceMs + postprocessMs
}