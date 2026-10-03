// SPDX-License-Identifier: Apache-2.0
#include "eas_player.h"
#include <jni.h>
#include <limits>
#include <unordered_map>
using mmapi::eas::Player;
using mmapi::eas::Event;
using mmapi::eas::Engine;
namespace {
std::mutex registryLock;
std::unordered_map<jlong, std::shared_ptr<Player>> registry;
jlong nextHandle = 1;
size_t creating = 0;
struct Session { std::weak_ptr<Engine> engine; int64_t epoch = 0, minimum = 0; bool allowed = false; };
std::unordered_map<jlong, Session> sessions;
constexpr size_t MAX_PLAYERS = 16;
std::shared_ptr<Player> player(jlong handle) {
    std::lock_guard<std::mutex> lock(registryLock);
    auto found = registry.find(handle);
    if (found == registry.end()) throw std::invalid_argument("Invalid or closed synthesis handle");
    return found->second;
}
void exception(JNIEnv *env, const char *type, const char *message) {
    if (env->ExceptionCheck()) return;
    jclass klass = env->FindClass(type);
    if (klass) { env->ThrowNew(klass, message); env->DeleteLocalRef(klass); }
}
void translate(JNIEnv *env) {
    try { throw; }
    catch (const std::bad_alloc &) { exception(env, "java/lang/OutOfMemoryError", "Sonivox allocation failed"); }
    catch (const std::invalid_argument &error) { exception(env, "java/lang/IllegalArgumentException", error.what()); }
    catch (const std::exception &error) { exception(env, "javax/microedition/media/MediaException", error.what()); }
    catch (...) { exception(env, "javax/microedition/media/MediaException", "Unexpected synthesis failure"); }
}
std::string string(JNIEnv *env, jstring value, bool nullable = false) {
    if (!value) {
        if (nullable) return {};
        throw std::invalid_argument("Null synthesis locator");
    }
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) throw std::bad_alloc();
    std::string result;
    try { result.assign(chars); }
    catch (...) { env->ReleaseStringUTFChars(value, chars); throw; }
    env->ReleaseStringUTFChars(value, chars);
    return result;
}
std::vector<uint8_t> bytes(JNIEnv *env, jbyteArray value, jint offset, jint count, size_t limit) {
    if (!value) throw std::invalid_argument("Null synthesis data");
    jsize length = env->GetArrayLength(value);
    if (offset < 0 || count < 0 || offset > length || count > length - offset)
        throw std::invalid_argument("Synthesis data range is out of bounds");
    if (static_cast<size_t>(count) > limit) throw std::runtime_error("Synthesis data exceeds configured size limit");
    std::vector<uint8_t> result(count);
    if (count) env->GetByteArrayRegion(value, offset, count, reinterpret_cast<jbyte *>(result.data()));
    if (env->ExceptionCheck()) throw std::runtime_error("Unable to copy synthesis data");
    return result;
}
void dispose(jlong handle) {
    std::shared_ptr<Player> owned;
    {
        std::lock_guard<std::mutex> lock(registryLock);
        auto found = registry.find(handle);
        if (found == registry.end()) return;
        owned = std::move(found->second); registry.erase(found);
    }
    owned->shutdown();
}
}
#define JNI_NAME(method) Java_io_github_h3nb_jlmodplus_mmapi_synth_eas_LibEAS_##method
#define VOID_METHOD(method, statement) \
extern "C" JNIEXPORT void JNICALL JNI_NAME(method)(JNIEnv *env, jobject, jlong handle) { \
    try { statement; } catch (...) { translate(env); } \
}
extern "C" JNIEXPORT void JNICALL JNI_NAME(setPolicy)(JNIEnv *, jclass, jlong id, jlong epoch,
        jlong minimum, jboolean allowed) {
    std::lock_guard<std::mutex> lock(registryLock);
    auto &session = sessions[id];
    if (epoch < session.epoch) return;
    session.epoch = epoch; session.minimum = minimum; session.allowed = allowed;
    if (auto engine = session.engine.lock()) engine->policy(minimum, allowed);
}
extern "C" JNIEXPORT void JNICALL JNI_NAME(closeSession)(JNIEnv *, jclass, jlong id) {
    std::lock_guard<std::mutex> lock(registryLock);
    auto found = sessions.find(id);
    if (found == sessions.end()) return;
    if (auto engine = found->second.engine.lock()) engine->policy(INT64_MAX, false);
    sessions.erase(found);
}
extern "C" JNIEXPORT jlong JNICALL JNI_NAME(createNative)(JNIEnv *env, jobject, jstring locator,
        jstring bank, jlong sessionId, jboolean sampled) {
    bool reserved = false;
    try {
        auto path = string(env, locator);
        auto font = string(env, bank, true);
        std::shared_ptr<Engine> engine;
        jlong handle;
        {
            std::lock_guard<std::mutex> lock(registryLock);
            if (registry.size() + creating >= MAX_PLAYERS) throw std::runtime_error("Audio context limit reached (16 Players)");
            if (nextHandle == std::numeric_limits<jlong>::max()) throw std::runtime_error("Audio handle space exhausted");
            auto &session = sessions.at(sessionId);
            engine = session.engine.lock();
            if (!engine) { engine = std::make_shared<Engine>(); session.engine = engine; }
            engine->policy(session.minimum, session.allowed);
            handle = nextHandle++; ++creating; reserved = true;
        }
        // Bank and source IO must not hold the handle registry against peer management.
        auto owned = std::make_shared<Player>(path, font, engine, sampled);
        {
            std::lock_guard<std::mutex> lock(registryLock);
            registry.emplace(handle, std::move(owned)); --creating; reserved = false;
        }
        return handle;
    } catch (...) {
        if (reserved) { std::lock_guard<std::mutex> lock(registryLock); --creating; }
        translate(env); return 0;
    }
}
extern "C" JNIEXPORT void JNICALL JNI_NAME(activateNative)(JNIEnv *env, jobject, jlong handle,
        jboolean midiOnly, jlong epoch) {
    try { auto owned = player(handle); if (midiOnly) owned->activateMidi(epoch); else owned->start(epoch); }
    catch (...) { translate(env); }
}
extern "C" JNIEXPORT jlong JNICALL JNI_NAME(getOutputIdentity)(JNIEnv *env, jobject, jlong handle) {
    try { return player(handle)->outputGroup(); } catch (...) { translate(env); return 0; }
}
extern "C" JNIEXPORT jboolean JNICALL JNI_NAME(outputFailed)(JNIEnv *env, jobject, jlong handle) {
    try { return player(handle)->outputFailed(); } catch (...) { translate(env); return false; }
}
extern "C" JNIEXPORT jlongArray JNICALL JNI_NAME(runtimeDiagnostics)(JNIEnv *env, jobject, jlong handle) {
    try {
        auto values = player(handle)->runtimeDiagnostics();
        jlong longs[12]; std::copy(values.begin(), values.end(), longs);
        auto result = env->NewLongArray(12);
        if (result) env->SetLongArrayRegion(result, 0, 12, longs);
        return result;
    } catch (...) { translate(env); return nullptr; }
}
extern "C" JNIEXPORT jobjectArray JNICALL JNI_NAME(metadataBytes)(JNIEnv *env, jobject, jlong handle) {
    try {
        auto tags = player(handle)->metadata();
        auto klass = env->FindClass("[B");
        if (!klass) return nullptr;
        auto result = env->NewObjectArray(static_cast<jsize>(tags.size()), klass, nullptr);
        env->DeleteLocalRef(klass);
        if (!result) return nullptr;
        for (size_t i = 0; i < tags.size(); ++i) {
            auto value = env->NewByteArray(static_cast<jsize>(tags[i].size()));
            if (!value) return result;
            env->SetByteArrayRegion(value, 0, static_cast<jsize>(tags[i].size()),
                reinterpret_cast<const jbyte *>(tags[i].data()));
            env->SetObjectArrayElement(result, static_cast<jsize>(i), value);
            env->DeleteLocalRef(value);
            if (env->ExceptionCheck()) return result;
        }
        return result;
    } catch (...) { translate(env); return nullptr; }
}
extern "C" JNIEXPORT jstring JNICALL JNI_NAME(contentType)(JNIEnv *env, jobject, jlong handle) {
    try { auto value = player(handle)->contentType(); return env->NewStringUTF(value.c_str()); }
    catch (...) { translate(env); return nullptr; }
}
extern "C" JNIEXPORT jlongArray JNICALL JNI_NAME(decoderDiagnostics)(JNIEnv *env, jobject, jlong handle) {
    try {
        auto values = player(handle)->decoderDiagnostics();
        jlong longs[4]; std::copy(values.begin(), values.end(), longs);
        auto result = env->NewLongArray(4);
        if (result) env->SetLongArrayRegion(result, 0, 4, longs);
        return result;
    } catch (...) { translate(env); return nullptr; }
}
extern "C" JNIEXPORT jboolean JNICALL JNI_NAME(isOutputSuspended)(JNIEnv *env, jobject, jlong handle) {
    try { return player(handle)->suspended(); } catch (...) { translate(env); return false; }
}
VOID_METHOD(close, dispose(handle))
VOID_METHOD(realize, (void)player(handle))
VOID_METHOD(prefetch, player(handle)->prefetch())
VOID_METHOD(start, player(handle)->start())
VOID_METHOD(activateMidi, player(handle)->activateMidi())
VOID_METHOD(pause, player(handle)->pause())
VOID_METHOD(suspendOutput, player(handle)->suspendOutput())
VOID_METHOD(resumeOutput, player(handle)->resumeOutput())
VOID_METHOD(deallocate, player(handle)->deallocate())
VOID_METHOD(recoverOutput, player(handle)->recoverOutput())
extern "C" JNIEXPORT jlong JNICALL JNI_NAME(setMediaTime)(JNIEnv *env, jobject, jlong handle, jlong time) {
    try { return player(handle)->seek(time); } catch (...) { translate(env); return -1; }
}
extern "C" JNIEXPORT jlong JNICALL JNI_NAME(getMediaTime)(JNIEnv *env, jobject, jlong handle) {
    try { return player(handle)->time(); } catch (...) { translate(env); return -1; }
}
extern "C" JNIEXPORT jlong JNICALL JNI_NAME(getDuration)(JNIEnv *env, jobject, jlong handle) {
    try { return player(handle)->length(); } catch (...) { translate(env); return -1; }
}
extern "C" JNIEXPORT jlong JNICALL JNI_NAME(getGeneration)(JNIEnv *env, jobject, jlong handle) {
    try { return player(handle)->epoch(); } catch (...) { translate(env); return -1; }
}
extern "C" JNIEXPORT void JNICALL JNI_NAME(setRepeat)(JNIEnv *env, jobject, jlong handle, jint count) {
    try { player(handle)->repeat(count); } catch (...) { translate(env); }
}
extern "C" JNIEXPORT void JNICALL JNI_NAME(setVolume)(JNIEnv *env, jobject, jlong handle, jfloat left, jfloat right) {
    try { player(handle)->gain(left, right); } catch (...) { translate(env); }
}
extern "C" JNIEXPORT void JNICALL JNI_NAME(setDataSource)(JNIEnv *env, jobject, jlong handle, jbyteArray data) {
    try {
        if (!data) throw std::invalid_argument("Null synthesis source");
        auto copy = bytes(env, data, 0, env->GetArrayLength(data), Player::MEDIA_LIMIT);
        player(handle)->data(std::move(copy));
    } catch (...) { translate(env); }
}
extern "C" JNIEXPORT jint JNICALL JNI_NAME(writeMIDI)(JNIEnv *env, jobject, jlong handle, jbyteArray data, jint offset, jint count) {
    try {
        auto owned = player(handle);
        if (!data) throw std::invalid_argument("Null MIDI data");
        jsize length = env->GetArrayLength(data);
        if (offset < 0 || count < 0 || offset > length || count > length - offset)
            throw std::invalid_argument("MIDI data range is out of bounds");
        if (count > 16384) return -1;
        auto copy = bytes(env, data, offset, count, 16384);
        return owned->writeMidi(copy.data(), count);
    } catch (...) { translate(env); return -1; }
}
extern "C" JNIEXPORT jlongArray JNICALL JNI_NAME(pollEvent)(JNIEnv *env, jobject, jlong handle) {
    try {
        Event event{};
        if (!player(handle)->poll(event)) return nullptr;
        jlong values[] = {event.type, event.time, event.generation, event.error};
        auto result = env->NewLongArray(4);
        if (result) env->SetLongArrayRegion(result, 0, 4, values);
        return result;
    } catch (...) { translate(env); return nullptr; }
}
extern "C" JNIEXPORT jlongArray JNICALL JNI_NAME(diagnostics)(JNIEnv *env, jobject, jlong handle) {
    try {
        auto values = player(handle)->diagnostics();
        jlong resultValues[9];
        std::copy(values.begin(), values.end(), resultValues);
        auto result = env->NewLongArray(9);
        if (result) env->SetLongArrayRegion(result, 0, 9, resultValues);
        return result;
    } catch (...) { translate(env); return nullptr; }
}
extern "C" JNIEXPORT jint JNICALL JNI_NAME(liveHandles)(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(registryLock);
    return static_cast<jint>(registry.size());
}
