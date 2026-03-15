#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include "whisper.h"

#define TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static struct whisper_context *w_ctx = nullptr;

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_ultronai_productarmobile_stt_WhisperJni_initContext(
    JNIEnv *env, jobject, jstring model_path_j) {

    if (w_ctx) { whisper_free(w_ctx); w_ctx = nullptr; }

    const char *model_path = env->GetStringUTFChars(model_path_j, nullptr);
    LOGI("Loading whisper model: %s", model_path);

    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;

    w_ctx = whisper_init_from_file_with_params(model_path, cparams);
    env->ReleaseStringUTFChars(model_path_j, model_path);

    if (!w_ctx) {
        LOGE("Failed to load whisper model");
        return 0;
    }

    LOGI("Whisper model loaded");
    return reinterpret_cast<jlong>(w_ctx);
}

JNIEXPORT jstring JNICALL
Java_com_ultronai_productarmobile_stt_WhisperJni_transcribe(
    JNIEnv *env, jobject, jfloatArray audio_j) {

    if (!w_ctx) {
        return env->NewStringUTF("[Error: whisper not initialized]");
    }

    jint n_samples = env->GetArrayLength(audio_j);
    jfloat *audio = env->GetFloatArrayElements(audio_j, nullptr);

    LOGI("Transcribing %d samples", n_samples);

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.language = "en";
    params.n_threads = 4;
    params.print_progress = false;
    params.print_timestamps = false;
    params.no_context = true;
    params.single_segment = true;

    int ret = whisper_full(w_ctx, params, audio, n_samples);
    env->ReleaseFloatArrayElements(audio_j, audio, 0);

    if (ret != 0) {
        LOGE("Whisper transcription failed: %d", ret);
        return env->NewStringUTF("[Error: transcription failed]");
    }

    std::string result;
    int n_segments = whisper_full_n_segments(w_ctx);
    for (int i = 0; i < n_segments; i++) {
        const char *text = whisper_full_get_segment_text(w_ctx, i);
        if (text) {
            result += text;
        }
    }

    LOGI("Transcribed: %s", result.c_str());
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_com_ultronai_productarmobile_stt_WhisperJni_freeContext(JNIEnv *, jobject) {
    if (w_ctx) {
        whisper_free(w_ctx);
        w_ctx = nullptr;
        LOGI("Whisper context freed");
    }
}

}
