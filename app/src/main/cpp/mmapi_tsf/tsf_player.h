// Copyright 2023 Yury Kharchenko
// Copyright 2026 H3NB
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
// Created by woesss on 01.08.2023.

#ifndef MMAPI_TSF_PLAYER_H
#define MMAPI_TSF_PLAYER_H

#include "tsf.h"
#include "tml.h"
#include "mmapi/PlayerListener.h"
#include "mmapi/BasePlayer.h"
#include "util/jbytearray.h"

#include <cstddef>
#include <cstdint>
#include <mutex>

namespace mmapi {
    namespace tiny {
        class Player : public BasePlayer {
            static tsf *soundBank;

            tsf *synth;
            tml_message *media;
            tml_message *currentMsg;
            std::mutex midiMutex;
            bool liveMode;

        public:
            Player(tsf *synth, tml_message *midi, const int64_t duration, bool liveMode = false);
            ~Player() override;

            void deallocate() override;
            void close() override;
            oboe::Result prefetch() override;
            int32_t setDataSource(util::JByteArrayPtr *data);
            int32_t writeMIDI(const uint8_t *data, size_t length);

            oboe::DataCallbackResult
            onAudioReady(oboe::AudioStream *audioStream, void *audioData, int32_t numFrames) override;

            static int32_t initSoundBank(const char *sound_bank);
            static int32_t createPlayer(const char *locator, Player **pPlayer);

        protected:
            oboe::Result createAudioStream() override;

        private:
            void processEvents(bool playMode);
        }; // class Player
    } // namespace tiny
} // namespace mmapi

#endif //MMAPI_TSF_PLAYER_H
