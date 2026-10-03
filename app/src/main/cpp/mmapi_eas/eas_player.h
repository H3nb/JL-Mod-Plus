// SPDX-License-Identifier: Apache-2.0
#ifndef MMAPI_EAS_PLAYER_H
#define MMAPI_EAS_PLAYER_H
#include "eas.h"
#include "eas_file.h"
#include <oboe/Oboe.h>
#include <array>
#include <atomic>
#include <memory>
#include <mutex>

namespace mmapi::eas {
struct Event { int64_t type, time, generation, error; };
// Java owns guest state. This class owns only the context and output resources.
// Controls serialize every producer and quiesce callbacks before touching EAS.
class Player final : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback,
                     public std::enable_shared_from_this<Player> {
    std::mutex controls;
    EAS_DATA_HANDLE eas = nullptr;
    EAS_HANDLE media = nullptr, interactive = nullptr;
    std::unique_ptr<MemoryFile> source;
    std::shared_ptr<oboe::AudioStream> stream;
    std::atomic<oboe::AudioStream *> currentStream{nullptr};
    std::atomic<int> callbacksActive{0};
    std::atomic<bool> rendering{false};
    bool requested = false, closed = false, ended = false, midiOnly = false, hostSuspended = false;
    int looping = 1, remaining = 1, recoveries = 0;
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
    std::atomic<int> pending{0}, renderError{0}, outputError{0};
    std::atomic<int64_t> eventTime{0}, eventGeneration{0};
    std::atomic<int64_t> frames{0}, callbacks{0}, nonzero{0}, disconnects{0};
    int64_t opens = 0;
    void ensureOpen() const;
    void quiesce();
    void closeOutput();
    void openOutput();
    void runOutput();
    void invalidate();
    void closeMedia();
    void openMedia(std::vector<uint8_t> bytes);
    void signal(int type, int error);
    bool renderBlock(bool onlyMidi);
    void drainMidi();
public:
    friend class PlayerTest;
    static constexpr size_t MEDIA_LIMIT = 16 * 1024 * 1024;
    static constexpr size_t BANK_LIMIT = 128 * 1024 * 1024;
    Player(const std::string &locator, const std::string &bank);
    ~Player() override;
    void prefetch();
    void start();
    void activateMidi();
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
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *output, void *audio, int32_t numFrames) override;
    bool onError(oboe::AudioStream *output, oboe::Result error) override;
};
}
#endif
