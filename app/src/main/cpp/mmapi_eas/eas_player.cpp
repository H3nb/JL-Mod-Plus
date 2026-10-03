// SPDX-License-Identifier: Apache-2.0
#include "eas_player.h"
#include "eas_data.h"
#include <algorithm>
#include <stdexcept>

namespace mmapi::eas {
namespace {
void checked(EAS_RESULT result, const char *operation) {
    if (result == EAS_ERROR_MALLOC_FAILED) throw std::bad_alloc();
    if (result != EAS_SUCCESS) throw std::runtime_error(std::string(operation) + ": EAS " + std::to_string(result));
}

}

Player::Player(const std::string &locator, const std::string &bank, std::shared_ptr<Engine> shared, bool pcm, bool videoAudio)
    : engine(std::move(shared)) {
    slot = engine->reserve(this);
    try {
        if (pcm) { sampled = std::make_unique<pcm::Decoder>(locator, videoAudio); duration = sampled->duration(); return; }
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
        engine->remove(this, slot);
        throw;
    }
}
Player::~Player() { shutdown(); }
void Player::ensureOpen() const {
    if (closed) throw std::runtime_error("Synthesis Player is closed");
}
void Player::quiesce() {
    rendering.store(false, std::memory_order_release);
    engine->detach(this, slot);
    engine->idle();
    if (sampled) sampled->pause();
}
void Player::runOutput(bool fresh) {
    if (sampled) sampled->resume();
    try {
        hostSuspended = !engine->activate(this, fresh);
        if (hostSuspended && sampled) sampled->pause();
    }
    catch (...) { if (sampled) sampled->pause(); throw; }
}
void Player::invalidate() {
    presentationOrigin.store(position.load());
    generation.fetch_add(1, std::memory_order_acq_rel);
    pending.store(0); renderError.store(0);
}
void Player::closeMedia() {
    if (interactive) { EAS_CloseMIDIStream(eas, interactive); interactive = nullptr; }
    if (media) { EAS_CloseFile(eas, media); media = nullptr; }
    source.reset();
}
void Player::openMedia(std::vector<uint8_t> bytes) {
    bytes = MemoryFile::synthesisMedia(std::move(bytes));
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
    if (sampled) sampled->prefetch();
    engine->prepare(this);
}
void Player::start(int64_t policyEpoch) {
    std::lock_guard<std::mutex> lock(controls);
    ensureOpen();
    if (!prepared.load()) throw std::runtime_error("Synthesis output is not prefetched");
    quiesce(); invalidate();
    if (sampled && ended) {
        sampled->seek(0); sampled->prefetch(); position.store(0); presentationOrigin.store(0); remaining = looping; ended = false;
    }
    EAS_STATE mediaState = EAS_STATE_READY;
    if (media) checked(EAS_State(eas, media, &mediaState), "Read synthesis state");
    if (media && (ended || mediaState == EAS_STATE_STOPPED)) {
        checked(EAS_Locate(eas, media, 0, EAS_FALSE), "Restart synthesis media");
        position.store(0); cursor = 256; blockEndTime = 0; remaining = looping; ended = false;
    }
    midiOnly = false; requested = true; hostSuspended = false;
    if (remaining == 0) remaining = looping;
    requestEpoch.store(policyEpoch); runOutput(true);
}
void Player::activateMidi(int64_t policyEpoch) {
    std::lock_guard<std::mutex> lock(controls);
    ensureOpen();
    if (sampled) throw std::runtime_error("Sampled Player has no MIDI control");
    if (!prepared.load()) throw std::runtime_error("MIDI requires prefetched output");
    quiesce(); invalidate();
    midiOnly = media != nullptr; requested = true; hostSuspended = false;
    requestEpoch.store(policyEpoch); runOutput(true);
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
    if (!prepared.load() || !requested || pending.load(std::memory_order_acquire)) return;
    runOutput();
}
void Player::deallocate() {
    std::lock_guard<std::mutex> lock(controls);
    ensureOpen(); quiesce(); requested = false; invalidate();
    if (sampled) sampled->deallocate();
    engine->release(this);
}
void Player::shutdown() {
    std::lock_guard<std::mutex> lock(controls);
    if (closed) return;
    closed = true; requested = false;
    rendering.store(false); prepared.store(false);
    engine->remove(this, slot);
    sampled.reset();
    closeMedia();
    if (eas) { EAS_Shutdown(eas); eas = nullptr; }
}
int64_t Player::seek(int64_t time) {
    std::lock_guard<std::mutex> lock(controls);
    ensureOpen();
    if (!media && !sampled) throw std::runtime_error("Media time is unsupported for interactive MIDI");
    quiesce(); invalidate();
    time = std::max<int64_t>(0, time);
    if (duration >= 0) time = std::min(time, duration);
    if (sampled) {
        position.store(sampled->seek(time)); presentationOrigin.store(position.load()); ended = false;
        if (prepared.load()) sampled->prefetch();
        if (prepared.load() && requested && !hostSuspended) runOutput();
        return position.load();
    }
    time = std::min<int64_t>(time / 1000, INT32_MAX);
    checked(EAS_Locate(eas, media, static_cast<EAS_I32>(time), EAS_FALSE), "Seek synthesis media");
    position.store(time * 1000); blockEndTime = time * 1000;
    cursor = interactiveCursor = 256; ended = false;
    if (prepared.load() && requested && !hostSuspended) runOutput();
    return position.load();
}
int64_t Player::length() {
    std::lock_guard<std::mutex> lock(controls); ensureOpen(); if (sampled) duration = sampled->duration(); return duration;
}
void Player::repeat(int count) {
    std::lock_guard<std::mutex> lock(controls); ensureOpen();
    if (count == 0 || count < -1) throw std::runtime_error("Invalid synthesis repeat count");
    looping = remaining = count;
}
void Player::data(std::vector<uint8_t> bytes) {
    std::lock_guard<std::mutex> lock(controls);
    ensureOpen();
    if (sampled) throw std::runtime_error("Sampled Player has no ToneControl source");
    quiesce(); invalidate(); openMedia(std::move(bytes));
}
int Player::writeMidi(const uint8_t *bytes, int length) {
    std::lock_guard<std::mutex> lock(controls); ensureOpen();
    if (sampled) throw std::runtime_error("Sampled Player has no MIDI control");
    if (!prepared.load()) throw std::runtime_error("MIDI requires prefetched output");
    uint32_t head = midiHead.load(std::memory_order_relaxed);
    uint32_t tail = midiTail.load(std::memory_order_acquire);
    // All JNI producers serialize under controls; callback is the sole consumer.
    // Return rejection for the entire write; never partially enqueue an event.
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
    // The management poll also retires policy-blocked renderers/workers if the
    // main-thread participant callback has not arrived yet. Preserve guest intent.
    if (requested && !hostSuspended && !engine->granted(this)) {
        quiesce(); hostSuspended = true;
    }
    if (presentationDrain && rendering.load() && sampled && sampled->drained()) {
        const auto stamp = engine->presentation(this);
        if (stamp[7] && stamp[0] >= position.load()) signal(2, 0);
    }
    int type = pending.load(std::memory_order_acquire);
    if (type) {
        quiesce();
        event = {type, eventTime.load(), eventGeneration.load(), renderError.load()};
        pending.store(0);
        if (event.generation != generation.load()) return false;
        if (type == 2 && (media || sampled)) {
            ended = true;
            if (requested && (looping == -1 || --remaining > 0)) {
                if (sampled) { sampled->seek(0); sampled->prefetch(); }
                else checked(EAS_Locate(eas, media, 0, EAS_FALSE), "Loop synthesis media");
                position.store(0); blockEndTime = 0; cursor = 256; ended = false;
                if (!hostSuspended) runOutput();
                event.type = 1;
            } else requested = false;
        } else if (type == 3) requested = false;
        return true;
    }
    if (fatalReported) return false;
    int error = 0;
    if (engine->poll(seenOutputEpoch, error)) {
        const bool disconnected = error == static_cast<int>(oboe::Result::ErrorDisconnected) && !engine->failed();
        if (disconnected) disconnects.fetch_add(1);
        else fatalReported = true;
        event = {disconnected ? 4 : 5, position.load(), generation.load(), error};
        return true;
    }
    return false;
}
void Player::recoverOutput() {
    std::lock_guard<std::mutex> lock(controls); ensureOpen();
    if (!requested || hostSuspended) return;
    try { engine->recover(); } catch (...) { rendering.store(false); throw; }
}
std::array<int64_t, 9> Player::diagnostics() {
    std::lock_guard<std::mutex> lock(controls); ensureOpen();
    std::lock_guard<std::mutex> outputLock(engine->controls);
    auto stream = engine->stream;
    int64_t xruns = -1;
    if (stream) { auto result = stream->getXRunCount(); if (result) xruns = result.value(); }
    return {frames.load(), callbacks.load(), nonzero.load(), engine->opens, disconnects.load(), xruns,
        stream ? stream->getSampleRate() : 0, stream ? stream->getDeviceId() : 0, generation.load()};
}
bool Player::suspended() {
    std::lock_guard<std::mutex> lock(controls); ensureOpen(); return hostSuspended;
}
std::vector<std::string> Player::metadata() {
    std::lock_guard<std::mutex> lock(controls); ensureOpen();
    return sampled ? sampled->metadata() : std::vector<std::string>{};
}
std::string Player::contentType() {
    std::lock_guard<std::mutex> lock(controls); ensureOpen();
    return sampled ? sampled->contentType() : std::string{};
}
std::array<int64_t, 4> Player::decoderDiagnostics() {
    std::lock_guard<std::mutex> lock(controls); ensureOpen();
    return sampled ? sampled->diagnostics() : std::array<int64_t, 4>{};
}
int Player::mix(float *out, int32_t count) {
    if (sampled && rendering.load(std::memory_order_acquire)) {
        int copied = sampled->read(out, count, left.load(), right.load());
        position.store(sampled->time());
        frames.fetch_add(copied); callbacks.fetch_add(1); nonzero.store(sampled->audibleSamples());
        int error = sampled->error();
        if (error) signal(3, error);
        else if (sampled->drained() && !presentationDrain) signal(2, 0);
        return copied;
    }
    if (rendering.load(std::memory_order_acquire)) {
        int offset = 0;
        int &read = midiOnly ? interactiveCursor : cursor;
        const auto &samples = midiOnly ? interactiveBlock : block;
        float l = left.load(), r = right.load();
        int64_t audible = 0;
        while (offset < count && rendering.load(std::memory_order_acquire)
) {
            if (read == 256 && !renderBlock(midiOnly)) break;
            int amount = std::min(count - offset, 256 - read);
            for (int i = 0; i < amount; ++i) {
                const float sourceLeft = samples[2 * (read + i)] * (l / 32768.0f);
                const float sourceRight = samples[2 * (read + i) + 1] * (r / 32768.0f);
                out[2 * (offset + i)] += sourceLeft;
                out[2 * (offset + i) + 1] += sourceRight;
                audible += sourceLeft != 0;
                audible += sourceRight != 0;
            }
            read += amount; offset += amount;
            if (media && !midiOnly) {
                int64_t now = std::max<int64_t>(0, blockEndTime - (256 - cursor) * 1000000LL / 44100);
                if (duration >= 0) now = std::min(now, duration);
                position.store(now);
            }
        }
        nonzero.fetch_add(audible); frames.fetch_add(offset); callbacks.fetch_add(1);
        return offset;
    }
    return 0;
}
void Player::recordPresentation(int64_t first, int count, int64_t begin, int64_t end, int64_t output) {
    const auto head = presentationHead.load(std::memory_order_relaxed);
    auto &segment = presentationSegments[head % 128];
    segment.revision.fetch_add(1, std::memory_order_acq_rel);
    segment.first.store(first); segment.last.store(first + count);
    segment.begin.store(begin); segment.end.store(end);
    segment.generation.store(generation.load()); segment.output.store(output);
    segment.revision.fetch_add(1, std::memory_order_release);
    presentationHead.store(head + 1, std::memory_order_release);
}
void Player::timeline(int64_t origin) {
    std::lock_guard<std::mutex> lock(controls); ensureOpen();
    if (!sampled || prepared.load()) throw std::runtime_error("Configure audio timeline before prefetch");
    sampled->timeline(origin); presentationDrain = true;
}
std::array<int64_t, 8> Player::presentation(int64_t at) {
    std::lock_guard<std::mutex> lock(controls); ensureOpen(); return engine->presentation(this, at);
}
}
