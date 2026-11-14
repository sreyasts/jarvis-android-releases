#include <jni.h>
#include <llama.h>
#include <android/log.h>
#include <chrono>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
using Clock = std::chrono::steady_clock;
struct Request {
    JNIEnv *env;
    jobject observer;
    jmethodID cancelled;
    Clock::time_point deadline;
};
bool aborted(void *data) {
    auto *r = static_cast<Request *>(data);
    return Clock::now() >= r->deadline || r->env->CallBooleanMethod(r->observer, r->cancelled) || r->env->ExceptionCheck();
}
void quiet_log(ggml_log_level, const char *, void *) {}
void model_log(ggml_log_level level, const char *text, void *) {
    if (level == GGML_LOG_LEVEL_ERROR || level == GGML_LOG_LEVEL_WARN) __android_log_write(ANDROID_LOG_WARN, "JarvisModel", text);
}
struct LoadLogScope {
    LoadLogScope() { llama_log_set(model_log, nullptr); }
    ~LoadLogScope() { llama_log_set(quiet_log, nullptr); }
};
void fail(JNIEnv *env, const char *text) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), text);
}
std::string bytes(JNIEnv *env, jbyteArray input) {
    const auto size = env->GetArrayLength(input);
    std::string result(size, '\0');
    env->GetByteArrayRegion(input, 0, size, reinterpret_cast<jbyte *>(result.data()));
    return result;
}
struct Engine {
    llama_model *model = nullptr;
    ~Engine() { if (model) llama_model_free(model); }
};
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_jarvis_app_LocalLlm_nativeLoad(JNIEnv *env, jobject, jstring path, jobject observer) {
    try {
        LoadLogScope logs; // Model load diagnostics only. Generation never logs prompts or replies.
        llama_backend_init();
        auto engine = std::make_unique<Engine>();
        auto params = llama_model_default_params();
        params.n_gpu_layers = 0; // Portable CPU path for both MediaTek and Qualcomm.
        params.load_mode = LLAMA_LOAD_MODE_MMAP;
        params.use_extra_bufts = false; // Keep the quantized weights in the file mapping.
        const auto started = Clock::now();
        Request request{env, observer, env->GetMethodID(env->GetObjectClass(observer), "cancelled", "()Z"), started + std::chrono::seconds(120)};
        params.progress_callback = [](float, void *data) { return !aborted(data); };
        params.progress_callback_user_data = &request;
        const char *file = env->GetStringUTFChars(path, nullptr);
        engine->model = llama_model_load_from_file(file, params);
        env->ReleaseStringUTFChars(path, file);
        if (!engine->model) {
            if (Clock::now() >= request.deadline) throw std::runtime_error("Offline AI took too long to load. Close other apps and retry.");
            throw std::runtime_error("Offline AI could not load the model. Restart Jarvis and try again.");
        }
        __android_log_print(ANDROID_LOG_INFO, "JarvisModel", "Offline model loaded in %lld ms",
            static_cast<long long>(std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - started).count()));
        return reinterpret_cast<jlong>(engine.release());
    } catch (const std::exception &e) { fail(env, e.what()); return 0; }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_jarvis_app_LocalLlm_nativeGenerate(JNIEnv *env, jobject, jlong handle, jbyteArray rawPrompt,
                                         jbyteArray rawGrammar, jobject observer) {
    try {
        auto *engine = reinterpret_cast<Engine *>(handle);
        if (!engine || !engine->model) throw std::runtime_error("Offline model is not loaded.");
        const auto prompt = bytes(env, rawPrompt);
        const auto grammar = bytes(env, rawGrammar);
        auto *vocab = llama_model_get_vocab(engine->model);
        const int count = -llama_tokenize(vocab, prompt.data(), prompt.size(), nullptr, 0, true, true);
        constexpr int limit = 384;
        if (count <= 0 || count + limit > 2048) throw std::runtime_error("This request is too long for offline AI. Try a shorter request.");
        std::vector<llama_token> tokens(count);
        if (llama_tokenize(vocab, prompt.data(), prompt.size(), tokens.data(), count, true, true) != count)
            throw std::runtime_error("Could not read this request.");
        Request request{env, observer, env->GetMethodID(env->GetObjectClass(observer), "cancelled", "()Z"), Clock::now() + std::chrono::seconds(150)};
        auto params = llama_context_default_params();
        params.n_ctx = 2048; params.n_batch = 128; params.n_ubatch = 128;
        params.n_threads = 2; params.n_threads_batch = 4;
        params.abort_callback = aborted; params.abort_callback_data = &request;
        std::unique_ptr<llama_context, decltype(&llama_free)> ctx(llama_init_from_model(engine->model, params), llama_free);
        if (!ctx) throw std::runtime_error("Not enough memory for offline AI. Close other apps and try again.");
        std::unique_ptr<llama_batch_ext, decltype(&llama_batch_ext_free)> batch(llama_batch_ext_init(ctx.get()), llama_batch_ext_free);
        auto fill = [&](const llama_token *ids, int n, int pos) {
            llama_batch_ext_clear(batch.get());
            for (int i = 0; i < n; ++i) {
                auto idx = llama_batch_ext_add_token(batch.get(), 0, ids[i]);
                llama_pos p = pos + i;
                llama_batch_ext_set_pos(batch.get(), idx, &p);
            }
            llama_batch_ext_set_output_logits(batch.get(), n - 1, true);
        };
        for (int pos = 0; pos < count; pos += 128) {
            if (aborted(&request)) throw std::runtime_error("Offline AI stopped or timed out. Try a shorter request.");
            fill(tokens.data() + pos, std::min(128, count - pos), pos);
            if (llama_process(ctx.get(), LLAMA_PROCESS_TYPE_DECODE, batch.get()))
                throw std::runtime_error("Offline AI stopped while reading. Try again.");
        }
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
        auto *constraint = llama_sampler_init_grammar(vocab, grammar.c_str(), "root");
        if (!constraint) throw std::runtime_error("Could not initialize the response format.");
        llama_sampler_chain_add(sampler.get(), constraint);
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());
        std::string result;
        bool complete = false;
        for (int i = 0; i < limit; ++i) {
            if (aborted(&request)) throw std::runtime_error("Offline AI stopped or timed out. Try a shorter request.");
            auto token = llama_sampler_sample(sampler.get(), ctx.get(), -1);
            if (llama_vocab_is_eog(vocab, token)) { complete = true; break; }
            char part[512];
            int n = llama_token_to_piece(vocab, token, part, sizeof(part), 0, false);
            if (n < 0) throw std::runtime_error("Offline AI produced an invalid response.");
            result.append(part, n);
            fill(&token, 1, count + i);
            if (llama_process(ctx.get(), LLAMA_PROCESS_TYPE_DECODE, batch.get()))
                throw std::runtime_error("Offline AI stopped while replying. Try again.");
        }
        if (!complete) throw std::runtime_error("Offline AI reached its reply limit. Ask a shorter question.");
        auto output = env->NewByteArray(result.size());
        env->SetByteArrayRegion(output, 0, result.size(), reinterpret_cast<const jbyte *>(result.data()));
        return output;
    } catch (const std::exception &e) { fail(env, e.what()); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_jarvis_app_LocalLlm_nativeRelease(JNIEnv *, jobject, jlong handle) { delete reinterpret_cast<Engine *>(handle); }
