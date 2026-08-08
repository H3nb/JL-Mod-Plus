//
// Copyright 2026 H3NB
// SPDX-License-Identifier: Apache-2.0
//

#include <jni.h>

#include "wav_player.h"
#include "util/jstring.h"

namespace {

mmapi::wav::Player *requirePlayer(JNIEnv *env, jlong handle) {
    auto *player = reinterpret_cast<mmapi::wav::Player *>(handle);
    if (player == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "WAV player is closed");
    }
    return player;
}

void throwMediaException(JNIEnv *env, const char *message) {
    env->ThrowNew(env->FindClass("javax/microedition/media/MediaException"), message);
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_ru_woesss_j2me_mmapi_audio_WavPlayer_nativeCreate(JNIEnv *env,
                                                        jclass /*clazz*/,
                                                        jstring path) {
    if (path == nullptr) {
        throwMediaException(env, "WAV data source is missing");
        return 0;
    }

    util::JStringPtr sourcePath(env, path);
    mmapi::wav::Player *player = nullptr;
    if (!mmapi::wav::Player::create(*sourcePath, &player)) {
        throwMediaException(env, "Unsupported or invalid WAV file");
        return 0;
    }
    return reinterpret_cast<jlong>(player);
}

JNIEXPORT void JNICALL
Java_ru_woesss_j2me_mmapi_audio_WavPlayer_nativeDestroy(JNIEnv * /*env*/,
                                                         jclass /*clazz*/,
                                                         jlong handle) {
    delete reinterpret_cast<mmapi::wav::Player *>(handle);
}

JNIEXPORT void JNICALL
Java_ru_woesss_j2me_mmapi_audio_WavPlayer_nativeRealize(JNIEnv *env,
                                                         jclass /*clazz*/,
                                                         jlong handle) {
    auto *player = requirePlayer(env, handle);
    if (player != nullptr && !player->realize()) {
        throwMediaException(env, "Unable to realize WAV player");
    }
}

JNIEXPORT void JNICALL
Java_ru_woesss_j2me_mmapi_audio_WavPlayer_nativePrefetch(JNIEnv *env,
                                                          jclass /*clazz*/,
                                                          jlong handle) {
    auto *player = requirePlayer(env, handle);
    if (player == nullptr) {
        return;
    }
    const oboe::Result result = player->prefetch();
    if (result != oboe::Result::OK) {
        throwMediaException(env, oboe::convertToText(result));
    }
}

JNIEXPORT void JNICALL
Java_ru_woesss_j2me_mmapi_audio_WavPlayer_nativeStart(JNIEnv *env,
                                                       jclass /*clazz*/,
                                                       jlong handle) {
    auto *player = requirePlayer(env, handle);
    if (player == nullptr) {
        return;
    }
    const oboe::Result result = player->start();
    if (result != oboe::Result::OK) {
        throwMediaException(env, oboe::convertToText(result));
    }
}

JNIEXPORT void JNICALL
Java_ru_woesss_j2me_mmapi_audio_WavPlayer_nativePause(JNIEnv *env,
                                                       jclass /*clazz*/,
                                                       jlong handle) {
    auto *player = requirePlayer(env, handle);
    if (player == nullptr) {
        return;
    }
    const oboe::Result result = player->pause();
    if (result != oboe::Result::OK) {
        throwMediaException(env, oboe::convertToText(result));
    }
}

JNIEXPORT void JNICALL
Java_ru_woesss_j2me_mmapi_audio_WavPlayer_nativeDeallocate(JNIEnv *env,
                                                            jclass /*clazz*/,
                                                            jlong handle) {
    auto *player = requirePlayer(env, handle);
    if (player != nullptr) {
        player->deallocate();
    }
}

JNIEXPORT jlong JNICALL
Java_ru_woesss_j2me_mmapi_audio_WavPlayer_nativeSetMediaTime(JNIEnv *env,
                                                              jclass /*clazz*/,
                                                              jlong handle,
                                                              jlong time) {
    auto *player = requirePlayer(env, handle);
    return player == nullptr ? -1 : player->setMediaTime(time);
}

JNIEXPORT jlong JNICALL
Java_ru_woesss_j2me_mmapi_audio_WavPlayer_nativeGetMediaTime(JNIEnv *env,
                                                              jclass /*clazz*/,
                                                              jlong handle) {
    auto *player = requirePlayer(env, handle);
    return player == nullptr ? -1 : player->getMediaTime();
}

JNIEXPORT jlong JNICALL
Java_ru_woesss_j2me_mmapi_audio_WavPlayer_nativeGetDuration(JNIEnv *env,
                                                             jclass /*clazz*/,
                                                             jlong handle) {
    auto *player = requirePlayer(env, handle);
    return player == nullptr ? -1 : player->duration;
}

JNIEXPORT void JNICALL
Java_ru_woesss_j2me_mmapi_audio_WavPlayer_nativeSetRepeat(JNIEnv *env,
                                                           jclass /*clazz*/,
                                                           jlong handle,
                                                           jint count) {
    auto *player = requirePlayer(env, handle);
    if (player != nullptr) {
        player->setRepeat(count);
    }
}

JNIEXPORT void JNICALL
Java_ru_woesss_j2me_mmapi_audio_WavPlayer_nativeSetVolume(JNIEnv *env,
                                                           jclass /*clazz*/,
                                                           jlong handle,
                                                           jfloat left,
                                                           jfloat right) {
    auto *player = requirePlayer(env, handle);
    if (player != nullptr) {
        player->setVolume(left, right);
    }
}

JNIEXPORT void JNICALL
Java_ru_woesss_j2me_mmapi_audio_WavPlayer_nativeSetListener(JNIEnv *env,
                                                             jclass /*clazz*/,
                                                             jlong handle,
                                                             jobject listener) {
    auto *player = requirePlayer(env, handle);
    if (player != nullptr) {
        player->setListener(new mmapi::PlayerListener(env, listener));
    }
}

} // extern "C"
