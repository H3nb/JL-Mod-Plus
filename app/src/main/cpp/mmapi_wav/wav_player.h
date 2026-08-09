//
// Copyright 2026 H3NB
// SPDX-License-Identifier: Apache-2.0
//

#ifndef MMAPI_WAV_PLAYER_H
#define MMAPI_WAV_PLAYER_H

#include <cstdint>
#include <oboe/Oboe.h>

#include "mmapi/BasePlayer.h"
#include "dr_wav.h"

namespace mmapi {
namespace wav {

class Player final : public BasePlayer {
public:
    static bool create(const char *path, Player **outPlayer);

    ~Player() override;

    oboe::Result prefetch() override;
    void close() override;

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *audioStream,
                                          void *audioData,
                                          int32_t numFrames) override;

protected:
    oboe::Result createAudioStream() override;

private:
    Player();

    bool seekToMicros(int64_t timeMicros);
    void updateMediaTime();
    void applyGain(int16_t *samples, uint64_t frames) const;

    drwav decoder{};
    bool decoderOpen = false;
    uint32_t channels = 0;
    uint32_t sampleRate = 0;
    uint64_t totalFrames = 0;
    uint64_t currentFrame = 0;
};

} // namespace wav
} // namespace mmapi

#endif // MMAPI_WAV_PLAYER_H
