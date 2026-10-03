// SPDX-License-Identifier: Apache-2.0
#include "eas_player.h"
#include "eas_data.h"
#include <algorithm>
#include <chrono>
#include <cstring>
#include <stdexcept>
#include <thread>

extern "C" void JL_EAS_Realtime(int);
namespace mmapi::eas {
namespace {
void checked(EAS_RESULT result, const char *operation) {
    if (result == EAS_ERROR_MALLOC_FAILED) throw std::bad_alloc();
    if (result != EAS_SUCCESS) throw std::runtime_error(std::string(operation) + ": EAS " + std::to_string(result));
}
void checked(oboe::Result result, const char *operation) {
    if (result != oboe::Result::OK) throw std::runtime_error(std::string(operation) + ": " + oboe::convertToText(result));
}
}

Player::Player(const std::string &locator, const std::string &bank) {
    try {
        checked(EAS_Init(&eas), "Initialize Sonivox");
        if (EAS_Config()->sampleRate != 44100 || EAS_Config()->mixBufferSize != 256
                || EAS_Config()->numChannels != 2) throw std::runtime_error("Unsupported Sonivox configuration");
        checked(EAS_SetHeaderSearchFlag(eas, EAS_FALSE), "Disable unbounded header search");
        if (!bank.empty()) {
            MemoryFile font(MemoryFile::readFile(bank, BANK_LIMIT));
            checked(EAS_LoadDLSCollection(eas, nullptr, &font.locator), "Load sound bank");
        }
        checked(EAS_SetVolume(eas, nullptr, 100), "Set synthesis master gain");
        if (locator == "device://midi" || locator == "device://tone")
            checked(EAS_OpenMIDIStream(eas, &interactive, nullptr), "Open interactive MIDI");
        else openMedia(MemoryFile::readFile(locator, MEDIA_LIMIT));
    } catch (...) {
        closeMedia();
        if (eas) EAS_Shutdown(eas);
        eas = nullptr;
        throw;
    }
}
Player::~Player() { shutdown(); }
void Player::ensureOpen() const {
    if (closed) throw std::runtime_error("Synthesis Player is closed");
}
void Player::quiesce() {
    rendering.store(false, std::memory_order_release);
    // Pause is bounded. Even if the device is disconnected, close later joins it.
    if (stream) {
        auto state = stream->getState();
        if (state == oboe::StreamState::Started || state == oboe::StreamState::Starting
                || state == oboe::StreamState::Pausing) {
            auto result = stream->pause(1000000000LL);
            if (result != oboe::Result::OK && result != oboe::Result::ErrorDisconnected
                    && result != oboe::Result::ErrorInvalidState) checked(result, "Pause output");
        }
    }
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(1);
    while (callbacksActive.load(std::memory_order_acquire)) {
        if (std::chrono::steady_clock::now() >= deadline)
            throw std::runtime_error("Synthesis callback did not quiesce");
        std::this_thread::yield();
    }
}
void Player::closeOutput() {
    rendering.store(false, std::memory_order_release);
    currentStream.store(nullptr, std::memory_order_release);
    if (stream) {
        // Close joins data callbacks. The stream's shared callback ownership also
        // keeps Player alive until Oboe's separately dispatched error callback ends.
        stream->close();
        stream.reset();
    }
}
void Player::openOutput() {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setSharingMode(oboe::SharingMode::Shared)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(2)->setSampleRate(44100)
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
        ->setUsage(oboe::Usage::Game)->setContentType(oboe::ContentType::Music)
        ->setDataCallback(std::static_pointer_cast<oboe::AudioStreamDataCallback>(shared_from_this()))
        ->setErrorCallback(std::static_pointer_cast<oboe::AudioStreamErrorCallback>(shared_from_this()));
    checked(builder.openStream(stream), "Open synthesis output");
    if (stream->getSampleRate() != 44100 || stream->getChannelCount() != 2) {
        closeOutput();
        throw std::runtime_error("Oboe did not provide the synthesis format");
    }
    currentStream.store(stream.get(), std::memory_order_release);
    ++opens;
}
void Player::runOutput() {
    rendering.store(true, std::memory_order_release);
    auto result = stream->start(1000000000LL);
    if (result != oboe::Result::OK) {
        rendering.store(false, std::memory_order_release);
        checked(result, "Start synthesis output");
    }
}
void Player::invalidate() {
    generation.fetch_add(1, std::memory_order_acq_rel);
    pending.store(0); renderError.store(0);
}
void Player::closeMedia() {
    if (interactive) { EAS_CloseMIDIStream(eas, interactive); interactive = nullptr; }
    if (media) { EAS_CloseFile(eas, media); media = nullptr; }
    source.reset();
}
void Player::openMedia(std::vector<uint8_t> bytes) {
    bool tone = bytes.size() >= 2 && bytes[0] == 0xfe && bytes[1] == 1;
    closeMedia();
    source = std::make_unique<MemoryFile>(std::move(bytes));
    if (tone) checked(EAS_MMAPIToneControl(eas, &source->locator, &media), "Open ToneControl sequence");
    else checked(EAS_OpenFile(eas, &source->locator, &media), "Recognize synthesis media");
    checked(EAS_Prepare(eas, media), "Prepare synthesis media");
    EAS_I32 milliseconds = -1;
    checked(EAS_ParseMetaData(eas, media, &milliseconds), "Read synthesis duration");
    duration = milliseconds >= 0 ? static_cast<int64_t>(milliseconds) * 1000 : -1;
    checked(EAS_OpenMIDIStream(eas, &interactive, media), "Open MIDI control stream");
    position.store(0); blockEndTime = 0; cursor = interactiveCursor = 256;
    midiTail.store(midiHead.load());
    ended = false; midiOnly = false; requested = false;
}
void Player::prefetch() {
    std::lock_guard<std::mutex> lock(controls);
    ensureOpen();
    if (!stream) openOutput();
}
void Player::start() {
    std::lock_guard<std::mutex> lock(controls);
    ensureOpen();
    if (!stream) throw std::runtime_error("Synthesis output is not prefetched");
    quiesce(); invalidate();
    EAS_STATE mediaState = EAS_STATE_READY;
    if (media) checked(EAS_State(eas, media, &mediaState), "Read synthesis state");
    if (media && (ended || mediaState == EAS_STATE_STOPPED)) {
        checked(EAS_Locate(eas, media, 0, EAS_FALSE), "Restart synthesis media");
        position.store(0); cursor = 256; blockEndTime = 0; remaining = looping; ended = false;
    }
    midiOnly = false; requested = true; hostSuspended = false; recoveries = 0;
    if (outputError.load() || stream->getState() == oboe::StreamState::Disconnected
            || stream->getState() == oboe::StreamState::Closed) {
        closeOutput(); openOutput(); outputError.store(0);
    }
    runOutput();
}
void Player::activateMidi() {
    std::lock_guard<std::mutex> lock(controls);
    ensureOpen();
    if (!stream) throw std::runtime_error("MIDI requires prefetched output");
    if (requested && rendering.load()) return;
    quiesce(); invalidate();
    midiOnly = media != nullptr; requested = true; hostSuspended = false; recoveries = 0;
    if (outputError.load() || stream->getState() == oboe::StreamState::Disconnected) {
        closeOutput(); openOutput(); outputError.store(0);
    }
    runOutput();
}
void Player::pause() {
    std::lock_guard<std::mutex> lock(controls);
    ensureOpen(); quiesce();
    requested = false; invalidate();
    // Do not EAS_Pause: it releases held notes. Keep voices and both staging cursors.
}
void Player::suspendOutput() {
    std::lock_guard<std::mutex> lock(controls);
    ensureOpen(); quiesce(); hostSuspended = true;
    // Preserve already-rendered EOM/error facts and interactive-vs-file mode.
}
void Player::resumeOutput() {
    std::lock_guard<std::mutex> lock(controls);
    ensureOpen(); hostSuspended = false;
    if (!stream || !requested || pending.load(std::memory_order_acquire)) return;
    if (stream->getState() == oboe::StreamState::Disconnected || stream->getState() == oboe::StreamState::Closed) {
        if (++recoveries > 3) throw std::runtime_error("Synthesis output recovery exhausted (3 attempts)");
        closeOutput(); openOutput(); outputError.store(0);
    }
    runOutput();
}
void Player::deallocate() {
    std::lock_guard<std::mutex> lock(controls);
    ensureOpen(); quiesce(); requested = false; invalidate(); closeOutput();
    outputError.store(0);
}
void Player::shutdown() {
    std::lock_guard<std::mutex> lock(controls);
    if (closed) return;
    closed = true; requested = false;
    closeOutput();
    closeMedia();
    if (eas) { EAS_Shutdown(eas); eas = nullptr; }
}
int64_t Player::seek(int64_t time) {
    std::lock_guard<std::mutex> lock(controls);
    ensureOpen();
    if (!media) throw std::runtime_error("Media time is unsupported for interactive MIDI");
    quiesce(); invalidate();
    time = std::max<int64_t>(0, time);
    if (duration >= 0) time = std::min(time, duration);
    time = std::min<int64_t>(time / 1000, INT32_MAX);
    checked(EAS_Locate(eas, media, static_cast<EAS_I32>(time), EAS_FALSE), "Seek synthesis media");
    position.store(time * 1000); blockEndTime = time * 1000;
    cursor = interactiveCursor = 256; ended = false;
    if (stream && requested && !hostSuspended) runOutput();
    return position.load();
}
int64_t Player::length() {
    std::lock_guard<std::mutex> lock(controls); ensureOpen(); return duration;
}
void Player::repeat(int count) {
    std::lock_guard<std::mutex> lock(controls); ensureOpen();
    if (count == 0 || count < -1) throw std::runtime_error("Invalid synthesis repeat count");
    looping = remaining = count;
}
void Player::data(std::vector<uint8_t> bytes) {
    std::lock_guard<std::mutex> lock(controls);
    ensureOpen(); quiesce(); invalidate(); openMedia(std::move(bytes));
}
int Player::writeMidi(const uint8_t *bytes, int length) {
    std::lock_guard<std::mutex> lock(controls); ensureOpen();
    if (!stream) throw std::runtime_error("MIDI requires prefetched output");
    uint32_t head = midiHead.load(std::memory_order_relaxed);
    uint32_t tail = midiTail.load(std::memory_order_acquire);
    // All JNI producers serialize under controls; callback is the sole consumer.
    // Reject the entire write instead of silently dropping note-off or SysEx.
    if (length < 0 || static_cast<uint32_t>(length) > midi.size() - (head - tail))
        return -1;
    for (int i = 0; i < length; ++i) midi[(head + i) % midi.size()] = bytes[i];
    midiHead.store(head + length, std::memory_order_release);
    return length;
}
void Player::signal(int type, int error) {
    rendering.store(false, std::memory_order_release);
    eventTime.store(position.load()); eventGeneration.store(generation.load());
    renderError.store(error);
    pending.store(type, std::memory_order_release);
}
void Player::drainMidi() {
    uint32_t tail = midiTail.load(std::memory_order_relaxed);
    const uint32_t head = midiHead.load(std::memory_order_acquire);
    std::array<uint8_t, 1024> bytes{};
    // Bound work per render block; even an adversarial producer cannot starve audio.
    int remainingBytes = 4096;
    while (tail != head && remainingBytes > 0) {
        int count = std::min<uint32_t>(head - tail, std::min<int>(bytes.size(), remainingBytes));
        for (int i = 0; i < count; ++i) bytes[i] = midi[(tail + i) % midi.size()];
        EAS_RESULT result = EAS_WriteMIDIStream(eas, interactive, bytes.data(), count);
        if (result != EAS_SUCCESS) { signal(3, result); break; }
        tail += count; remainingBytes -= count;
    }
    midiTail.store(tail, std::memory_order_release);
}
bool Player::renderBlock(bool onlyMidi) {
    drainMidi();
    if (!rendering.load(std::memory_order_acquire)) return false;
    if (media && !onlyMidi) {
        EAS_STATE state;
        EAS_RESULT result = EAS_State(eas, media, &state);
        if (result != EAS_SUCCESS || state == EAS_STATE_ERROR) {
            signal(3, result == EAS_SUCCESS ? EAS_FAILURE : result); return false;
        }
        if (state == EAS_STATE_STOPPED) {
            if (duration >= 0) position.store(duration);
            signal(2, 0); return false;
        }
    }
    EAS_I32 generated = 0;
    // Private stream detachment keeps parser/time frozen for PREFETCHED MIDI.
    // Only this callback owns EAS here. Voices still synthesize incoming MIDI;
    // guest stop without a new MIDI request freezes the entire context/output.
    const auto parser = media && onlyMidi ? media->pParserModule : nullptr;
    if (media && onlyMidi) media->pParserModule = nullptr;
    EAS_RESULT result = EAS_Render(eas, onlyMidi ? interactiveBlock.data() : block.data(), 256, &generated);
    if (media && onlyMidi) media->pParserModule = parser;
    if (result != EAS_SUCCESS || generated != 256) {
        signal(3, result == EAS_SUCCESS ? EAS_BUFFER_SIZE_MISMATCH : result); return false;
    }
    if (onlyMidi) interactiveCursor = 0;
    else {
        cursor = 0;
        if (media) {
            EAS_I32 milliseconds;
            if (EAS_GetLocation(eas, media, &milliseconds) == EAS_SUCCESS)
                blockEndTime = static_cast<int64_t>(milliseconds) * 1000;
        }
    }
    return true;
}
bool Player::poll(Event &event) {
    std::lock_guard<std::mutex> lock(controls); ensureOpen();
    int type = pending.load(std::memory_order_acquire);
    if (type) {
        quiesce();
        event = {type, eventTime.load(), eventGeneration.load(), renderError.load()};
        pending.store(0);
        if (event.generation != generation.load()) return false;
        if (type == 2 && media) {
            ended = true;
            if (requested && (looping == -1 || --remaining > 0)) {
                checked(EAS_Locate(eas, media, 0, EAS_FALSE), "Loop synthesis media");
                position.store(0); blockEndTime = 0; cursor = 256; ended = false;
                if (!hostSuspended) runOutput();
                event.type = 1;
            } else requested = false;
        } else if (type == 3) requested = false;
        return true;
    }
    int error = outputError.exchange(0);
    if (error) {
        event = {error == static_cast<int>(oboe::Result::ErrorDisconnected) ? 4 : 3,
                 position.load(), generation.load(), error};
        return true;
    }
    return false;
}
void Player::recoverOutput() {
    std::lock_guard<std::mutex> lock(controls); ensureOpen();
    quiesce();
    if (++recoveries > 3) throw std::runtime_error("Synthesis output recovery exhausted (3 attempts)");
    closeOutput(); openOutput();
    if (requested && !hostSuspended) runOutput();
}
std::array<int64_t, 9> Player::diagnostics() {
    std::lock_guard<std::mutex> lock(controls); ensureOpen();
    int64_t xruns = -1;
    if (stream) { auto result = stream->getXRunCount(); if (result) xruns = result.value(); }
    return {frames.load(), callbacks.load(), nonzero.load(), opens, disconnects.load(), xruns,
        stream ? stream->getSampleRate() : 0, stream ? stream->getDeviceId() : 0, generation.load()};
}
oboe::DataCallbackResult Player::onAudioReady(oboe::AudioStream *output, void *audio, int32_t count) {
    callbacksActive.fetch_add(1, std::memory_order_acq_rel);
    auto out = static_cast<float *>(audio);
    std::fill(out, out + static_cast<size_t>(count) * 2, 0.0f);
    if (output == currentStream.load(std::memory_order_acquire) && rendering.load(std::memory_order_acquire)) {
        JL_EAS_Realtime(1);
        int offset = 0;
        int &read = midiOnly ? interactiveCursor : cursor;
        const auto &samples = midiOnly ? interactiveBlock : block;
        float l = left.load(), r = right.load();
        int64_t audible = 0;
        while (offset < count && rendering.load(std::memory_order_acquire)) {
            if (read == 256 && !renderBlock(midiOnly)) break;
            int amount = std::min(count - offset, 256 - read);
            for (int i = 0; i < amount; ++i) {
                out[2 * (offset + i)] = samples[2 * (read + i)] * (l / 32768.0f);
                out[2 * (offset + i) + 1] = samples[2 * (read + i) + 1] * (r / 32768.0f);
                audible += out[2 * (offset + i)] != 0;
                audible += out[2 * (offset + i) + 1] != 0;
            }
            read += amount; offset += amount;
            if (media && !midiOnly) {
                int64_t now = std::max<int64_t>(0, blockEndTime - (256 - cursor) * 1000000LL / 44100);
                if (duration >= 0) now = std::min(now, duration);
                position.store(now);
            }
        }
        nonzero.fetch_add(audible); frames.fetch_add(offset); callbacks.fetch_add(1);
        JL_EAS_Realtime(0);
    }
    callbacksActive.fetch_sub(1, std::memory_order_release);
    return oboe::DataCallbackResult::Continue;
}
bool Player::onError(oboe::AudioStream *output, oboe::Result error) {
    if (output == currentStream.load(std::memory_order_acquire)) {
        rendering.store(false, std::memory_order_release);
        outputError.store(static_cast<int>(error), std::memory_order_release);
        if (error == oboe::Result::ErrorDisconnected) disconnects.fetch_add(1);
    }
    // Java management polling closes/reopens. Oboe must not close on this callback.
    return true;
}
}
