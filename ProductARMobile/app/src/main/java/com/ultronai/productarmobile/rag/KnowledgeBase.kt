package com.ultronai.productarmobile.rag

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class KnowledgeBase(private val context: Context) {

    companion object {
        private const val TAG = "KnowledgeBase"
    }

    private val gson = Gson()
    var products: List<Product> = emptyList()
        private set
    var planogram: List<PlanogramZone> = emptyList()
        private set

    fun load() {
        products = loadJsonAsset("products.json")
        planogram = loadJsonAsset("planogram.json")
        Log.i(TAG, "Loaded ${products.size} products, ${planogram.size} planogram zones")
    }

    fun getProductChunks(): List<DocumentChunk> {
        return products.map { p ->
            val text = buildString {
                append("Product: ${p.name}\n")
                append("Brand: ${p.brand}\n")
                append("SKU: ${p.sku}\n")
                if (p.ingredients.isNotBlank()) append("Ingredients: ${p.ingredients}\n")
                if (p.allergens.isNotEmpty()) append("Allergens: ${p.allergens.joinToString(", ")}\n")
                if (p.nutritionFacts.isNotBlank()) append("Nutrition: ${p.nutritionFacts}\n")
                if (p.price.isNotBlank()) append("Price: ${p.price}\n")
            }
            DocumentChunk(id = "product_${p.sku}", text = text, type = ChunkType.PRODUCT)
        }
    }

    fun getPlanogramChunks(): List<DocumentChunk> {
        return planogram.map { z ->
            val text = buildString {
                append("Zone ${z.zoneId}: ${z.description}\n")
                append("Shelf: ${z.shelfLevel}\n")
                append("Expected products: ${z.expectedSkus.joinToString(", ")}\n")
            }
            DocumentChunk(id = "zone_${z.zoneId}", text = text, type = ChunkType.PLANOGRAM)
        }
    }

    fun getAllChunks(): List<DocumentChunk> = getProductChunks() + getPlanogramChunks()

    fun findProductBySku(sku: String): Product? = products.find { it.sku == sku }

    fun findProductByName(name: String): Product? {
        val lower = name.lowercase()
        return products.find { it.name.lowercase().contains(lower) }
    }

    private inline fun <reified T> loadJsonAsset(filename: String): List<T> {
        return try {
            val json = context.assets.open(filename).bufferedReader().use { it.readText() }
            val type = TypeToken.getParameterized(List::class.java, T::class.java).type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load $filename: ${e.message}")
            emptyList()
        }
    }
}

data class Product(
    val name: String = "",
    val brand: String = "",
    val sku: String = "",
    val ingredients: String = "",
    val allergens: List<String> = emptyList(),
    val nutritionFacts: String = "",
    val price: String = "",
    val category: String = ""
)

data class PlanogramZone(
    val zoneId: Int = 0,
    val description: String = "",
    val shelfLevel: String = "",
    val expectedSkus: List<String> = emptyList()
)

data class DocumentChunk(
    val id: String,
    val text: String,
    val type: ChunkType,
    var embedding: FloatArray? = null
) {
    override fun equals(other: Any?) = other is DocumentChunk && id == other.id
    override fun hashCode() = id.hashCode()
}

enum class ChunkType { PRODUCT, PLANOGRAM }
