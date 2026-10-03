// SPDX-License-Identifier: Apache-2.0
#ifndef MMAPI_EAS_PLAYER_H
#define MMAPI_EAS_PLAYER_H
#include "eas.h"
#include "eas_file.h"
#include "audio_engine.h"
#include "../mmapi_pcm/pcm_decoder.h"
#include <oboe/Oboe.h>
#include <array>
#include <atomic>
#include <memory>
#include <mutex>

namespace mmapi::eas {
struct Event { int64_t type, time, generation, error; };
// Java owns guest state. Each source owns its decoder/context; Engine owns output.
// Controls serialize every producer and quiesce callbacks before touching EAS.
class Player final {
    std::mutex controls;
    EAS_DATA_HANDLE eas = nullptr;
    EAS_HANDLE media = nullptr, interactive = nullptr;
    std::unique_ptr<MemoryFile> source;
    std::unique_ptr<pcm::Decoder> sampled;
    const std::shared_ptr<Engine> engine;
    int slot = -1;
    int64_t outputIdentity = 0, seenOutputEpoch = 0;
    bool fatalReported = false;
    std::atomic<bool> prepared{false};
    std::atomic<int64_t> requestEpoch{0};
    std::atomic<bool> rendering{false};
    std::atomic<bool> requested{false};
    bool closed = false, ended = false, midiOnly = false, hostSuspended = false;
    int looping = 1, remaining = 1;
    std::atomic<int64_t> position{0}, generation{1};
    int64_t duration = -1, blockEndTime = 0;
    std::array<EAS_PCM, 512> block{};
    std::array<EAS_PCM, 512> interactiveBlock{};
    int cursor = 256;
    int interactiveCursor = 256;
    std::atomic<float> left{1}, right{1};
    std::array<uint8_t, 16384> midi{};
    std::atomic<uint32_t> midiHead{0}, midiTail{0};
    // At most one terminal render event per generation; it freezes rendering.
    std::atomic<int> pending{0}, renderError{0};
    std::atomic<int64_t> eventTime{0}, eventGeneration{0};
    std::atomic<int64_t> frames{0}, callbacks{0}, nonzero{0}, disconnects{0};
    void ensureOpen() const;
    void quiesce();
    void runOutput(bool fresh = false);
    void invalidate();
    void closeMedia();
    void openMedia(std::vector<uint8_t> bytes);
    void signal(int type, int error);
    bool renderBlock(bool onlyMidi);
    void drainMidi();
    int mix(float *audio, int32_t count);
public:
    friend class PlayerTest;
    friend class Engine;
    static constexpr size_t MEDIA_LIMIT = 16 * 1024 * 1024;
    static constexpr size_t BANK_LIMIT = 128 * 1024 * 1024;
    Player(const std::string &locator, const std::string &bank,
        std::shared_ptr<Engine> engine = std::make_shared<Engine>(), bool pcm = false);
    ~Player();
    void prefetch();
    void start(int64_t policyEpoch = 0);
    void activateMidi(int64_t policyEpoch = 0);
    void pause();
    void suspendOutput();
    void resumeOutput();
    void deallocate();
    void shutdown();
    int64_t seek(int64_t time);
    int64_t time() const { return position.load(); }
    int64_t length();
    int64_t epoch() const { return generation.load(); }
    void repeat(int count);
    void gain(float l, float r) { left.store(l); right.store(r); }
    void data(std::vector<uint8_t> bytes);
    int writeMidi(const uint8_t *bytes, int length);
    bool poll(Event &event);
    void recoverOutput();
    std::array<int64_t, 9> diagnostics();
    std::array<int64_t, 12> runtimeDiagnostics() { return engine->diagnostics(); }
    int64_t outputGroup() const { return outputIdentity; }
    bool suspended();
    std::vector<std::string> metadata();
    std::string contentType();
    std::array<int64_t, 4> decoderDiagnostics();
    bool outputFailed() const { return engine->failed(); }
};
}
#endif
