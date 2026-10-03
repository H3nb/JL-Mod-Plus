// SPDX-License-Identifier: Apache-2.0
#include "audio_engine.h"
#include "eas_player.h"
#include <algorithm>
#include <chrono>
#include <stdexcept>
#include <thread>
#include <time.h>
extern "C" void JL_EAS_Realtime(int);
namespace mmapi::eas {
#ifdef JL_EAS_OUTPUT_TEST
oboe::Result testOpenOutput(const oboe::AudioStreamBuilder &, std::shared_ptr<oboe::AudioStream> &);
#endif
namespace {
static_assert(std::atomic<int64_t>::is_always_lock_free, "Runtime audio requires lock-free clocks");
std::atomic<int64_t> nextIdentity{1};
void checked(oboe::Result result, const char *operation) {
    if (result != oboe::Result::OK)
        throw std::runtime_error(std::string(operation) + ": " + oboe::convertToText(result));
}
}
int Engine::reserve(Player *player) {
    std::lock_guard<std::mutex> lock(controls);
    bool empty = true;
    for (const auto &slot : slots) if (slot.reserved) empty = false;
    if (empty) { identity = nextIdentity.fetch_add(1); fatal.store(false); recoveries = 0; }
    for (size_t i = 0; i < slots.size(); ++i) if (!slots[i].reserved) {
        slots[i].reserved = player;
        player->outputIdentity = identity;
        return static_cast<int>(i);
    }
    throw std::runtime_error("Runtime audio source limit reached (16 Players)");
}
void Engine::detach(Player *player, int index, bool bounded) {
    auto &slot = slots[index];
    slot.published.store(nullptr, std::memory_order_seq_cst);
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(1);
    while (slot.hazard.load(std::memory_order_seq_cst) == player) {
        if (bounded && std::chrono::steady_clock::now() >= deadline)
            throw std::runtime_error("Audio source callback did not quiesce");
        std::this_thread::yield();
    }
}
void Engine::remove(Player *player, int index) {
    // Teardown never frees a context until the callback has relinquished it.
    detach(player, index, false);
    std::lock_guard<std::mutex> lock(controls);
    slots[index].reserved = nullptr;
    bool prepared = false;
    for (const auto &slot : slots) if (slot.reserved && slot.reserved->prepared.load()) prepared = true;
    if (!prepared) closeOutput();
}
bool Engine::eligible(const Player *player) const {
    return player->rendering.load(std::memory_order_acquire)
        && player->requestEpoch.load(std::memory_order_acquire) >= minimumRequestEpoch.load(std::memory_order_acquire);
}
bool Engine::granted(const Player *player) const {
    return allowed.load(std::memory_order_acquire)
        && player->requestEpoch.load(std::memory_order_acquire) >= minimumRequestEpoch.load(std::memory_order_acquire);
}
bool Engine::hasActive() const {
    for (const auto &slot : slots) if (slot.reserved && eligible(slot.reserved)) return true;
    return false;
}
void Engine::policy(int64_t minimum, bool enabled) {
    allowed.store(false, std::memory_order_release);
    minimumRequestEpoch.store(minimum, std::memory_order_release);
    allowed.store(enabled, std::memory_order_release);
}
void Engine::closeOutput() {
    if (output) output->enabled.store(false, std::memory_order_seq_cst);
    if (stream) { stream->close(); stream.reset(); }
    // Do not rely on device-specific close behavior to serialize old/new
    // consumers. A callback entering after this acknowledgment sees disabled.
    while (output && output->callbacksActive.load(std::memory_order_seq_cst)) std::this_thread::yield();
    output.reset();
}
void Engine::openOutput() {
    auto next = std::make_shared<Output>(shared_from_this(), ++outputEpoch);
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)->setSharingMode(oboe::SharingMode::Shared)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(2)->setSampleRate(44100)
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
        ->setUsage(oboe::Usage::Game)->setContentType(oboe::ContentType::Music)
        ->setDataCallback(std::static_pointer_cast<oboe::AudioStreamDataCallback>(next))
        ->setErrorCallback(std::static_pointer_cast<oboe::AudioStreamErrorCallback>(next));
#ifdef JL_EAS_OUTPUT_TEST
    checked(testOpenOutput(builder, stream), "Open runtime output");
#else
    checked(builder.openStream(stream), "Open runtime output");
#endif
    output = std::move(next);
    if (stream->getSampleRate() != 44100 || stream->getChannelCount() != 2) {
        closeOutput(); throw std::runtime_error("Oboe did not provide the runtime bus format");
    }
    ++opens;
}
bool Engine::needsRecovery() {
    if (!stream) return false;
    int error = output->error.load(std::memory_order_acquire);
    if (error && error != static_cast<int>(oboe::Result::ErrorDisconnected))
        checked(static_cast<oboe::Result>(error), "Runtime output failure");
    return error || !output->enabled.load() || stream->getState() == oboe::StreamState::Disconnected
        || stream->getState() == oboe::StreamState::Closed;
}
void Engine::replaceOutput() {
    if (output && output->progress.load(std::memory_order_acquire) >= 4410) recoveries = 0;
    if (++recoveries > 3) throw std::runtime_error("Runtime output recovery exhausted (3 unhealthy attempts)");
    closeOutput(); openOutput();
}
void Engine::prepare(Player *player) {
    std::lock_guard<std::mutex> lock(controls);
    try {
        if (fatal.load()) throw std::runtime_error("Runtime output has failed");
        if (!stream) openOutput();
        player->prepared.store(true);
    } catch (...) { fatal.store(true); throw; }
}
bool Engine::activate(Player *player, bool fresh) {
    std::lock_guard<std::mutex> lock(controls);
    try {
        if (fatal.load()) throw std::runtime_error("Runtime output has failed");
        if (!player->prepared.load() || !stream) throw std::runtime_error("Audio source is not prefetched");
        bool peerIntent = false;
        for (const auto &slot : slots) {
            auto peer = slot.reserved;
            if (peer && peer != player && peer->requested.load() && peer->prepared.load()
                    && peer->requestEpoch.load() >= minimumRequestEpoch.load()) peerIntent = true;
        }
        if (fresh && !peerIntent) recoveries = 0;
        if (!granted(player)) return false;
        if (needsRecovery()) replaceOutput();
        player->rendering.store(true, std::memory_order_release);
        slots[player->slot].published.store(player, std::memory_order_seq_cst);
        auto state = stream->getState();
        if (state != oboe::StreamState::Started && state != oboe::StreamState::Starting)
            checked(stream->start(1000000000LL), "Start runtime output");
        return true;
    } catch (...) { fatal.store(true); player->rendering.store(false); throw; }
}
void Engine::idle() {
    std::lock_guard<std::mutex> lock(controls);
    if (stream && (!allowed.load() || !hasActive())) {
        auto state = stream->getState();
        if (state == oboe::StreamState::Started || state == oboe::StreamState::Starting) {
            auto result = stream->pause(1000000000LL);
            if (result != oboe::Result::OK && result != oboe::Result::ErrorDisconnected
                    && result != oboe::Result::ErrorInvalidState) {
                fatal.store(true);
                checked(result, "Pause runtime output");
            }
        }
    }
}
void Engine::release(Player *player) {
    std::lock_guard<std::mutex> lock(controls);
    player->prepared.store(false);
    for (const auto &slot : slots) if (slot.reserved && slot.reserved->prepared.load()) return;
    closeOutput();
}
void Engine::recover() {
    std::lock_guard<std::mutex> lock(controls);
    try {
        if (fatal.load()) throw std::runtime_error("Runtime output has failed");
        if (!allowed.load() || !hasActive() || !needsRecovery()) return;
        replaceOutput();
        checked(stream->start(1000000000LL), "Recover runtime output");
    } catch (...) { fatal.store(true); throw; }
}
bool Engine::poll(int64_t &seen, int &error) {
    std::lock_guard<std::mutex> lock(controls);
    if (fatal.load()) { error = static_cast<int>(oboe::Result::ErrorInternal); return true; }
    if (!output || seen == output->epoch) return false;
    error = output->error.load(std::memory_order_acquire);
    if (!error) return false;
    seen = output->epoch;
    if (error != static_cast<int>(oboe::Result::ErrorDisconnected)) fatal.store(true);
    return true;
}
oboe::DataCallbackResult Engine::Output::onAudioReady(oboe::AudioStream *stream, void *audio, int32_t count) {
    return owner->render(*this, stream, audio, count);
}
bool Engine::Output::onError(oboe::AudioStream *, oboe::Result failure) {
    int empty = 0;
    error.compare_exchange_strong(empty, static_cast<int>(failure), std::memory_order_acq_rel);
    return true; // Management closes/reopens; old origins can only publish their own facts.
}
oboe::DataCallbackResult Engine::render(Output &origin, oboe::AudioStream *sink, void *audio, int32_t count) {
    if (count <= 0) return oboe::DataCallbackResult::Continue;
    origin.callbacksActive.fetch_add(1, std::memory_order_seq_cst);
    const auto began = std::chrono::steady_clock::now();
    auto out = static_cast<float *>(audio);
    std::fill(out, out + static_cast<size_t>(count) * 2, 0.0f);
    if (allowed.load(std::memory_order_acquire) && !fatal.load() && origin.enabled.load(std::memory_order_seq_cst)
            && !origin.error.load()) {
        JL_EAS_Realtime(1);
        int progress = 0;
        const int64_t first = sink->getFramesWritten();
        for (auto &slot : slots) {
            auto player = slot.published.load(std::memory_order_seq_cst);
            slot.hazard.store(player, std::memory_order_seq_cst);
            if (player && player == slot.published.load(std::memory_order_seq_cst) && eligible(player)) {
                const int64_t begin = player->position.load();
                progress = std::max(progress, player->mix(out, count));
                if (player->presentationDrain)
                    player->recordPresentation(first, count, begin, player->position.load(), origin.epoch);
            }
            slot.hazard.store(nullptr, std::memory_order_seq_cst);
        }
        int64_t clips = 0;
        for (size_t i = 0; i < static_cast<size_t>(count) * 2; ++i) {
            if (out[i] > 1 || out[i] < -1) { ++clips; out[i] = std::clamp(out[i], -1.0f, 1.0f); }
        }
        clipped.fetch_add(clips); origin.progress.fetch_add(progress, std::memory_order_release);
        JL_EAS_Realtime(0);
    }
    callbackCount.fetch_add(1);
    auto nanos = std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - began).count();
    auto maximum = callbackNanos.load();
    while (nanos > maximum && !callbackNanos.compare_exchange_weak(maximum, nanos)) {}
    origin.callbacksActive.fetch_sub(1, std::memory_order_seq_cst);
    return oboe::DataCallbackResult::Continue;
}
std::array<int64_t, 8> Engine::presentation(Player *player, int64_t at) {
    // Player controls precede Engine controls, as in diagnostics. Timestamp calls
    // are serialized with close/reopen and never made by the audio callback.
    std::lock_guard<std::mutex> lock(controls);
    timespec time{}; clock_gettime(CLOCK_MONOTONIC, &time);
    const int64_t now = time.tv_sec * 1000000000LL + time.tv_nsec;
    const int64_t query = at > 0 ? std::min(at, now) : now;
    int64_t bus = -1, available = 0, uncertainty = 0;
    if (stream && output) {
        auto stamp = stream->getTimestamp(CLOCK_MONOTONIC);
        if (stamp) {
            bus = stamp.value().position + (query - stamp.value().timestamp) * 44100 / 1000000000;
            bus = std::min(bus, stream->getFramesWritten());
            available = 1;
            uncertainty = stream->getFramesPerBurst() * 1000000LL / 44100;
        } else {
            // Bounded queue estimate from the current sink, not a fixed delay.
            const int64_t queue = stream->getBufferSizeInFrames();
            const int64_t capacity = stream->getBufferCapacityInFrames();
            if (capacity <= 0 || stream->getFramesPerBurst() <= 0)
                throw std::runtime_error("Audio output has no bounded presentation estimate");
            bus = std::max<int64_t>(0, stream->getFramesWritten() - (queue > 0 ? queue : capacity)
                - (now - query) * 44100 / 1000000000);
            uncertainty = (capacity + stream->getFramesPerBurst()) * 1000000LL / 44100;
        }
    }
    int64_t media = player->presentationOrigin.load();
    const auto generation = player->generation.load();
    const auto head = player->presentationHead.load(std::memory_order_acquire);
    bool found = false, mapped = false;
    for (uint32_t n = 0; n < std::min<uint32_t>(head, 128); ++n) {
        auto &segment = player->presentationSegments[(head - 1 - n) % 128];
        const auto revision = segment.revision.load(std::memory_order_acquire);
        if (revision & 1) continue;
        const auto first = segment.first.load(), last = segment.last.load();
        const auto begin = segment.begin.load(), end = segment.end.load();
        const auto gen = segment.generation.load(), epoch = segment.output.load();
        if (revision != segment.revision.load(std::memory_order_acquire) || gen != generation || epoch != outputEpoch) continue;
        media = begin; found = true;
        if (bus < first) continue;
        mapped = true;
        media = std::min(end, begin + std::max<int64_t>(0, bus - first) * 1000000 / 44100);
        if (bus >= last) media = end;
        break;
    }
    if (!found) media = player->presentationOrigin.load();
    return {media, query, generation, outputEpoch, available, uncertainty, bus, mapped ? 1 : 0};
}
std::array<int64_t, 12> Engine::diagnostics() {
    std::lock_guard<std::mutex> lock(controls);
    int64_t sources = 0, active = 0, xruns = -1, latency = -1;
    for (const auto &slot : slots) if (slot.reserved) { ++sources; active += eligible(slot.reserved); }
    if (stream) {
        auto result = stream->getXRunCount(); if (result) xruns = result.value();
        auto measured = stream->calculateLatencyMillis();
        if (measured) latency = static_cast<int64_t>(measured.value() * 1000);
    }
    return {identity, opens, stream ? 1 : 0, sources, active, clipped.load(), callbackNanos.load(),
        callbackCount.load(), xruns, outputEpoch, latency, stream ? stream->getBufferSizeInFrames() : 0};
}
}
