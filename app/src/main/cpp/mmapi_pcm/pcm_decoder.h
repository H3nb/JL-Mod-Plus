// SPDX-License-Identifier: Apache-2.0
#ifndef MMAPI_PCM_DECODER_H
#define MMAPI_PCM_DECODER_H
#include <array>
#include <atomic>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace mmapi::pcm {
// Management calls are serialized by the owning Player, with its source detached
// from the mixer. read() has one realtime consumer and never waits or allocates.
class Decoder final {
    struct State;
    std::unique_ptr<State> state;
public:
    static constexpr int RATE = 44100, CHANNELS = 2;
    static constexpr size_t MEDIA_LIMIT = 64 * 1024 * 1024;
    explicit Decoder(const std::string &path);
    ~Decoder();
    Decoder(const Decoder &) = delete;
    Decoder &operator=(const Decoder &) = delete;
    void prefetch();
    void resume();
    void pause();
    void deallocate();
    int64_t seek(int64_t microseconds);
    // Adds to interleaved stereo output, applying per-source gains. Returns the
    // consumed frame count; missing frames remain untouched (silence on this bus).
    int read(float *bus, int frames, float left, float right);
    bool drained() const;
    int error() const;
    int64_t time() const;
    int64_t duration() const;
    int64_t audibleSamples() const;
    std::string contentType() const;
    std::vector<std::string> metadata() const;
    std::array<int64_t, 4> diagnostics() const; // consumed, underflow, queued, workers
};
}
#endif
