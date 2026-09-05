/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <jni.h>

// Keep the large proven engine translation unit unchanged. This private JNI shim lets the Java
// wrapper classify the existing bounded-edit summary before the result reaches Binder/UI.
extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_edit(
        JNIEnv *env, jclass clazz, jlongArray candidateIds, jstring replacementValue);

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_editUnchecked(
        JNIEnv *env, jclass clazz, jlongArray candidateIds, jstring replacementValue) {
    return Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_edit(
            env, clazz, candidateIds, replacementValue);
}
