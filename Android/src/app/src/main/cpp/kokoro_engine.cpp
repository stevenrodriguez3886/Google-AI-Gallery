#include "kokoro_engine.h"
#include <android/log.h>
#include <fstream>
#include <cmath>
#include <algorithm>
#include <unordered_map>
#include <cctype>

#define TAG "KokoroEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

KokoroEngine::KokoroEngine(const std::string& modelPath,
                           const std::vector<float>& voiceEmbeddings,
                           AudioCircularBuffer<int16_t>* outputBuffer)
    : env_(ORT_LOGGING_LEVEL_WARNING, "KokoroEngine"),
      sessionOpts_(),
      outputBuffer_(outputBuffer),
      modelPath_(modelPath),
      voiceEmbeddings_(voiceEmbeddings) {
}

KokoroEngine::~KokoroEngine() {
    shutdown();
}

bool KokoroEngine::initialize() {
    try {
        sessionOpts_.SetIntraOpNumThreads(2);
        sessionOpts_.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);

        // Using CPU execution provider as default

        session_ = std::make_unique<Ort::Session>(env_, modelPath_.c_str(), sessionOpts_);
        LOGI("ONNX Runtime session created successfully.");

        // Log input/output info for introspection
        Ort::AllocatorWithDefaultOptions allocator;
        size_t numInputs = session_->GetInputCount();
        for (size_t i = 0; i < numInputs; i++) {
            auto name = session_->GetInputNameAllocated(i, allocator);
            LOGI("Input[%zu]: name='%s'", i, name.get());
        }
        size_t numOutputs = session_->GetOutputCount();
        for (size_t i = 0; i < numOutputs; i++) {
            auto name = session_->GetOutputNameAllocated(i, allocator);
            LOGI("Output[%zu]: name='%s'", i, name.get());
        }

        running_ = true;
        synthesisThread_ = std::thread(&KokoroEngine::synthesisLoop, this);
        return true;
    } catch (const std::exception& e) {
        LOGE("Failed to initialize KokoroEngine: %s", e.what());
        return false;
    }
}

std::vector<int64_t> KokoroEngine::textToPhonemeIds(const std::string& text) {
    // Kokoro phoneme vocabulary (espeak-ng en-us subset)
    static const std::unordered_map<char, std::vector<int64_t>> charToPhonemes = {
        {'a', {49}},  {'b', {21}}, {'c', {23}}, {'d', {22}},
        {'e', {50}},  {'f', {29}}, {'g', {24}}, {'h', {33}},
        {'i', {53}},  {'j', {38}}, {'k', {23}}, {'l', {37}},
        {'m', {35}},  {'n', {36}}, {'o', {55}}, {'p', {20}},
        {'q', {23}},  {'r', {34}}, {'s', {28}}, {'t', {22}},
        {'u', {58}},  {'v', {30}}, {'w', {32}}, {'x', {23, 28}},
        {'y', {54}},  {'z', {27}}, {' ', {0}},
    };

    std::vector<int64_t> ids;
    ids.push_back(0); // BOS
    for (unsigned char c : text) {
        char lower = std::tolower(static_cast<char>(c));
        auto it = charToPhonemes.find(lower);
        if (it != charToPhonemes.end()) {
            for (auto id : it->second) ids.push_back(id);
        }
    }
    ids.push_back(0); // EOS
    return ids;
}

std::vector<int16_t> KokoroEngine::runInference(const std::string& text, int voiceIndex, float speed) {
    try {
        Ort::AllocatorWithDefaultOptions allocator;

        std::vector<int64_t> phonemeIds = textToPhonemeIds(text);
        if (phonemeIds.empty()) return {};

        std::vector<int64_t> inputShape = {1, static_cast<int64_t>(phonemeIds.size())};
        Ort::Value inputTensor = Ort::Value::CreateTensor<int64_t>(
            allocator.GetInfo(), phonemeIds.data(), phonemeIds.size(), inputShape.data(), inputShape.size());

        int embeddingDim = 256;
        int styleIndex = phonemeIds.size();
        if (styleIndex >= 510) styleIndex = 509;

        if ((styleIndex + 1) * embeddingDim > voiceEmbeddings_.size()) {
            LOGE("Voice embeddings array is too small: size=%zu", voiceEmbeddings_.size());
            return {};
        }

        std::vector<float> styleEmbedding(voiceEmbeddings_.begin() + styleIndex * embeddingDim,
                                          voiceEmbeddings_.begin() + (styleIndex + 1) * embeddingDim);

        std::vector<int64_t> styleShape = {1, static_cast<int64_t>(embeddingDim)};
        Ort::Value styleTensor = Ort::Value::CreateTensor<float>(
            allocator.GetInfo(), styleEmbedding.data(), styleEmbedding.size(), styleShape.data(), styleShape.size());

        std::vector<float> speedData = {speed};
        std::vector<int64_t> speedShape = {1};
        Ort::Value speedTensor = Ort::Value::CreateTensor<float>(
            allocator.GetInfo(), speedData.data(), speedData.size(), speedShape.data(), speedShape.size());

        const char* inputNames[] = {"tokens", "style", "speed"};
        Ort::Value inputTensors[] = {std::move(inputTensor), std::move(styleTensor), std::move(speedTensor)};
        const char* outputNames[] = {"audio"};

        auto outputTensors = session_->Run(Ort::RunOptions{nullptr}, inputNames, inputTensors, 3, outputNames, 1);

        float* audioData = outputTensors[0].GetTensorMutableData<float>();
        size_t audioSize = outputTensors[0].GetTensorTypeAndShapeInfo().GetElementCount();

        std::vector<int16_t> pcmOutput(audioSize);
        for (size_t i = 0; i < audioSize; ++i) {
            float sample = audioData[i];
            sample = std::clamp(sample, -1.0f, 1.0f);
            pcmOutput[i] = static_cast<int16_t>(sample * 32767.0f);
        }

        return pcmOutput;
    } catch (const std::exception& e) {
        LOGE("Inference failed: %s", e.what());
        return {};
    }
}

void KokoroEngine::synthesisLoop() {
    while (running_) {
        std::string currentText;
        {
            std::unique_lock<std::mutex> lock(queueMutex_);
            queueCv_.wait(lock, [this] { return !textQueue_.empty() || !running_; });

            if (!running_ && textQueue_.empty()) {
                break;
            }

            currentText = textQueue_.front();
            textQueue_.pop();
        }

        if (!currentText.empty()) {
            std::vector<int16_t> pcm = runInference(currentText, 0, 1.0f);
            if (!pcm.empty()) {
                size_t pushed = 0;
                while (pushed < pcm.size() && running_) {
                    size_t toPush = pcm.size() - pushed;
                    if (outputBuffer_->push(pcm.data() + pushed, toPush)) {
                        pushed += toPush;
                    } else {
                        // Buffer full, sleep briefly and retry
                        std::this_thread::sleep_for(std::chrono::milliseconds(5));
                    }
                }
            }
        }
    }
}

void KokoroEngine::enqueue(const std::string& text) {
    std::lock_guard<std::mutex> lock(queueMutex_);
    textQueue_.push(text);
    queueCv_.notify_one();
}

bool KokoroEngine::synthesize(const std::string& text, int voiceIndex, float speed) {
    std::vector<int16_t> pcm = runInference(text, voiceIndex, speed);
    if (pcm.empty()) return false;
    
    // Blocking push for single synthesize call
    size_t pushed = 0;
    while(pushed < pcm.size()){
        size_t toPush = pcm.size() - pushed;
        size_t chunk = std::min(toPush, static_cast<size_t>(1024));
        if (outputBuffer_->push(pcm.data() + pushed, chunk)){
            pushed += chunk;
        } else {
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
        }
    }
    return true;
}

void KokoroEngine::shutdown() {
    running_ = false;
    queueCv_.notify_all();
    if (synthesisThread_.joinable()) {
        synthesisThread_.join();
    }
}
