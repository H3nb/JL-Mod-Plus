//
// Created by woesss on 08.07.2023.
//

#include "eas_player.h"
#include "util/log.h"
#include "eas_util.h"
#include "libsonivox/eas_reverb.h"

#define LOG_TAG "MMAPI"
#define NUM_COMBINE_BUFFERS 4

namespace mmapi {
    namespace eas {
        EAS_DLSLIB_HANDLE Player::soundBank{nullptr};

        Player::Player(EAS_DATA_HANDLE easHandle, BaseFile *file, EAS_HANDLE stream, const int64_t duration)
                : BasePlayer(duration), easHandle(easHandle), media(stream), interactive(nullptr), file(file) {
            EAS_DLSLIB_HANDLE dls = Player::soundBank;
            if (dls == nullptr) {
                EAS_SetParameter(easHandle, EAS_MODULE_REVERB, EAS_PARAM_REVERB_PRESET, EAS_PARAM_REVERB_CHAMBER);
                EAS_SetParameter(easHandle, EAS_MODULE_REVERB, EAS_PARAM_REVERB_BYPASS, EAS_FALSE);
            } else {
                EAS_SetGlobalDLSLib(easHandle, dls);
            }
        }

        Player::Player(EAS_DATA_HANDLE easHandle) : Player(easHandle, nullptr, nullptr, -1) {}

        Player::~Player() {
            close();
        }

        int32_t Player::createPlayer(const char *locator, Player **pPlayer) {
            if (locator == nullptr) {
                return EAS_ERROR_INVALID_PARAMETER;
            }
            EAS_DATA_HANDLE easHandle;
            EAS_RESULT result = EAS_Init(&easHandle);
            if (result != EAS_SUCCESS) {
                return result;
            }
            EAS_SetHeaderSearchFlag(easHandle, false);
            if (strcmp(locator, "device://tone") == 0) {
                *pPlayer = new Player(easHandle);
                return EAS_SUCCESS;
            } else if (strcmp(locator, "device://midi") == 0) {
                Player *player = new Player(easHandle);
                result = EAS_OpenMIDIStream(easHandle, &player->interactive, nullptr);
                if (result != EAS_SUCCESS) {
                    ALOGE("EAS_OpenMIDIStream return: %s", EAS_GetErrorString(result));
                    delete player;
                    return result;
                }
                *pPlayer = player;
                return EAS_SUCCESS;
            }
            BaseFile *file = new IOFile(locator, "rb");;
            EAS_HANDLE stream;
            int64_t duration;
            result = openSource(easHandle, file, &stream, &duration);
            if (result != EAS_SUCCESS) {
                EAS_Shutdown(easHandle);
                delete file;
                return result;
            }
            *pPlayer = new Player(easHandle, file, stream, duration);
            (*pPlayer)->playTime = 0;
            return result;
        }

        oboe::Result Player::createAudioStream() {
            oboe::AudioStreamBuilder builder;
            builder.setDirection(oboe::Direction::Output);
            builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
            builder.setSharingMode(oboe::SharingMode::Shared);
            builder.setFormat(oboe::AudioFormat::I16);
            builder.setChannelCount(static_cast<int>(easConfig->numChannels));
            builder.setSampleRate(static_cast<int>(easConfig->sampleRate));
            builder.setCallback(this);
            builder.setFramesPerDataCallback(easConfig->mixBufferSize * NUM_COMBINE_BUFFERS);

            oboe::Result result = builder.openStream(oboeStream);
            if (result != oboe::Result::OK) {
                oboeStream.reset();
                ALOGE("%s: can't open audio stream. %s", __func__, oboe::convertToText(result));
            }
            return result;
        }

        void Player::deallocate() {
            BasePlayer::deallocate();
            if (file != nullptr) {
                seekTime = 0;
            }
        }

        void Player::close() {
            BasePlayer::close();
            if (media != nullptr) {
                EAS_CloseFile(easHandle, media);
                delete file;
            }
            if (interactive != nullptr) {
                EAS_CloseMIDIStream(easHandle, interactive);
            }
            EAS_Shutdown(easHandle);
            file = nullptr;
            media = nullptr;
            interactive = nullptr;
            easHandle = nullptr;
        }

        int32_t Player::initSoundBank(const char *sound_bank) {
            EAS_DATA_HANDLE easHandle;
            EAS_RESULT result = EAS_Init(&easHandle);
            if (result != EAS_SUCCESS) {
                return result;
            }
            EAS_SetHeaderSearchFlag(easHandle, false);
            IOFile file(sound_bank, "rb");
            result = EAS_LoadDLSCollection(easHandle, nullptr, &file.easFile);
            if (result == EAS_SUCCESS) {
                EAS_GetGlobalDLSLib(easHandle, &Player::soundBank);
            }
            EAS_Shutdown(easHandle);
            return result;
        }

        jint Player::writeMIDI(util::JByteArrayPtr &data) {
            if (interactive == nullptr) {
                ALOGE("%s: player has no interactive MIDI stream", __func__);
                return 0;
            }
            EAS_RESULT result = EAS_WriteMIDIStream(easHandle, interactive, (EAS_U8 *) data.buffer, data.length);
            if (result != EAS_SUCCESS) {
                ALOGE("EAS_WriteMIDIStream return: %s", EAS_GetErrorString(result));
            }
            return data.length;
        }

        int32_t Player::setDataSource(BaseFile *pFile) {
            EAS_HANDLE stream = nullptr;
            int32_t result = openSource(easHandle, pFile, &stream, &duration);
            if (result != EAS_SUCCESS) {
                return result;
            }
            if (media != nullptr) {
                EAS_CloseFile(easHandle, media);
                delete file;
            }
            media = stream;
            file = pFile;
            playTime = 0;
            return result;
        }

        int32_t Player::openSource(EAS_DATA_HANDLE easHandle,
                                   BaseFile *pFile,
                                   EAS_HANDLE *outStream,
                                   int64_t *outDuration) {
            EAS_HANDLE stream;
            EAS_RESULT result = EAS_OpenFile(easHandle, &pFile->easFile, &stream);
            if (result != EAS_SUCCESS) {
                result = EAS_MMAPIToneControl(easHandle, &pFile->easFile, &stream);
            }
            if (result != EAS_SUCCESS) {
                return result;
            }
            result = EAS_Prepare(easHandle, stream);
            if (result != EAS_SUCCESS) {
                EAS_CloseFile(easHandle, stream);
                return result;
            }
            EAS_I32 type = EAS_FILE_UNKNOWN;
            result = EAS_GetFileType(easHandle, stream, &type);
            if (result != EAS_SUCCESS) {
                EAS_CloseFile(easHandle, stream);
                return result;
            }
            ALOGV("EAS_checkFileType(): %s file recognized", EAS_GetFileTypeString(type));
            if (type == EAS_FILE_UNKNOWN) {
                EAS_CloseFile(easHandle, stream);
                result = EAS_ERROR_FILE_FORMAT;
                return result;
            }
            EAS_I32 duration;
            result = EAS_ParseMetaData(easHandle, stream, &duration);
            if (result != EAS_SUCCESS) {
                EAS_CloseFile(easHandle, stream);
                return result;
            }
            *outStream = stream;
            *outDuration = static_cast<int64_t>(duration) * 1000;
            return EAS_SUCCESS;
        }

        oboe::Result Player::pause() {
            oboe::Result result = BasePlayer::pause();
            if (result != oboe::Result::OK) {
                return result;
            }
            if (media != nullptr) {
                EAS_Pause(easHandle, media);
            }
            return result;
        }

        oboe::Result Player::prefetch() {
            oboe::Result result = BasePlayer::prefetch();
            if (result != oboe::Result::OK) {
                return result;
            }
            if (media != nullptr) {
                EAS_Resume(easHandle, media);
            }
            return result;
        }

        oboe::DataCallbackResult Player::onAudioReady(oboe::AudioStream *audioStream,
                                                       void *audioData,
                                                       int32_t numFrames) {
            auto *buffer = static_cast<EAS_PCM *>(audioData);
            EAS_I32 renderedFrames = 0;
            while (renderedFrames < numFrames) {
                EAS_I32 frames = easConfig->mixBufferSize;
                if (frames > numFrames - renderedFrames) {
                    frames = numFrames - renderedFrames;
                }
                EAS_I32 numGenerated = 0;
                EAS_RESULT result = EAS_Render(easHandle,
                                               buffer + renderedFrames * easConfig->numChannels,
                                               frames,
                                               &numGenerated);
                if (result != EAS_SUCCESS) {
                    ALOGE("EAS_Render return: %s", EAS_GetErrorString(result));
                    postEvent(PlayerListener::ERROR, result);
                    return oboe::DataCallbackResult::Stop;
                }
                renderedFrames += numGenerated;
                if (numGenerated < frames) {
                    const EAS_I32 remaining = frames - numGenerated;
                    memset(buffer + renderedFrames * easConfig->numChannels,
                           0,
                           static_cast<size_t>(remaining * easConfig->numChannels) * sizeof(EAS_PCM));
                    renderedFrames += remaining;
                }
            }

            if (media == nullptr) {
                return oboe::DataCallbackResult::Continue;
            }

            EAS_STATE state;
            EAS_RESULT result = EAS_State(easHandle, media, &state);
            if (result != EAS_SUCCESS) {
                ALOGE("EAS_State return: %s", EAS_GetErrorString(result));
                postEvent(PlayerListener::ERROR, result);
                return oboe::DataCallbackResult::Stop;
            }
            if (state == EAS_STATE_STOPPED) {
                if (repeatCount == -1 || --repeatCount > 0) {
                    EAS_Locate(easHandle, media, 0, false);
                    EAS_Resume(easHandle, media);
                    playTime = 0;
                    postEvent(PlayerListener::RESTART, playTime);
                    return oboe::DataCallbackResult::Continue;
                }
                playTime = getTime();
                postEvent(PlayerListener::STOP, playTime);
                return oboe::DataCallbackResult::Stop;
            }

            playTime = getTime();
            return oboe::DataCallbackResult::Continue;
        }
    } // namespace eas
} // namespace mmapi
