package com.ultronai.productarmobile.rag

import android.content.Context
import android.util.Log

class RagPipeline(context: Context) {

    companion object {
        private const val TAG = "RagPipeline"
        private const val TOP_K = 3
    }

    private val kb = KnowledgeBase(context)
    private val embedder = SimpleEmbedder()
    private val vectorStore = VectorStore()

    val products get() = kb.products
    val planogram get() = kb.planogram

    fun initialize() {
        kb.load()
        val chunks = kb.getAllChunks()
        if (chunks.isEmpty()) {
            Log.w(TAG, "No knowledge base data found")
            return
        }

        val docs = chunks.map { it.text }
        embedder.buildVocab(docs)

        for (chunk in chunks) {
            chunk.embedding = embedder.embed(chunk.text)
        }

        vectorStore.clear()
        vectorStore.addAll(chunks)
        Log.i(TAG, "RAG initialized with ${chunks.size} chunks")
    }

    fun retrieve(query: String, ocrText: String = ""): String {
        val combined = "$query $ocrText".trim()
        if (combined.isBlank()) return ""

        val queryVec = embedder.embed(combined)
        if (queryVec.isEmpty()) return ""
        val results = vectorStore.search(queryVec, TOP_K)

        if (results.isEmpty()) return ""

        return results.joinToString("\n---\n") { r ->
            "[score=%.2f] %s".format(r.score, r.chunk.text.trim())
        }
    }

    fun findProductBySku(sku: String) = kb.findProductBySku(sku)
    fun findProductByName(name: String) = kb.findProductByName(name)
}
