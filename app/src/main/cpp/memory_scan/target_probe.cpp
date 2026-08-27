#include <jni.h>
#include <unistd.h>

#include <cstdint>

namespace {

volatile uint64_t g_remote_probe = 0x4A4C4D50524F4245ULL;  // "JLMPROBE"

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeTargetProbe_probeAddress(JNIEnv *, jclass) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(&g_remote_probe));
}

extern "C" JNIEXPORT jlong JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeTargetProbe_probeValue(JNIEnv *, jclass) {
    return static_cast<jlong>(g_remote_probe);
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeTargetProbe_pageSize(JNIEnv *, jclass) {
    const long value = sysconf(_SC_PAGESIZE);
    return value > 0 ? static_cast<jint>(value) : 4096;
}
