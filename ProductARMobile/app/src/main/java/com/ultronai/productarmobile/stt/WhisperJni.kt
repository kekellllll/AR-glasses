package com.ultronai.productarmobile.stt

class WhisperJni {
    companion object {
        init {
            System.loadLibrary("whisper_jni")
        }
    }

    external fun initContext(modelPath: String): Long
    external fun transcribe(audioData: FloatArray): String
    external fun freeContext()
}
