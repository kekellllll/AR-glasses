#include <jni.h>
#include <android/log.h>
#include <string>
#include <thread>
#include "llama.h"
#include "common.h"

#define TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static llama_model *model = nullptr;
static llama_context *ctx = nullptr;
static llama_sampler *smpl = nullptr;

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_ultronai_productarmobile_llm_LlamaJni_loadModel(
    JNIEnv *env, jobject, jstring model_path_j) {

    if (smpl) { llama_sampler_free(smpl); smpl = nullptr; }
    if (ctx) { llama_free(ctx); ctx = nullptr; }
    if (model) { llama_model_free(model); model = nullptr; }

    const char *model_path = env->GetStringUTFChars(model_path_j, nullptr);
    LOGI("Loading model: %s", model_path);

    llama_backend_init();

    auto mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;

    model = llama_model_load_from_file(model_path, mparams);
    env->ReleaseStringUTFChars(model_path_j, model_path);

    if (!model) {
        LOGE("Failed to load model");
        return 0;
    }

    auto cparams = llama_context_default_params();
    cparams.n_ctx = 4096;
    cparams.n_threads = 4;
    cparams.n_threads_batch = 4;

    ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        LOGE("Failed to create context");
        llama_model_free(model);
        model = nullptr;
        return 0;
    }

    auto sparams = llama_sampler_chain_default_params();
    smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(42));

    LOGI("Model loaded successfully");
    return reinterpret_cast<jlong>(model);
}

JNIEXPORT jstring JNICALL
Java_com_ultronai_productarmobile_llm_LlamaJni_generate(
    JNIEnv *env, jobject, jstring prompt_j, jint max_tokens) {

    if (!model || !ctx) {
        return env->NewStringUTF("[Error: model not loaded]");
    }

    const char *prompt = env->GetStringUTFChars(prompt_j, nullptr);
    LOGI("Generating with prompt length: %zu", strlen(prompt));

    const llama_vocab *vocab = llama_model_get_vocab(model);

    std::vector<llama_token> tokens = common_tokenize(vocab, prompt, true, true);
    env->ReleaseStringUTFChars(prompt_j, prompt);

    llama_kv_cache_clear(ctx);

    int n_tokens = tokens.size();
    if (llama_decode(ctx, llama_batch_get_one(tokens.data(), n_tokens))) {
        LOGE("Failed to decode prompt");
        return env->NewStringUTF("[Error: decode failed]");
    }

    std::string result;
    for (int i = 0; i < max_tokens; i++) {
        llama_token new_token = llama_sampler_sample(smpl, ctx, -1);

        if (llama_vocab_is_eog(vocab, new_token)) {
            break;
        }

        char buf[256];
        int n = llama_token_to_piece(vocab, new_token, buf, sizeof(buf), 0, true);
        if (n > 0) {
            result.append(buf, n);
        }

        if (llama_decode(ctx, llama_batch_get_one(&new_token, 1))) {
            break;
        }
    }

    LOGI("Generated %zu chars", result.size());
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_com_ultronai_productarmobile_llm_LlamaJni_unload(JNIEnv *, jobject) {
    if (smpl) { llama_sampler_free(smpl); smpl = nullptr; }
    if (ctx) { llama_free(ctx); ctx = nullptr; }
    if (model) { llama_model_free(model); model = nullptr; }
    llama_backend_free();
    LOGI("Model unloaded");
}

}
