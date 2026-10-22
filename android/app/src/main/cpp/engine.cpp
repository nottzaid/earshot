// Capture -> DSP -> network engine.
//
// The Oboe callback only copies samples into a lock-free ring and signals the
// worker. The worker owns everything that may block or allocate: DSP, metering,
// the ROC sender, and reopening the input stream after a route change.

#include <android/log.h>
#include <jni.h>
#include <oboe/Oboe.h>
#include <semaphore.h>

#include <atomic>
#include <cerrno>
#include <cmath>
#include <ctime>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "es_link.h"

#define LOG_TAG "earshot"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int kRate = ES_RATE;

// Single-producer single-consumer ring of mono floats.
class Ring {
public:
    explicit Ring(size_t capacity) : buf_(capacity), cap_(capacity) {}

    size_t write(const float* src, size_t n) {
        size_t w = w_.load(std::memory_order_relaxed);
        size_t r = r_.load(std::memory_order_acquire);
        size_t room = cap_ - (w - r);
        if (n > room) n = room;
        for (size_t i = 0; i < n; i++) buf_[(w + i) % cap_] = src[i];
        w_.store(w + n, std::memory_order_release);
        return n;
    }

    size_t read(float* dst, size_t n) {
        size_t r = r_.load(std::memory_order_relaxed);
        size_t w = w_.load(std::memory_order_acquire);
        if (n > w - r) n = w - r;
        for (size_t i = 0; i < n; i++) dst[i] = buf_[(r + i) % cap_];
        r_.store(r + n, std::memory_order_release);
        return n;
    }

    void clear() { r_.store(w_.load(std::memory_order_acquire), std::memory_order_release); }

private:
    std::vector<float> buf_;
    size_t cap_;
    std::atomic<size_t> w_{0}, r_{0};
};

// 2nd-order Butterworth high-pass (transposed direct form II).
struct HighPass {
    float b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0, z1 = 0, z2 = 0;

    void design(float hz) {
        const float w = 2.0f * float(M_PI) * hz / kRate;
        const float c = std::cos(w), alpha = std::sin(w) / (2.0f * float(M_SQRT1_2));
        const float a0 = 1 + alpha;
        b0 = (1 + c) / 2 / a0;
        b1 = -(1 + c) / a0;
        b2 = (1 + c) / 2 / a0;
        a1 = -2 * c / a0;
        a2 = (1 - alpha) / a0;
    }

    float process(float x) {
        float y = b0 * x + z1;
        z1 = b1 * x - a1 * y + z2;
        z2 = b2 * x - a2 * y;
        return y;
    }

    void reset() { z1 = z2 = 0; }
};

struct Config {
    std::string host;
    int deviceId = 0;
    oboe::InputPreset preset = oboe::InputPreset::VoicePerformance;
    es_link_params link{};
};

class Engine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    // -- live controls (any thread)
    std::atomic<float> gainTarget{1.0f};
    std::atomic<bool> muted{false};
    std::atomic<bool> highPass{false};

    // -- meter / status (worker writes, UI reads)
    std::atomic<float> meterPeak{0}, meterRms{0};
    std::atomic<int64_t> clipUntilNs{0};
    std::atomic<int> receivers{0};
    std::atomic<bool> mmap{false}, exclusive{false};
    std::atomic<int> burstFrames{0};
    std::atomic<int> overruns{0};
    std::atomic<int> state{0};  // 0 stopped, 1 running, -1 capture failed, -2 link failed
    std::atomic<int> sessionId{0};

    bool start(const Config& cfg) {
        stop();
        cfg_ = cfg;
        cfg_.link.host = cfg_.host.c_str();
        link_ = es_link_open(&cfg_.link);
        if (!link_) {
            state = -2;
            return false;
        }
        if (!openStream()) {
            es_link_close(link_);
            link_ = nullptr;
            state = -1;
            return false;
        }
        running_ = true;
        state = 1;
        worker_ = std::thread([this] { work(); });
        return true;
    }

    void stop() {
        if (worker_.joinable()) {
            running_ = false;
            sem_post(&ready_);
            worker_.join();
        }
        closeStream();
        if (link_) {
            es_link_close(link_);
            link_ = nullptr;
        }
        ring_.clear();
        meterPeak = 0;
        meterRms = 0;
        receivers = 0;
        if (state > 0) state = 0;
    }

    Engine() { sem_init(&ready_, 0, 0); }

    // -- Oboe callbacks (audio thread: no locks, no allocation)

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void* data, int32_t frames) override {
        size_t n = ring_.write(static_cast<const float*>(data), size_t(frames));
        if (n < size_t(frames)) overruns.fetch_add(1, std::memory_order_relaxed);
        sem_post(&ready_);
        return oboe::DataCallbackResult::Continue;
    }

    void onErrorAfterClose(oboe::AudioStream*, oboe::Result error) override {
        LOGW("input stream closed: %s", oboe::convertToText(error));
        streamDied_ = true;
        sem_post(&ready_);
    }

private:
    bool openStream() {
        oboe::AudioStreamBuilder b;
        b.setDirection(oboe::Direction::Input)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(oboe::ChannelCount::Mono)
            ->setSampleRate(kRate)
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::High)
            ->setInputPreset(cfg_.preset)
            ->setSessionId(oboe::SessionId::Allocate)
            ->setDataCallback(this)
            ->setErrorCallback(this);
        if (cfg_.deviceId > 0) b.setDeviceId(cfg_.deviceId);

        oboe::Result r = b.openStream(stream_);
        if (r != oboe::Result::OK) {
            LOGW("openStream failed: %s", oboe::convertToText(r));
            return false;
        }
        mmap = oboe::OboeExtensions::isMMapUsed(stream_.get());
        exclusive = stream_->getSharingMode() == oboe::SharingMode::Exclusive;
        burstFrames = stream_->getFramesPerBurst();
        sessionId = int(stream_->getSessionId());
        LOGI("input: rate=%d burst=%d mmap=%d exclusive=%d preset=%d device=%d",
             stream_->getSampleRate(), stream_->getFramesPerBurst(), int(mmap.load()),
             int(exclusive.load()), int(cfg_.preset), stream_->getDeviceId());
        r = stream_->requestStart();
        if (r != oboe::Result::OK) {
            LOGW("requestStart failed: %s", oboe::convertToText(r));
            closeStream();
            return false;
        }
        return true;
    }

    void closeStream() {
        if (stream_) {
            stream_->stop();
            stream_->close();
            stream_.reset();
        }
    }

    static int64_t nowNs() {
        timespec ts;
        clock_gettime(CLOCK_MONOTONIC, &ts);
        return int64_t(ts.tv_sec) * 1000000000 + ts.tv_nsec;
    }

    void work() {
        constexpr size_t kMax = 512;
        float buf[kMax];
        // One-pole smoothing: ~10 ms for gain/mute moves, so changes never click.
        const float smooth = 1.0f - std::exp(-1.0f / (0.010f * kRate));
        // Limiter: instant attack, ~150 ms release, ceiling just under full scale.
        const float release = 1.0f - std::exp(-1.0f / (0.150f * kRate));
        constexpr float kCeiling = 0.966f;  // -0.3 dBFS
        float gain = gainTarget.load(), muteGain = muted ? 0.0f : 1.0f, limit = 1.0f;
        bool hpOn = false;
        int64_t lastQuery = 0;
        HighPass hp;
        hp.design(80.0f);

        while (running_) {
            timespec deadline;
            clock_gettime(CLOCK_REALTIME, &deadline);
            deadline.tv_nsec += 100 * 1000000;
            if (deadline.tv_nsec >= 1000000000) {
                deadline.tv_sec++;
                deadline.tv_nsec -= 1000000000;
            }
            if (sem_timedwait(&ready_, &deadline) != 0 && errno != ETIMEDOUT) continue;
            if (!running_) break;

            if (streamDied_.exchange(false)) {
                closeStream();
                ring_.clear();
                if (!openStream()) {
                    state = -1;  // the service sees this and decides what to do
                    break;
                }
            }

            size_t n;
            while ((n = ring_.read(buf, kMax)) > 0) {
                const bool wantHp = highPass.load(std::memory_order_relaxed);
                if (wantHp != hpOn) {
                    hpOn = wantHp;
                    hp.reset();
                }
                const float gT = gainTarget.load(std::memory_order_relaxed);
                const float mT = muted.load(std::memory_order_relaxed) ? 0.0f : 1.0f;
                float peak = 0, sumSq = 0;
                bool clipped = false;
                for (size_t i = 0; i < n; i++) {
                    float x = buf[i];
                    if (std::fabs(x) >= 0.999f) clipped = true;  // the converter itself clipped
                    if (hpOn) x = hp.process(x);
                    gain += (gT - gain) * smooth;
                    muteGain += (mT - muteGain) * smooth;
                    x *= gain;
                    const float ax = std::fabs(x);
                    if (ax * limit > kCeiling) limit = kCeiling / ax;
                    else limit += (1.0f - limit) * release;
                    x *= limit;
                    peak = std::fmax(peak, std::fabs(x));
                    sumSq += x * x;
                    x *= muteGain;
                    buf[i] = x;
                }
                if (limit < 0.5f) clipped = true;  // >6 dB of limiting: tell the user

                // Meter shows what the PC would get if unmuted, so you can set levels while muted.
                const float rms = std::sqrt(sumSq / float(n));
                const float oldPeak = meterPeak.load(std::memory_order_relaxed);
                const float decay = std::exp(-float(n) / (0.300f * kRate));
                meterPeak.store(std::fmax(peak, oldPeak * decay), std::memory_order_relaxed);
                const float oldRms = meterRms.load(std::memory_order_relaxed);
                meterRms.store(oldRms + (rms - oldRms) * (1.0f - std::exp(-float(n) / (0.050f * kRate))),
                               std::memory_order_relaxed);
                if (clipped) clipUntilNs.store(nowNs() + 1000000000LL, std::memory_order_relaxed);

                if (es_link_write(link_, buf, n) != 0) {
                    state = -2;
                    running_ = false;
                    break;
                }
            }

            const int64_t now = nowNs();
            if (now - lastQuery > 250000000LL) {
                lastQuery = now;
                receivers = es_link_receivers(link_);
            }
        }
    }

    Config cfg_;
    es_link* link_ = nullptr;
    std::shared_ptr<oboe::AudioStream> stream_;
    Ring ring_{8192};
    sem_t ready_;
    std::thread worker_;
    std::atomic<bool> running_{false};
    std::atomic<bool> streamDied_{false};
};

Engine& engine() {
    static Engine e;
    return e;
}

std::mutex gControl;  // start/stop are called from the service's thread only, but be safe

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL Java_dev_nottzaid_earshot_audio_NativeEngine_start(
    JNIEnv* env, jobject, jstring host, jint deviceId, jint preset, jint packetUs, jint fecSource,
    jint fecRepair) {
    std::lock_guard<std::mutex> lock(gControl);
    const char* h = env->GetStringUTFChars(host, nullptr);
    Config cfg;
    cfg.host = h;
    env->ReleaseStringUTFChars(host, h);
    cfg.deviceId = deviceId;
    cfg.preset = static_cast<oboe::InputPreset>(preset);
    cfg.link.packet_us = unsigned(packetUs);
    cfg.link.fec_source = unsigned(fecSource);
    cfg.link.fec_repair = unsigned(fecRepair);
    engine().start(cfg);
    return engine().state.load();
}

JNIEXPORT void JNICALL Java_dev_nottzaid_earshot_audio_NativeEngine_stop(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(gControl);
    engine().stop();
}

JNIEXPORT void JNICALL Java_dev_nottzaid_earshot_audio_NativeEngine_setGainDb(JNIEnv*, jobject, jfloat db) {
    engine().gainTarget = std::pow(10.0f, db / 20.0f);
}

JNIEXPORT void JNICALL Java_dev_nottzaid_earshot_audio_NativeEngine_setMuted(JNIEnv*, jobject, jboolean m) {
    engine().muted = m;
}

JNIEXPORT void JNICALL Java_dev_nottzaid_earshot_audio_NativeEngine_setHighPass(JNIEnv*, jobject, jboolean on) {
    engine().highPass = on;
}

// out: [peak, rms, clipping, receivers, mmap, exclusive, burstFrames, overruns, state, sessionId]
JNIEXPORT void JNICALL Java_dev_nottzaid_earshot_audio_NativeEngine_readStatus(JNIEnv* env, jobject,
                                                                              jfloatArray out) {
    Engine& e = engine();
    timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    const int64_t now = int64_t(ts.tv_sec) * 1000000000 + ts.tv_nsec;
    const float v[] = {
        e.meterPeak.load(), e.meterRms.load(), e.clipUntilNs.load() > now ? 1.0f : 0.0f,
        float(e.receivers.load()), e.mmap ? 1.0f : 0.0f, e.exclusive ? 1.0f : 0.0f,
        float(e.burstFrames.load()), float(e.overruns.load()), float(e.state.load()),
        float(e.sessionId.load()),
    };
    env->SetFloatArrayRegion(out, 0, jsize(sizeof(v) / sizeof(v[0])), v);
}

}  // extern "C"
