// SPDX-License-Identifier: Apache-2.0
#ifndef MMAPI_AUDIO_ENGINE_H
#define MMAPI_AUDIO_ENGINE_H
#include <oboe/Oboe.h>
#include <array>
#include <atomic>
#include <memory>
#include <mutex>

namespace mmapi::eas {
class Player;
// Runtime output ownership is separate from media generation and source lifetime.
class Engine final : public std::enable_shared_from_this<Engine> {
    friend class Player;
    friend class PlayerTest;
    struct Output final : oboe::AudioStreamDataCallback, oboe::AudioStreamErrorCallback {
        const std::shared_ptr<Engine> owner;
        const int64_t epoch;
        std::atomic<bool> enabled{true};
        std::atomic<int> error{0};
        std::atomic<int> callbacksActive{0};
        std::atomic<int64_t> progress{0};
        Output(std::shared_ptr<Engine> engine, int64_t epoch) : owner(std::move(engine)), epoch(epoch) {}
        oboe::DataCallbackResult onAudioReady(oboe::AudioStream *, void *, int32_t) override;
        bool onError(oboe::AudioStream *, oboe::Result) override;
    };
    struct Slot {
        Player *reserved = nullptr; // controls only; includes construction reservations.
        std::atomic<Player *> published{nullptr}, hazard{nullptr};
    };
    std::mutex controls;
    std::array<Slot, 16> slots;
    std::shared_ptr<oboe::AudioStream> stream;
    std::shared_ptr<Output> output;
    std::atomic<bool> allowed{true}, fatal{false};
    std::atomic<int64_t> minimumRequestEpoch{0};
    int recoveries = 0;
    int64_t opens = 0, outputEpoch = 0, identity = 0;
    std::atomic<int64_t> clipped{0}, callbackNanos{0}, callbackCount{0};
    int reserve(Player *);
    void remove(Player *, int);
    void detach(Player *, int, bool bounded = true);
    bool eligible(const Player *) const;
    bool granted(const Player *) const;
    bool hasActive() const;
    void closeOutput();
    void openOutput();
    bool needsRecovery();
    void replaceOutput();
    void prepare(Player *);
    bool activate(Player *, bool fresh);
    void idle();
    void release(Player *);
    void recover();
    bool poll(int64_t &seen, int &error);
    oboe::DataCallbackResult render(Output &, void *, int32_t);
public:
    void policy(int64_t minimum, bool enabled);
    bool failed() const { return fatal.load(std::memory_order_acquire); }
    std::array<int64_t, 12> diagnostics();
};
}
#endif
