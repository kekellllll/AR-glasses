package com.ultronai.productarmobile.rag

import android.util.Log
import kotlin.math.sqrt

/**
 * Simple in-memory vector store using cosine similarity.
 * For larger datasets, migrate to SQLite-vec extension.
 */
class VectorStore {

    companion object {
        private const val TAG = "VectorStore"
    }

    private val entries = mutableListOf<VectorEntry>()

    data class VectorEntry(
        val chunk: DocumentChunk,
        val vector: FloatArray
    )

    fun addAll(chunks: List<DocumentChunk>) {
        for (chunk in chunks) {
            val vec = chunk.embedding ?: continue
            entries.add(VectorEntry(chunk, vec))
        }
        Log.i(TAG, "Indexed ${entries.size} vectors")
    }

    fun clear() {
        entries.clear()
    }

    fun search(queryVec: FloatArray, topK: Int = 3): List<SearchResult> {
        if (entries.isEmpty()) return emptyList()

        return entries.map { entry ->
            SearchResult(entry.chunk, cosineSimilarity(queryVec, entry.vector))
        }
            .sortedByDescending { it.score }
            .take(topK)
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom > 0f) dot / denom else 0f
    }
}

data class SearchResult(
    val chunk: DocumentChunk,
    val score: Float
)
