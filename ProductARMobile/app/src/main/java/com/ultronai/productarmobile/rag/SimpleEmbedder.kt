package com.ultronai.productarmobile.rag

import android.util.Log
import kotlin.math.sqrt

/**
 * Lightweight TF-IDF-style embedder for on-device RAG when a full embedding model
 * (all-MiniLM-L6-v2) is not yet deployed. Uses bag-of-words with IDF weighting.
 * Produces consistent-dimension vectors based on a fixed vocabulary built from the corpus.
 *
 * Upgrade path: replace with TFLite all-MiniLM-L6-v2 for semantic embeddings.
 */
class SimpleEmbedder {

    companion object {
        private const val TAG = "SimpleEmbedder"
        private const val MAX_VOCAB = 2048
    }

    private var vocab: Map<String, Int> = emptyMap()
    private var idf: FloatArray = FloatArray(0)
    private var dimension: Int = 0

    fun buildVocab(documents: List<String>) {
        val df = mutableMapOf<String, Int>()
        val n = documents.size

        for (doc in documents) {
            val uniqueTokens = tokenize(doc).toSet()
            for (token in uniqueTokens) {
                df[token] = (df[token] ?: 0) + 1
            }
        }

        val sorted = df.entries
            .filter { it.value >= 1 }
            .sortedByDescending { it.value }
            .take(MAX_VOCAB)

        vocab = sorted.mapIndexed { idx, entry -> entry.key to idx }.toMap()
        dimension = vocab.size

        idf = FloatArray(dimension)
        for ((token, idx) in vocab) {
            idf[idx] = Math.log((n + 1.0) / ((df[token] ?: 0) + 1.0)).toFloat() + 1f
        }

        Log.i(TAG, "Vocabulary: $dimension terms from $n documents")
    }

    fun embed(text: String): FloatArray {
        val vec = FloatArray(dimension)
        val tokens = tokenize(text)
        val tf = mutableMapOf<String, Int>()
        for (t in tokens) tf[t] = (tf[t] ?: 0) + 1

        for ((token, count) in tf) {
            val idx = vocab[token] ?: continue
            vec[idx] = count.toFloat() * idf[idx]
        }

        val norm = sqrt(vec.sumOf { (it * it).toDouble() }).toFloat()
        if (norm > 0f) {
            for (i in vec.indices) vec[i] /= norm
        }
        return vec
    }

    private fun tokenize(text: String): List<String> {
        return text.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 2 }
    }
}
