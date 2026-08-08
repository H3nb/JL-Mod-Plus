//
// Created by woesss on 08.07.2023.
//

#ifndef MMAPI_EAS_PLAYER_H
#define MMAPI_EAS_PLAYER_H

#include "libsonivox/eas.h"
#include "eas_file.h"
#include "mmapi/PlayerListener.h"
#include "mmapi/BasePlayer.h"
#include "util/jbytearray.h"

namespace mmapi {
    namespace eas {
        class Player : public BasePlayer {
            const S_EAS_LIB_CONFIG *easConfig = EAS_Config();
            EAS_DATA_HANDLE easHandle;
            EAS_HANDLE media;
            EAS_HANDLE interactive;
            BaseFile *file;

        public:
            Player(EAS_DATA_HANDLE easHandle, BaseFile *file, EAS_HANDLE stream, const int64_t duration);
            Player(EAS_DATA_HANDLE easHandle);
            ~Player() override;

            void deallocate() override;
            void close() override;
            oboe::Result pause() override;
            oboe::Result prefetch() override;
            int32_t setDataSource(BaseFile *pFile);
            jint writeMIDI(util::JByteArrayPtr &data);

            oboe::DataCallbackResult onAudioReady(oboe::AudioStream *audioStream,
                                                  void *audioData,
                                                  int32_t numFrames)
                                                  override;

            /** Validates that SONiVOX can load the selected DLS/SF2 bank. */
            static int32_t validateSoundBank(const char *soundBank);

            /**
             * Creates one independent EAS instance. A custom soundbank, when
             * supplied, is loaded into that instance only; no process-global
             * SONiVOX sound-library handle is shared between players.
             */
            static int32_t createPlayer(const char *locator,
                                        const char *soundBank,
                                        Player **pPlayer);

        protected:
            oboe::Result createAudioStream() override;

        private:
            static int32_t configureHandle(EAS_DATA_HANDLE easHandle, const char *soundBank);
            static int32_t openSource(EAS_DATA_HANDLE easHandle,
                                      BaseFile *pFile,
                                      EAS_HANDLE *outStream,
                                      int64_t *outDuration);
        }; // class Player
    } // namespace eas
} // namespace mmapi

#endif //MMAPI_EAS_PLAYER_H
