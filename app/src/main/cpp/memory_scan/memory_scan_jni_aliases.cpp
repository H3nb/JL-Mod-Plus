#include <jni.h>

/*
 * Small JNI aliases let the Java facade add direct-first refine policy without touching the
 * established scanner implementation. The original exports keep all locking and scanner state.
 */
extern "C" jint Java_ru_playsoftware_j2meloader_memory_NativeMemoryAgent_nativeSearch(
        JNIEnv *, jclass, jstring, jint, jint);
extern "C" jint Java_ru_playsoftware_j2meloader_memory_NativeMemoryAgent_nativeRefine(
        JNIEnv *, jclass, jstring);
extern "C" jint Java_ru_playsoftware_j2meloader_memory_NativeMemoryAgent_nativeRefineRelocating(
        JNIEnv *, jclass, jstring);

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryAgent_nativeSearchRaw(
        JNIEnv *env, jclass clazz, jstring value, jint scope, jint value_type) {
    return Java_ru_playsoftware_j2meloader_memory_NativeMemoryAgent_nativeSearch(
            env, clazz, value, scope, value_type);
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryAgent_nativeRefineRaw(
        JNIEnv *env, jclass clazz, jstring value) {
    return Java_ru_playsoftware_j2meloader_memory_NativeMemoryAgent_nativeRefine(
            env, clazz, value);
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryAgent_nativeRefineRelocatingRaw(
        JNIEnv *env, jclass clazz, jstring value) {
    return Java_ru_playsoftware_j2meloader_memory_NativeMemoryAgent_nativeRefineRelocating(
            env, clazz, value);
}
