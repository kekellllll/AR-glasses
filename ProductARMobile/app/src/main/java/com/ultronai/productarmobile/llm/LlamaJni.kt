package com.ultronai.productarmobile.llm

class LlamaJni {
    companion object {
        init {
            System.loadLibrary("llama_jni")
        }
    }

    external fun loadModel(modelPath: String): Long
    external fun generate(prompt: String, maxTokens: Int): String
    external fun unload()
}
