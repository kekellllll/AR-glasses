package com.ultronai.productarmobile.shelf

import android.util.Log
import com.ultronai.productarmobile.llm.LlmClient
import com.ultronai.productarmobile.ml.Detection
import com.ultronai.productarmobile.rag.PlanogramZone
import com.ultronai.productarmobile.rag.RagPipeline
import com.ultronai.productarmobile.tts.TtsManager

/**
 * Orchestrates the Shelf Analysis demo flow:
 *   detect all products -> map to zones -> compare vs planogram -> report misplacements -> TTS
 */
class ShelfAnalysisAgent(
    private val rag: RagPipeline,
    private val llm: LlmClient,
    private val tts: TtsManager,
    private val onStatusUpdate: (String) -> Unit
) {

    companion object {
        private const val TAG = "ShelfAnalysis"
    }

    data class ShelfIssue(
        val type: IssueType,
        val description: String,
        val zone: PlanogramZone?,
        val detectedSku: String = ""
    )

    enum class IssueType { MISPLACED, MISSING, EXTRA }

    data class AnalysisResult(
        val issues: List<ShelfIssue>,
        val summary: String
    )

    suspend fun analyzeShelf(
        detections: List<Detection>,
        frameHeight: Int,
        classIdToSku: Map<Int, String> = emptyMap()
    ): AnalysisResult {
        onStatusUpdate("Analyzing shelf...")

        val zones = rag.planogram
        if (zones.isEmpty()) {
            val msg = "No planogram data available for analysis."
            tts.speak(msg)
            return AnalysisResult(emptyList(), msg)
        }

        val detectedSkus = detections.mapNotNull { det ->
            classIdToSku[det.classId]
        }

        onStatusUpdate("Mapping products to zones...")
        val zonedDetections = mapDetectionsToZones(detections, frameHeight, zones, classIdToSku)

        val issues = mutableListOf<ShelfIssue>()

        for (zone in zones) {
            val detectedInZone = zonedDetections[zone.zoneId] ?: emptyList()

            for (expectedSku in zone.expectedSkus) {
                if (expectedSku !in detectedInZone) {
                    val foundElsewhere = detectedSkus.contains(expectedSku)
                    if (foundElsewhere) {
                        val product = rag.findProductBySku(expectedSku)
                        issues.add(ShelfIssue(
                            IssueType.MISPLACED,
                            "${product?.name ?: expectedSku} should be in Zone ${zone.zoneId} (${zone.description})",
                            zone, expectedSku
                        ))
                    } else {
                        val product = rag.findProductBySku(expectedSku)
                        issues.add(ShelfIssue(
                            IssueType.MISSING,
                            "${product?.name ?: expectedSku} is missing from Zone ${zone.zoneId} (${zone.description})",
                            zone, expectedSku
                        ))
                    }
                }
            }

            for (sku in detectedInZone) {
                if (sku !in zone.expectedSkus) {
                    val product = rag.findProductBySku(sku)
                    issues.add(ShelfIssue(
                        IssueType.EXTRA,
                        "${product?.name ?: sku} does not belong in Zone ${zone.zoneId} (${zone.description})",
                        zone, sku
                    ))
                }
            }
        }

        onStatusUpdate("Generating guidance...")
        val summary = generateSummary(issues)

        tts.speak(summary)
        Log.i(TAG, "Analysis: ${issues.size} issues found")

        return AnalysisResult(issues, summary)
    }

    private fun mapDetectionsToZones(
        detections: List<Detection>,
        frameHeight: Int,
        zones: List<PlanogramZone>,
        classIdToSku: Map<Int, String>
    ): Map<Int, List<String>> {
        val result = mutableMapOf<Int, MutableList<String>>()

        val shelfBands = zones.groupBy { it.shelfLevel }
        val topZones = shelfBands["top"] ?: emptyList()
        val middleZones = shelfBands["middle"] ?: emptyList()
        val bottomZones = shelfBands["bottom"] ?: emptyList()

        for (det in detections) {
            val sku = classIdToSku[det.classId] ?: continue
            val normalizedY = det.cy / frameHeight

            val targetZones = when {
                normalizedY < 0.33f -> topZones
                normalizedY < 0.66f -> middleZones
                else -> bottomZones
            }

            val zone = targetZones.firstOrNull()
            if (zone != null) {
                result.getOrPut(zone.zoneId) { mutableListOf() }.add(sku)
            }
        }

        return result
    }

    private suspend fun generateSummary(issues: List<ShelfIssue>): String {
        if (issues.isEmpty()) {
            return "Shelf is compliant. All products are in their correct positions."
        }

        val issueText = issues.joinToString("\n") { "- ${it.type}: ${it.description}" }

        if (llm.isLoaded) {
            val prompt = """Based on the shelf analysis, generate concise guidance for the store associate.
Issues found:
$issueText
Provide step-by-step instructions to fix the issues."""
            return llm.generate(prompt)
        }

        val misplaced = issues.filter { it.type == IssueType.MISPLACED }
        val missing = issues.filter { it.type == IssueType.MISSING }

        return buildString {
            append("Found ${issues.size} shelf issues. ")
            if (misplaced.isNotEmpty()) {
                append("${misplaced.size} misplaced: ")
                append(misplaced.joinToString("; ") { it.description })
                append(". ")
            }
            if (missing.isNotEmpty()) {
                append("${missing.size} missing: ")
                append(missing.joinToString("; ") { it.description })
                append(".")
            }
        }
    }
}
