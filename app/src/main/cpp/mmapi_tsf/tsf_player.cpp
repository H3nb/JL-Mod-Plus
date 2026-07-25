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

#define TSF_IMPLEMENTATION
#define TML_IMPLEMENTATION

#include "tsf_player.h"
#include "util/log.h"

#define LOG_TAG "MMAPI"
#define NUM_CHANNELS 2

namespace mmapi {
    namespace tiny {
        tsf *Player::soundBank{};

        Player::Player(tsf *synth, tml_message *midi, const int64_t duration, bool liveMode)
                : BasePlayer(duration), synth(synth), media(midi), currentMsg(midi),
                  liveMode(liveMode) {
            //Initialize preset on special 10th MIDI channel to use percussion sound bank (128) if available
            tsf_channel_set_bank_preset(synth, 9, 128, 0);
        }

        Player::~Player() {
            close();
        }

        int32_t Player::createPlayer(const char *locator, Player **pPlayer) {
            if (locator == nullptr) {
                return -3;
            }
            tsf *synth = tsf_copy(soundBank);
            if (synth == nullptr) {
                return -1;
            }
            if (strcmp(locator, "device://tone") == 0) {
                *pPlayer = new Player(synth, nullptr, -1);
                return 0;
            }
            if (strcmp(locator, "device://midi") == 0) {
                *pPlayer = new Player(synth, nullptr, -1, true);
                return 0;
            }
            tml_message *midi = tml_load_filename(locator);
            if (midi == nullptr) {
                tsf_close(synth);
                return -2;
            }
            unsigned int timeLength;
            tml_get_info(midi, nullptr, nullptr, nullptr, nullptr, &timeLength);
            *pPlayer = new Player(synth, midi, timeLength * 1000LL);
            (*pPlayer)->playTime = 0;
            return 0;
        }

        oboe::Result Player::createAudioStream() {
            oboe::AudioStreamBuilder builder;
            builder.setDirection(oboe::Direction::Output);
            builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
            builder.setSharingMode(oboe::SharingMode::Shared);
            builder.setFormat(oboe::AudioFormat::Float);
            builder.setChannelCount(NUM_CHANNELS);
            builder.setCallback(this);
            builder.setFormatConversionAllowed(true);

            oboe::Result result = builder.openStream(oboeStream);
            if (result != oboe::Result::OK) {
                oboeStream.reset();
                ALOGE("%s: can't open audio stream. %s", __func__, oboe::convertToText(result));
                return result;
            }

            synth->outSampleRate = oboeStream->getSampleRate();
            return result;
        }

        void Player::deallocate() {
            BasePlayer::deallocate();
            seekTime = 0;
        }

        void Player::close() {
            BasePlayer::close();
            std::lock_guard<std::mutex> lock(midiMutex);
            if (media != nullptr) {
                tml_free(media);
            }
            tsf_close(synth);
            media = nullptr;
            synth = nullptr;
        }

        int32_t Player::initSoundBank(const char *sound_bank) {
            tsf *synth = tsf_load_filename(sound_bank);
            if (synth == nullptr) {
                return -1;
            }
            Player::soundBank = synth;
            return 0;
        }

        int32_t Player::setDataSource(util::JByteArrayPtr *data) {
            tml_message *midi = tml_load_memory(data->buffer, data->length);
            if (midi == nullptr) {
                return -2;
            }
            unsigned int timeLength;
            tml_get_info(midi, nullptr, nullptr, nullptr, nullptr, &timeLength);
            std::lock_guard<std::mutex> lock(midiMutex);
            duration = timeLength * 1000LL;
            if (media != nullptr) {
                tml_free(media);
            }
            media = midi;
            currentMsg = midi;
            playTime = 0;
            return 0;
        }

        int32_t Player::writeMIDI(const uint8_t *data, size_t length) {
            if (data == nullptr || length == 0 || synth == nullptr) {
                return 0;
            }

            std::lock_guard<std::mutex> lock(midiMutex);
            size_t offset = 0;
            uint8_t runningStatus = 0;
            while (offset < length) {
                uint8_t status = data[offset];
                if ((status & 0x80) != 0) {
                    ++offset;
                    if (status >= 0xF8) {
                        // MIDI real-time bytes may be interleaved anywhere.
                        continue;
                    }
                    if (status >= 0xF0) {
                        // SysEx and system-common messages are not part of
                        // the channel-voice subset supported by this bridge.
                        return static_cast<int32_t>(offset - 1);
                    }
                    runningStatus = status;
                } else if (runningStatus == 0) {
                    return static_cast<int32_t>(offset);
                } else {
                    status = runningStatus;
                }

                const uint8_t type = status & 0xF0;
                const uint8_t channel = status & 0x0F;
                const size_t parameterCount = type == 0xC0 || type == 0xD0 ? 1 : 2;
                if (length - offset < parameterCount) {
                    return static_cast<int32_t>(offset);
                }

                const uint8_t data1 = data[offset++];
                const uint8_t data2 = parameterCount == 2 ? data[offset++] : 0;
                switch (type) {
                    case TML_NOTE_OFF:
                        tsf_channel_note_off(synth, channel, data1);
                        break;
                    case TML_NOTE_ON:
                        if (data2 == 0) {
                            tsf_channel_note_off(synth, channel, data1);
                        } else {
                            tsf_channel_note_on(synth, channel, data1,
                                                static_cast<float>(data2) / 127.0f);
                        }
                        break;
                    case TML_CONTROL_CHANGE:
                        tsf_channel_midi_control(synth, channel, data1, data2);
                        break;
                    case TML_PROGRAM_CHANGE:
                        tsf_channel_set_presetnumber(synth, channel, data1, channel == 9);
                        break;
                    case TML_PITCH_BEND:
                        tsf_channel_set_pitchwheel(synth, channel,
                                                   data1 | (static_cast<int>(data2) << 7));
                        break;
                    case TML_KEY_PRESSURE:
                    case TML_CHANNEL_PRESSURE:
                        // TinySoundFont has no poly/channel pressure API.
                        break;
                    default:
                        return static_cast<int32_t>(offset);
                }
            }
            return static_cast<int32_t>(offset);
        }

        oboe::Result Player::prefetch() {
            if (media == nullptr && !liveMode) {
                return oboe::Result::ErrorInvalidState;
            }
            return BasePlayer::prefetch();
        }

        void Player::processEvents(bool playMode) {
            //Loop through all MIDI messages which need to be played up until the current playback time
            for (; currentMsg && playTime >= currentMsg->time * 1000LL; currentMsg = currentMsg->next) {
                switch (currentMsg->type) {
                    case TML_PROGRAM_CHANGE:
                        //channel program (preset) change (special handling for 10th MIDI channel with drums)
                        tsf_channel_set_presetnumber(synth, currentMsg->channel,
                                                     currentMsg->program,
                                                     (currentMsg->channel == 9));
                        break;
                    case TML_NOTE_ON: //play a note
                        if (playMode) {
                            tsf_channel_note_on(synth, currentMsg->channel, currentMsg->key,
                                                static_cast<float>(currentMsg->velocity) / 127.0f);
                        }
                        break;
                    case TML_NOTE_OFF: //stop a note
                        if (playMode) {
                            tsf_channel_note_off(synth, currentMsg->channel, currentMsg->key);
                        }
                        break;
                    case TML_PITCH_BEND: //pitch wheel modification
                        tsf_channel_set_pitchwheel(synth, currentMsg->channel,
                                                   currentMsg->pitch_bend);
                        break;
                    case TML_CONTROL_CHANGE: //MIDI controller messages
                        tsf_channel_midi_control(synth, currentMsg->channel, currentMsg->control,
                                                 currentMsg->control_value);
                        break;
                }
            }
        }

        oboe::DataCallbackResult
        Player::onAudioReady(oboe::AudioStream *audioStream, void *audioData, int32_t numFrames) {
            std::lock_guard<std::mutex> lock(midiMutex);
            memset(audioData, 0, sizeof(float) * NUM_CHANNELS * numFrames);
            if (!liveMode && seekTime == -1 && currentMsg == nullptr) {
                seekTime = 0;
                if (looping == -1 || (--loopCount) > 0) {
                    playerListener->postEvent(RESTART, playTime);
                } else {
                    state = PREFETCHED;
                    playerListener->postEvent(STOP, playTime);
                    return oboe::DataCallbackResult::Stop;
                }
            }

            if (!liveMode && seekTime != -1) {
                if (seekTime < playTime) {
                    tsf_reset(synth);
                    //Initialize preset on special 10th MIDI channel to use percussion sound bank (128) if available
                    tsf_channel_set_bank_preset(synth, 9, 128, 0);
                    currentMsg = media;
                } else {
                    tsf_note_off_all(synth);
                }
                playTime = seekTime - 1;
                seekTime = -1;
                processEvents(false);
            }
            //Number of samples to process
            int sampleBlock = TSF_RENDER_EFFECTSAMPLEBLOCK;
            auto *stream = static_cast<float *>(audioData);
            for (; numFrames > 0; numFrames -= sampleBlock) {
                //We progress the MIDI playback and then process TSF_RENDER_EFFECTSAMPLEBLOCK samples at once
                if (sampleBlock > numFrames) {
                    sampleBlock = numFrames;
                }

                playTime += sampleBlock * 1000000LL / audioStream->getSampleRate();
                processEvents(true);

                // Render the block of audio samples in float format
                tsf_render_float(synth, stream, sampleBlock);
                for (int j = 0; j < sampleBlock; ++j) {
                    *stream++ *= gainLeft;
                    *stream++ *= gainRight;
                }
            }
            return oboe::DataCallbackResult::Continue;
        }
    } // namespace tiny
} // namespace mmapi
