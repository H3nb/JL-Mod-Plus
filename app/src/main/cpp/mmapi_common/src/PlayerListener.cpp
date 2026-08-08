//
// Created by woesss on 13.08.2023.
//

#include "PlayerListener.h"
#include "log.h"

#define LOG_TAG "MMAPI"

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    JNIEnv* env = nullptr;
    if (vm == nullptr
            || vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    mmapi::JNIEnvPtr::vm = vm;
    return JNI_VERSION_1_6;
}

namespace mmapi {
    PlayerListener::PlayerListener(JNIEnv *env, jobject pListener) {
        if (env == nullptr || pListener == nullptr) {
            return;
        }
        listener = env->NewGlobalRef(pListener);
        if (listener == nullptr || env->ExceptionCheck()) {
            env->ExceptionClear();
            listener = nullptr;
            return;
        }
        jclass clazz = env->GetObjectClass(listener);
        if (clazz == nullptr || env->ExceptionCheck()) {
            env->ExceptionClear();
            return;
        }
        method = env->GetMethodID(clazz, "postEvent", "(IJ)V");
        env->DeleteLocalRef(clazz);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            method = nullptr;
        }
    }

    PlayerListener::~PlayerListener() {
        if (listener == nullptr) {
            return;
        }
        JNIEnvPtr env;
        if (!env) {
            ALOGE("%s: unable to acquire JNIEnv for global-ref cleanup", __func__);
            return;
        }
        env->DeleteGlobalRef(listener);
        listener = nullptr;
    }

    void PlayerListener::sendEvent(PlayerListenerEvent eventType, const int64_t time) {
        if (listener == nullptr || method == nullptr) {
            ALOGE("%s: obj=%p, mID=%p", __func__, listener, method);
            return;
        }
        JNIEnvPtr env;
        if (!env) {
            ALOGE("%s: unable to acquire JNIEnv", __func__);
            return;
        }
        env->CallVoidMethod(listener, method, eventType, time);
        if (env->ExceptionCheck()) {
            // The Java entry point is intentionally queue-only and must return
            // promptly to the Oboe callback. Do not leave a pending exception
            // attached to a realtime/native audio thread.
            ALOGE("%s: Java player event enqueue failed", __func__);
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
    }

    void PlayerListener::postEvent(PlayerListenerEvent type, int64_t time) {
        /*
         * Preserve the order in which the native backend generates events.
         * Java's postEvent(int,long) only enqueues work onto that Player's
         * single callback executor, so this direct JNI handoff neither invokes
         * MIDlet listeners nor closes audio resources on the realtime thread.
         *
         * The old detached-thread-per-event implementation could reorder events
         * and could outlive this PlayerListener, creating a use-after-free race.
         */
        sendEvent(type, time);
    }

    JavaVM *JNIEnvPtr::vm = nullptr;

    JNIEnvPtr::JNIEnvPtr() {
        if (vm == nullptr) {
            ALOGE("%s: JavaVM is not initialized", __func__);
            return;
        }

        jint res = vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
        if (res == JNI_OK) {
            return;
        }
        if (res != JNI_EDETACHED) {
            env = nullptr;
            ALOGE("%s: JavaVM::GetEnv() returned %d", __func__, res);
            return;
        }

        res = vm->AttachCurrentThread(&env, nullptr);
        if (res == JNI_OK) {
            detachOnDestroy = true;
        } else {
            env = nullptr;
            ALOGE("%s: JavaVM::AttachCurrentThread() returned %d", __func__, res);
        }
    }

    JNIEnvPtr::~JNIEnvPtr() {
        if (!detachOnDestroy || vm == nullptr) {
            return;
        }
        jint res = vm->DetachCurrentThread();
        if (res != JNI_OK) {
            ALOGE("%s: JavaVM::DetachCurrentThread() returned %d", __func__, res);
        }
    }

    JNIEnv *JNIEnvPtr::operator->() const {
        return env;
    }

    JNIEnvPtr::operator bool() const {
        return env != nullptr;
    }
} // namespace mmapi
