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
        Player::Player(EAS_DATA_HANDLE easHandle, BaseFile *file, EAS_HANDLE stream, const int64_t duration)
                : BasePlayer(duration), easHandle(easHandle), media(stream), interactive(nullptr), file(file) {}

        Player::Player(EAS_DATA_HANDLE easHandle) : Player(easHandle, nullptr, nullptr, -1) {}

        Player::~Player() {
            close();
        }

        int32_t Player::configureHandle(EAS_DATA_HANDLE easHandle, const char *soundBank) {
            if (easHandle == nullptr) {
                return EAS_ERROR_INVALID_HANDLE;
            }

            EAS_RESULT result = EAS_SetHeaderSearchFlag(easHandle, EAS_FALSE);
            if (result != EAS_SUCCESS) {
                return result;
            }

            if (soundBank == nullptr || soundBank[0] == '\0') {
                result = EAS_SetParameter(easHandle,
                                          EAS_MODULE_REVERB,
                                          EAS_PARAM_REVERB_PRESET,
                                          EAS_PARAM_REVERB_CHAMBER);
                if (result != EAS_SUCCESS) {
                    return result;
                }
                return EAS_SetParameter(easHandle,
                                        EAS_MODULE_REVERB,
                                        EAS_PARAM_REVERB_BYPASS,
                                        EAS_FALSE);
            }

            IOFile bankFile(soundBank, "rb");
            if (!bankFile.isOpen()) {
                return EAS_ERROR_FILE_OPEN_FAILED;
            }
            return EAS_LoadDLSCollection(easHandle, nullptr, &bankFile.easFile);
        }

        int32_t Player::createPlayer(const char *locator,
                                     const char *soundBank,
                                     Player **pPlayer) {
            if (locator == nullptr || pPlayer == nullptr) {
                return EAS_ERROR_INVALID_PARAMETER;
            }
            *pPlayer = nullptr;

            EAS_DATA_HANDLE easHandle = nullptr;
            EAS_RESULT result = EAS_Init(&easHandle);
            if (result != EAS_SUCCESS) {
                return result;
            }

            result = configureHandle(easHandle, soundBank);
            if (result != EAS_SUCCESS) {
                EAS_Shutdown(easHandle);
                return result;
            }

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

            BaseFile *file = new IOFile(locator, "rb");
            auto *ioFile = static_cast<IOFile *>(file);
            if (!ioFile->isOpen()) {
                delete file;
                EAS_Shutdown(easHandle);
                return EAS_ERROR_FILE_OPEN_FAILED;
            }

            EAS_HANDLE stream = nullptr;
            int64_t duration = -1;
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
            /*
             * JSR-135 deallocate() releases scarce resources and moves the
             * Player back to REALIZED without rewinding media time. The EAS
             * parser/stream remains alive, so closing the Oboe output is enough;
             * preserve both its current parser position and any pending seek.
             */
            BasePlayer::deallocate();
        }

        void Player::close() {
            if (easHandle == nullptr) {
                return;
            }

            BasePlayer::close();
            if (media != nullptr) {
                EAS_CloseFile(easHandle, media);
            }
            if (file != nullptr) {
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

        int32_t Player::validateSoundBank(const char *soundBank) {
            if (soundBank == nullptr || soundBank[0] == '\0') {
                return EAS_SUCCESS;
            }

            EAS_DATA_HANDLE easHandle = nullptr;
            EAS_RESULT result = EAS_Init(&easHandle);
            if (result != EAS_SUCCESS) {
                return result;
            }

            result = EAS_SetHeaderSearchFlag(easHandle, EAS_FALSE);
            if (result == EAS_SUCCESS) {
                IOFile bankFile(soundBank, "rb");
                if (!bankFile.isOpen()) {
                    result = EAS_ERROR_FILE_OPEN_FAILED;
                } else {
                    result = EAS_LoadDLSCollection(easHandle, nullptr, &bankFile.easFile);
                }
            }

            EAS_RESULT shutdownResult = EAS_Shutdown(easHandle);
            if (result == EAS_SUCCESS && shutdownResult != EAS_SUCCESS) {
                return shutdownResult;
            }
            return result;
        }

        jint Player::writeMIDI(util::JByteArrayPtr &data) {
            if (easHandle == nullptr || interactive == nullptr) {
                ALOGE("%s: player has no interactive MIDI stream", __func__);
                return 0;
            }
            EAS_RESULT result = EAS_WriteMIDIStream(easHandle,
                                                    interactive,
                                                    reinterpret_cast<EAS_U8 *>(data.buffer),
                                                    data.length);
            if (result != EAS_SUCCESS) {
                ALOGE("EAS_WriteMIDIStream return: %s", EAS_GetErrorString(result));
                return 0;
            }
            return data.length;
        }

        int32_t Player::setDataSource(BaseFile *pFile) {
            if (pFile == nullptr || easHandle == nullptr) {
                return EAS_ERROR_INVALID_PARAMETER;
            }

            EAS_HANDLE stream = nullptr;
            int32_t result = openSource(easHandle, pFile, &stream, &duration);
            if (result != EAS_SUCCESS) {
                return result;
            }
            if (media != nullptr) {
                EAS_CloseFile(easHandle, media);
            }
            if (file != nullptr) {
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
            if (easHandle == nullptr || pFile == nullptr || outStream == nullptr || outDuration == nullptr) {
                return EAS_ERROR_INVALID_PARAMETER;
            }

            EAS_HANDLE stream = nullptr;
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
                return EAS_ERROR_FILE_FORMAT;
            }
            EAS_I32 length = -1;
            result = EAS_ParseMetaData(easHandle, stream, &length);
            if (result != EAS_SUCCESS) {
                EAS_CloseFile(easHandle, stream);
                return result;
            }
            *outStream = stream;
            *outDuration = length > 0 ? length * 1000LL : length;
            return EAS_SUCCESS;
        }

        oboe::Result Player::prefetch() {
            if (media == nullptr && interactive == nullptr) {
                return oboe::Result::ErrorInvalidState;
            }
            // Prefetch acquires/configures the audio device but does not begin
            // rendering. This keeps device://midi in PREFETCHED until Player.start().
            return BasePlayer::prefetch();
        }

        oboe::Result Player::pause() {
            // stop() on device://midi must pause rendering just like sequenced
            // media; MIDIControl remains available while the Player is realized.
            return BasePlayer::pause();
        }

        oboe::DataCallbackResult
        Player::onAudioReady(oboe::AudioStream *audioStream, void *audioData, int32_t numFrames) {
            memset(audioData, 0, sizeof(EAS_PCM) * easConfig->numChannels * numFrames);
            if (seekTime == -1 && media) {
                EAS_STATE easState = EAS_STATE_PLAY;
                EAS_State(easHandle, media, &easState);
                if (easState == EAS_STATE_STOPPED || easState == EAS_STATE_ERROR) {
                    seekTime = 0;
                    if (looping == -1 || (--loopCount) > 0) {
                        if (playerListener != nullptr) {
                            playerListener->postEvent(RESTART, playTime);
                        }
                    } else {
                        // A configured finite loop count applies again the next
                        // time start() is called after end-of-media.
                        loopCount = looping;
                        state = PREFETCHED;
                        if (playerListener != nullptr) {
                            playerListener->postEvent(STOP, playTime);
                        }
                        return oboe::DataCallbackResult::Stop;
                    }
                }
            }

            if (seekTime != -1 && media) {
                EAS_I32 ms = static_cast<EAS_I32>(seekTime / 1000LL);
                EAS_RESULT result = EAS_Locate(easHandle, media, ms, EAS_FALSE);
                if (result != EAS_SUCCESS) {
                    ALOGE("%s: EAS_Locate() return %s", __func__, EAS_GetErrorString(result));
                }
                seekTime = -1;
            }

            auto *stream = static_cast<EAS_PCM *>(audioData);
            int numFramesOutput = 0;
            EAS_RESULT result;
            for (int i = 0; i < NUM_COMBINE_BUFFERS; i++) {
                EAS_I32 numRendered;
                result = EAS_Render(easHandle, stream, easConfig->mixBufferSize, &numRendered);
                if (result != EAS_SUCCESS) {
                    if (playerListener != nullptr) {
                        playerListener->postEvent(ERROR, result);
                    }
                    ALOGE("%s: EAS_Render() returned %s, numFramesOutput = %d",
                          __func__,
                          EAS_GetErrorString(result),
                          numFramesOutput);
                    return oboe::DataCallbackResult::Stop;
                }
                for (int j = 0; j < numRendered; ++j) {
                    *stream++ *= gainLeft;
                    *stream++ *= gainRight;
                }
                numFramesOutput += numRendered;
            }

            if (media != nullptr) {
                EAS_I32 pTime = -1;
                result = EAS_GetLocation(easHandle, media, &pTime);
                if (result != EAS_SUCCESS) {
                    ALOGE("%s: EAS_GetLocation return %s", __func__, EAS_GetErrorString(result));
                }
                playTime = pTime != -1 ? pTime * 1000LL : -1;
            }
            return oboe::DataCallbackResult::Continue;
        }
    } // namespace eas
} // namespace mmapi
