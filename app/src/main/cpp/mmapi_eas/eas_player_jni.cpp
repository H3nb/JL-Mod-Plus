//
// Created by woesss on 08.07.2023.
//

#include <jni.h>
#include "eas_player.h"
#include "util/jstring.h"
#include "eas_util.h"
#include "util/jbytearray.h"

namespace {

mmapi::eas::Player *requirePlayer(JNIEnv *env, jlong handle) {
    auto *player = reinterpret_cast<mmapi::eas::Player *>(handle);
    if (player == nullptr && !env->ExceptionCheck()) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "EAS player is closed");
    }
    return player;
}

void throwEasException(JNIEnv *env, const char *className, int32_t result) {
    if (env->ExceptionCheck()) {
        return;
    }
    const char *message = mmapi::eas::EAS_GetErrorString(result);
    env->ThrowNew(env->FindClass(className), message);
}

} // namespace

/* for C++ linkage */
#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT void JNICALL Java_ru_woesss_j2me_mmapi_synth_eas_LibEAS_nativeValidateSoundBank
(JNIEnv *env, jobject /*thiz*/, jstring soundBank) {
    if (soundBank == nullptr) {
        return;
    }

    util::JStringPtr bank(env, soundBank);
    int32_t result = mmapi::eas::Player::validateSoundBank(*bank);
    if (result != EAS_SUCCESS) {
        throwEasException(env, "java/lang/RuntimeException", result);
    }
}

JNIEXPORT jlong JNICALL Java_ru_woesss_j2me_mmapi_synth_eas_LibEAS_nativeCreatePlayer
(JNIEnv *env, jobject /*thiz*/, jstring locatorString, jstring soundBankString) {
    if (locatorString == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "locator == null");
        return 0;
    }

    util::JStringPtr locator(env, locatorString);
    const char *soundBank = nullptr;
    mmapi::eas::Player *player = nullptr;
    int32_t result;

    if (soundBankString == nullptr) {
        result = mmapi::eas::Player::createPlayer(*locator, nullptr, &player);
    } else {
        util::JStringPtr bank(env, soundBankString);
        soundBank = *bank;
        result = mmapi::eas::Player::createPlayer(*locator, soundBank, &player);
    }

    if (result != EAS_SUCCESS) {
        throwEasException(env, "javax/microedition/media/MediaException", result);
        return 0;
    }
    return reinterpret_cast<jlong>(player);
}

JNIEXPORT void JNICALL Java_ru_woesss_j2me_mmapi_synth_eas_LibEAS_finalize
(JNIEnv */*env*/, jobject /*thiz*/, jlong handle) {
    delete reinterpret_cast<mmapi::eas::Player *>(handle);
}

JNIEXPORT void JNICALL Java_ru_woesss_j2me_mmapi_synth_eas_LibEAS_realize
(JNIEnv *env, jobject /*thiz*/, jlong handle) {
    auto *player = requirePlayer(env, handle);
    if (player != nullptr && !player->realize()) {
        env->ThrowNew(env->FindClass("javax/microedition/media/MediaException"), "Unable to realize EAS player");
    }
}

JNIEXPORT void JNICALL Java_ru_woesss_j2me_mmapi_synth_eas_LibEAS_prefetch
(JNIEnv *env, jobject /*thiz*/, jlong handle) {
    auto *player = requirePlayer(env, handle);
    if (player == nullptr) {
        return;
    }
    oboe::Result result = player->prefetch();
    if (result != oboe::Result::OK) {
        env->ThrowNew(env->FindClass("javax/microedition/media/MediaException"), oboe::convertToText(result));
    }
}

JNIEXPORT void JNICALL Java_ru_woesss_j2me_mmapi_synth_eas_LibEAS_start
(JNIEnv *env, jobject /*thiz*/, jlong handle) {
    auto *player = requirePlayer(env, handle);
    if (player == nullptr) {
        return;
    }
    oboe::Result result = player->start();
    if (result != oboe::Result::OK) {
        env->ThrowNew(env->FindClass("javax/microedition/media/MediaException"), oboe::convertToText(result));
    }
}

JNIEXPORT void JNICALL Java_ru_woesss_j2me_mmapi_synth_eas_LibEAS_pause
(JNIEnv *env, jobject /*thiz*/, jlong handle) {
    auto *player = requirePlayer(env, handle);
    if (player == nullptr) {
        return;
    }
    oboe::Result result = player->pause();
    if (result != oboe::Result::OK) {
        env->ThrowNew(env->FindClass("javax/microedition/media/MediaException"), oboe::convertToText(result));
    }
}

JNIEXPORT void JNICALL Java_ru_woesss_j2me_mmapi_synth_eas_LibEAS_deallocate
(JNIEnv *env, jobject /*thiz*/, jlong handle) {
    auto *player = requirePlayer(env, handle);
    if (player != nullptr) {
        player->deallocate();
    }
}

JNIEXPORT void JNICALL Java_ru_woesss_j2me_mmapi_synth_eas_LibEAS_close
(JNIEnv *env, jobject /*thiz*/, jlong handle) {
    auto *player = requirePlayer(env, handle);
    if (player != nullptr) {
        player->close();
    }
}

JNIEXPORT jlong JNICALL Java_ru_woesss_j2me_mmapi_synth_eas_LibEAS_setMediaTime
(JNIEnv *env, jobject /*thiz*/, jlong handle, jlong now) {
    auto *player = requirePlayer(env, handle);
    return player == nullptr ? -1 : player->setMediaTime(now);
}

JNIEXPORT jlong JNICALL Java_ru_woesss_j2me_mmapi_synth_eas_LibEAS_getMediaTime
(JNIEnv *env, jobject /*thiz*/, jlong handle) {
    auto *player = requirePlayer(env, handle);
    return player == nullptr ? -1 : player->getMediaTime();
}

JNIEXPORT void JNICALL Java_ru_woesss_j2me_mmapi_synth_eas_LibEAS_setRepeat
(JNIEnv *env, jobject /*thiz*/, jlong handle, jint count) {
    auto *player = requirePlayer(env, handle);
    if (player != nullptr) {
        player->setRepeat(count);
    }
}

JNIEXPORT void JNICALL Java_ru_woesss_j2me_mmapi_synth_eas_LibEAS_setVolume
(JNIEnv *env, jobject /*thiz*/, jlong handle, jfloat left, jfloat right) {
    auto *player = requirePlayer(env, handle);
    if (player != nullptr) {
        player->setVolume(left, right);
    }
}

JNIEXPORT jlong JNICALL Java_ru_woesss_j2me_mmapi_synth_eas_LibEAS_getDuration
(JNIEnv *env, jobject /*thiz*/, jlong handle) {
    auto *player = requirePlayer(env, handle);
    return player == nullptr ? -1 : player->duration;
}

JNIEXPORT void JNICALL Java_ru_woesss_j2me_mmapi_synth_eas_LibEAS_setListener
(JNIEnv *env, jobject /*thiz*/, jlong handle, jobject listener) {
    auto *player = requirePlayer(env, handle);
    if (player == nullptr) {
        return;
    }
    if (listener == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "listener == null");
        return;
    }
    player->setListener(new mmapi::PlayerListener(env, listener));
}

JNIEXPORT void JNICALL Java_ru_woesss_j2me_mmapi_synth_eas_LibEAS_setDataSource
(JNIEnv *env, jobject /*thiz*/, jlong handle, jbyteArray data) {
    auto *player = requirePlayer(env, handle);
    if (player == nullptr) {
        return;
    }
    if (data == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "data == null");
        return;
    }

    auto *file = new mmapi::eas::MemFile(env, data);
    int32_t result = player->setDataSource(file);
    if (result != EAS_SUCCESS) {
        delete file;
        throwEasException(env, "javax/microedition/media/MediaException", result);
    }
}

JNIEXPORT jint JNICALL Java_ru_woesss_j2me_mmapi_synth_eas_LibEAS_writeMIDI
(JNIEnv *env, jobject /*thiz*/, jlong handle, jbyteArray data, jint offset, jint length) {
    auto *player = requirePlayer(env, handle);
    if (player == nullptr) {
        return 0;
    }
    if (data == nullptr || offset < 0 || length < 0 || offset > env->GetArrayLength(data) - length) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "invalid MIDI byte range");
        return 0;
    }
    util::JByteArrayPtr ptr(env, data, offset, length);
    return player->writeMIDI(ptr);
}

#ifdef __cplusplus
} /* end extern "C" */
#endif
