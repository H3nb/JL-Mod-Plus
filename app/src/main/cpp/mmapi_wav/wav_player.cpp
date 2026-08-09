//
// Copyright 2026 H3NB
// SPDX-License-Identifier: Apache-2.0
//

#define DR_WAV_IMPLEMENTATION
#include "wav_player.h"

#include <algorithm>
#include <cstring>
#include <limits>
#include <vector>

#include "gsm610_decoder.h"
#include "util/log.h"

#define LOG_TAG "MMAPI-WAV"

namespace mmapi {
namespace wav {

Player::Player() : BasePlayer(-1) {
    looping = 1;
    loopCount = 1;
    playTime = 0;
}

Player::~Player() {
    close();
}

bool Player::create(const char *path, Player **outPlayer) {
    if (path == nullptr || outPlayer == nullptr) {
        return false;
    }
    *outPlayer = nullptr;

    Player *player = new Player();
    const Gsm610DecodeResult gsmResult = decodeGsm610WaveFile(path, player->decodedWave);
    bool opened = false;
    if (gsmResult == Gsm610DecodeResult::Decoded) {
        opened = drwav_init_memory(&player->decoder,
                                   player->decodedWave.data(),
                                   player->decodedWave.size(),
                                   nullptr);
    } else if (gsmResult == Gsm610DecodeResult::NotGsm) {
        opened = drwav_init_file(&player->decoder, path, nullptr);
    }
    if (!opened) {
        delete player;
        return false;
    }

    player->decoderOpen = true;
    player->channels = player->decoder.channels;
    player->sampleRate = player->decoder.sampleRate;
    player->totalFrames = player->decoder.totalPCMFrameCount;

    // Java ME game audio is expected to be mono or stereo. Reject unusual
    // layouts rather than handing an unsupported channel count to Oboe.
    if ((player->channels != 1 && player->channels != 2)
            || player->sampleRate == 0
            || player->totalFrames == 0) {
        delete player;
        return false;
    }

    const uint64_t seconds = player->totalFrames / player->sampleRate;
    const uint64_t remainingFrames = player->totalFrames % player->sampleRate;
    if (seconds > static_cast<uint64_t>(std::numeric_limits<int64_t>::max() / 1000000LL)) {
        player->duration = std::numeric_limits<int64_t>::max();
    } else {
        player->duration = static_cast<int64_t>(seconds * 1000000ULL
                + (remainingFrames * 1000000ULL) / player->sampleRate);
    }

    *outPlayer = player;
    return true;
}

oboe::Result Player::prefetch() {
    if (!decoderOpen) {
        return oboe::Result::ErrorInvalidState;
    }
    return BasePlayer::prefetch();
}

void Player::close() {
    BasePlayer::close();
    if (decoderOpen) {
        drwav_uninit(&decoder);
        decoderOpen = false;
    }
    std::vector<uint8_t>().swap(decodedWave);
}

oboe::Result Player::createAudioStream() {
    if (!decoderOpen) {
        return oboe::Result::ErrorInvalidState;
    }

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output);
    builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
    builder.setSharingMode(oboe::SharingMode::Shared);
    builder.setFormat(oboe::AudioFormat::I16);
    builder.setChannelCount(static_cast<int32_t>(channels));
    builder.setSampleRate(static_cast<int32_t>(sampleRate));
    builder.setCallback(this);

    oboe::Result result = builder.openStream(oboeStream);
    if (result != oboe::Result::OK) {
        oboeStream.reset();
        ALOGE("%s: can't open audio stream: %s", __func__, oboe::convertToText(result));
    }
    return result;
}

bool Player::seekToMicros(int64_t timeMicros) {
    if (!decoderOpen) {
        return false;
    }
    if (timeMicros < 0) {
        timeMicros = 0;
    }
    if (duration >= 0 && timeMicros > duration) {
        timeMicros = duration;
    }

    const uint64_t micros = static_cast<uint64_t>(timeMicros);
    uint64_t frame = (micros / 1000000ULL) * sampleRate;
    frame += ((micros % 1000000ULL) * sampleRate) / 1000000ULL;
    frame = std::min(frame, totalFrames);

    if (!drwav_seek_to_pcm_frame(&decoder, frame)) {
        return false;
    }
    currentFrame = frame;
    updateMediaTime();
    return true;
}

void Player::updateMediaTime() {
    const uint64_t seconds = currentFrame / sampleRate;
    const uint64_t remainingFrames = currentFrame % sampleRate;
    if (seconds > static_cast<uint64_t>(std::numeric_limits<int64_t>::max() / 1000000LL)) {
        playTime = std::numeric_limits<int64_t>::max();
    } else {
        playTime = static_cast<int64_t>(seconds * 1000000ULL
                + (remainingFrames * 1000000ULL) / sampleRate);
    }
}

void Player::applyGain(int16_t *samples, uint64_t frames) const {
    if (samples == nullptr || frames == 0) {
        return;
    }

    if (channels == 1) {
        for (uint64_t i = 0; i < frames; ++i) {
            samples[i] = static_cast<int16_t>(samples[i] * gainLeft);
        }
        return;
    }

    for (uint64_t frame = 0; frame < frames; ++frame) {
        const uint64_t index = frame * 2;
        samples[index] = static_cast<int16_t>(samples[index] * gainLeft);
        samples[index + 1] = static_cast<int16_t>(samples[index + 1] * gainRight);
    }
}

oboe::DataCallbackResult Player::onAudioReady(oboe::AudioStream * /*audioStream*/,
                                               void *audioData,
                                               int32_t numFrames) {
    auto *output = static_cast<int16_t *>(audioData);
    if (!decoderOpen || output == nullptr || numFrames <= 0) {
        return oboe::DataCallbackResult::Stop;
    }

    if (seekTime != -1) {
        const int64_t requestedTime = seekTime;
        seekTime = -1;
        if (!seekToMicros(requestedTime)) {
            std::memset(output, 0, sizeof(int16_t) * channels * numFrames);
            if (playerListener != nullptr) {
                playerListener->postEvent(ERROR, 0);
            }
            return oboe::DataCallbackResult::Stop;
        }
    }

    uint64_t framesWritten = 0;
    while (framesWritten < static_cast<uint64_t>(numFrames)) {
        const uint64_t framesNeeded = static_cast<uint64_t>(numFrames) - framesWritten;
        int16_t *writePtr = output + framesWritten * channels;
        const uint64_t framesRead = drwav_read_pcm_frames_s16(&decoder, framesNeeded, writePtr);

        applyGain(writePtr, framesRead);
        framesWritten += framesRead;
        currentFrame += framesRead;
        updateMediaTime();

        if (framesRead == framesNeeded) {
            break;
        }

        const bool repeat = looping == -1 || loopCount > 1;
        if (repeat) {
            if (looping != -1) {
                --loopCount;
            }
            const int64_t endTime = duration;
            if (!drwav_seek_to_pcm_frame(&decoder, 0)) {
                std::memset(output + framesWritten * channels,
                            0,
                            sizeof(int16_t) * channels * (numFrames - framesWritten));
                if (playerListener != nullptr) {
                    playerListener->postEvent(ERROR, 0);
                }
                return oboe::DataCallbackResult::Stop;
            }
            currentFrame = 0;
            playTime = 0;
            if (playerListener != nullptr) {
                playerListener->postEvent(RESTART, endTime);
            }
            continue;
        }

        std::memset(output + framesWritten * channels,
                    0,
                    sizeof(int16_t) * channels * (numFrames - framesWritten));
        currentFrame = totalFrames;
        playTime = duration;
        seekTime = 0; // The next start after EOM begins from the start.
        loopCount = looping;
        state = PREFETCHED;
        if (playerListener != nullptr) {
            playerListener->postEvent(STOP, playTime);
        }
        return oboe::DataCallbackResult::Stop;
    }

    return oboe::DataCallbackResult::Continue;
}

} // namespace wav
} // namespace mmapi
