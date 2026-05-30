#pragma once

#include <string>
#include <vector>
#include <queue>
#include <thread>
#include <mutex>
#include <condition_variable>
#include <atomic>
#include <memory>
#include <cstdint>
#include "onnxruntime_cxx_api.h"
#include "audio_buffer.h"

class KokoroEngine {
public:
    KokoroEngine(const std::string& modelPath,
                 const std::vector<float>& voiceEmbeddings,
                 AudioCircularBuffer<int16_t>* outputBuffer);
    ~KokoroEngine();

    bool initialize();
    bool synthesize(const std::string& text, int voiceIndex = 0, float speed = 1.0f);
    void enqueue(const std::string& text);
    void shutdown();

    static constexpr int kSampleRate  = 24000;
    static constexpr int kNumChannels = 1;
    static constexpr int kBitDepth    = 16;

    // Accessible from JNI bridge for popPcm / available / reset
    AudioCircularBuffer<int16_t>* getOutputBuffer() { return outputBuffer_; }

private:
    Ort::Env                          env_;
    Ort::SessionOptions               sessionOpts_;
    std::unique_ptr<Ort::Session>     session_;
    std::vector<float>                voiceEmbeddings_;
    int                               numVoices_ = 0;
    int                               embeddingDim_ = 0;
    AudioCircularBuffer<int16_t>*     outputBuffer_;     // NOT owned
    std::string                       modelPath_;
    std::thread                       synthesisThread_;
    std::queue<std::string>           textQueue_;
    std::mutex                        queueMutex_;
    std::condition_variable           queueCv_;
    std::atomic<bool>                 running_{false};

    void synthesisLoop();
    std::vector<int16_t> runInference(const std::string& text, int voiceIndex, float speed);
    std::vector<int64_t> textToPhonemeIds(const std::string& text);
};
