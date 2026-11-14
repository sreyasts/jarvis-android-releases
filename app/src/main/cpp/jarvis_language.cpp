#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <dlfcn.h>
#include <algorithm>
#include <cmath>
#include <memory>
#include <stdexcept>
#include <vector>
#include "ort/onnxruntime_c_api.h"

namespace {
const OrtApi & api() {
    static const OrtApi * runtime = [] {
        void * lib = dlopen("libonnxruntime.so", RTLD_NOW | RTLD_LOCAL);
        if (!lib) throw std::runtime_error("ONNX Runtime library unavailable");
        auto entry = reinterpret_cast<decltype(&OrtGetApiBase)>(dlsym(lib, "OrtGetApiBase"));
        const OrtApi * result = entry ? entry()->GetApi(ORT_API_VERSION) : nullptr;
        if (!result) throw std::runtime_error("ONNX Runtime API unavailable");
        return result;
    }();
    return *runtime;
}

void check(OrtStatus * status) {
    if (!status) return;
    api().ReleaseStatus(status);
    throw std::runtime_error("Language identification model failed");
}

struct Classifier {
    std::unique_ptr<OrtEnv, decltype(api().ReleaseEnv)> environment{nullptr, api().ReleaseEnv};
    std::unique_ptr<OrtSession, decltype(api().ReleaseSession)> session{nullptr, api().ReleaseSession};
    std::unique_ptr<OrtMemoryInfo, decltype(api().ReleaseMemoryInfo)> memory{nullptr, api().ReleaseMemoryInfo};

    explicit Classifier(AAssetManager * assets) {
        std::unique_ptr<AAsset, decltype(&AAsset_close)> model(
            AAssetManager_open(assets, "models/language/lid-ecapa-voxlingua107.int8.onnx", AASSET_MODE_BUFFER), AAsset_close);
        if (!model) throw std::runtime_error("Language identification model missing");
        const void * bytes = AAsset_getBuffer(model.get());
        if (!bytes) throw std::runtime_error("Language identification model unreadable");
        OrtEnv * env = nullptr;
        check(api().CreateEnv(ORT_LOGGING_LEVEL_ERROR, "JarvisLanguage", &env));
        environment.reset(env);
        OrtSessionOptions * rawOptions = nullptr;
        check(api().CreateSessionOptions(&rawOptions));
        std::unique_ptr<OrtSessionOptions, decltype(api().ReleaseSessionOptions)> options(rawOptions, api().ReleaseSessionOptions);
        check(api().SetIntraOpNumThreads(options.get(), 2));
        check(api().SetInterOpNumThreads(options.get(), 1));
        OrtSession * rawSession = nullptr;
        check(api().CreateSessionFromArray(environment.get(), bytes, AAsset_getLength(model.get()), options.get(), &rawSession));
        session.reset(rawSession);
        OrtMemoryInfo * rawMemory = nullptr;
        check(api().CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &rawMemory));
        memory.reset(rawMemory);
    }
};

void fail(JNIEnv * env, const std::exception & failure) {
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type) env->ThrowNew(type, failure.what());
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_jarvis_app_SpokenLanguageDetector_nativeLoad(JNIEnv * env, jobject, jobject manager) {
    try {
        auto assets = AAssetManager_fromJava(env, manager);
        if (!assets) throw std::runtime_error("Android assets unavailable");
        return reinterpret_cast<jlong>(new Classifier(assets));
    } catch (const std::exception & failure) {
        fail(env, failure);
        return 0;
    }
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_dev_jarvis_app_SpokenLanguageDetector_nativeScore(JNIEnv * env, jobject, jlong handle, jfloatArray audio) {
    try {
        const auto * classifier = reinterpret_cast<Classifier *>(handle);
        const auto length = env->GetArrayLength(audio);
        if (!classifier || length < 16000 || length > 240000) throw std::runtime_error("Invalid audio length");
        std::vector<float> samples(length);
        env->GetFloatArrayRegion(audio, 0, length, samples.data());
        if (env->ExceptionCheck()) return nullptr;
        const int64_t shape[] = {1, length};
        OrtValue * rawInput = nullptr;
        check(api().CreateTensorWithDataAsOrtValue(classifier->memory.get(), samples.data(), samples.size() * sizeof(float),
            shape, 2, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &rawInput));
        std::unique_ptr<OrtValue, decltype(api().ReleaseValue)> input(rawInput, api().ReleaseValue);
        const OrtValue * inputs[] = {input.get()};
        const char * inputNames[] = {"wav"};
        const char * outputNames[] = {"logp"};
        OrtValue * rawOutput = nullptr;
        OrtStatus * status = api().Run(classifier->session.get(), nullptr, inputNames, inputs, 1, outputNames, 1, &rawOutput);
        std::fill(samples.begin(), samples.end(), 0.0f);
        check(status);
        std::unique_ptr<OrtValue, decltype(api().ReleaseValue)> output(rawOutput, api().ReleaseValue);
        OrtTensorTypeAndShapeInfo * rawInfo = nullptr;
        check(api().GetTensorTypeAndShape(output.get(), &rawInfo));
        std::unique_ptr<OrtTensorTypeAndShapeInfo, decltype(api().ReleaseTensorTypeAndShapeInfo)> info(rawInfo, api().ReleaseTensorTypeAndShapeInfo);
        size_t count = 0;
        check(api().GetTensorShapeElementCount(info.get(), &count));
        if (count != 107) throw std::runtime_error("Invalid language model output");
        void * data = nullptr;
        check(api().GetTensorMutableData(output.get(), &data));
        const auto * logScores = static_cast<const float *>(data);
        jfloat scores[107];
        for (size_t i = 0; i < count; ++i) scores[i] = std::exp(logScores[i]);
        jfloatArray result = env->NewFloatArray(count);
        if (result) env->SetFloatArrayRegion(result, 0, count, scores);
        return result;
    } catch (const std::exception & failure) {
        fail(env, failure);
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_jarvis_app_SpokenLanguageDetector_nativeRelease(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<Classifier *>(handle);
}
