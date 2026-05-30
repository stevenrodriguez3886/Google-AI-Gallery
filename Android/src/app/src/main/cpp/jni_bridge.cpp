#include <jni.h>
#include <string>
#include <vector>
#include <stdexcept>
#include "kokoro_engine.h"

struct NativeContext {
    AudioCircularBuffer<int16_t>* audioBuffer;
    KokoroEngine* engine;
};

extern "C" {

JNIEXPORT jlong JNICALL 
Java_com_google_ai_edge_gallery_tts_KokoroTtsEngine_nativeCreate(JNIEnv* env, jclass /* clazz */, jstring modelPath, jfloatArray voiceData) {
    const char* cModelPath = env->GetStringUTFChars(modelPath, nullptr);
    
    jfloat* cVoiceData = env->GetFloatArrayElements(voiceData, nullptr);
    jsize voiceDataLen = env->GetArrayLength(voiceData);
    std::vector<float> voiceEmbeddings(cVoiceData, cVoiceData + voiceDataLen);
    env->ReleaseFloatArrayElements(voiceData, cVoiceData, JNI_ABORT);

    NativeContext* context = new NativeContext();
    context->audioBuffer = new AudioCircularBuffer<int16_t>(96000); // 2s at 24000Hz
    context->engine = new KokoroEngine(cModelPath, voiceEmbeddings, context->audioBuffer);

    env->ReleaseStringUTFChars(modelPath, cModelPath);

    if (!context->engine->initialize()) {
        delete context->engine;
        delete context->audioBuffer;
        delete context;
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), "Failed to initialize KokoroEngine");
        return 0;
    }

    return reinterpret_cast<jlong>(context);
}

JNIEXPORT void JNICALL 
Java_com_google_ai_edge_gallery_tts_KokoroTtsEngine_nativeEnqueue(JNIEnv* env, jobject /* this */, jlong handle, jstring text) {
    if (handle == 0) return;
    NativeContext* context = reinterpret_cast<NativeContext*>(handle);
    const char* cText = env->GetStringUTFChars(text, nullptr);
    context->engine->enqueue(cText);
    env->ReleaseStringUTFChars(text, cText);
}

JNIEXPORT jint JNICALL 
Java_com_google_ai_edge_gallery_tts_KokoroTtsEngine_nativePopPcm(JNIEnv* env, jobject /* this */, jlong handle, jshortArray outBuffer, jint maxSamples) {
    if (handle == 0) return 0;
    NativeContext* context = reinterpret_cast<NativeContext*>(handle);
    
    std::vector<int16_t> tempBuffer(maxSamples);
    size_t popped = context->audioBuffer->pop(tempBuffer.data(), maxSamples);
    
    if (popped > 0) {
        env->SetShortArrayRegion(outBuffer, 0, popped, tempBuffer.data());
    }
    return static_cast<jint>(popped);
}

JNIEXPORT jint JNICALL 
Java_com_google_ai_edge_gallery_tts_KokoroTtsEngine_nativeAvailable(JNIEnv* /* env */, jobject /* this */, jlong handle) {
    if (handle == 0) return 0;
    NativeContext* context = reinterpret_cast<NativeContext*>(handle);
    return static_cast<jint>(context->audioBuffer->available());
}

JNIEXPORT void JNICALL 
Java_com_google_ai_edge_gallery_tts_KokoroTtsEngine_nativeReset(JNIEnv* /* env */, jobject /* this */, jlong handle) {
    if (handle == 0) return;
    NativeContext* context = reinterpret_cast<NativeContext*>(handle);
    context->audioBuffer->reset();
}

JNIEXPORT void JNICALL 
Java_com_google_ai_edge_gallery_tts_KokoroTtsEngine_nativeDestroy(JNIEnv* /* env */, jobject /* this */, jlong handle) {
    if (handle == 0) return;
    NativeContext* context = reinterpret_cast<NativeContext*>(handle);
    context->engine->shutdown();
    delete context->engine;
    delete context->audioBuffer;
    delete context;
}

} // extern "C"
