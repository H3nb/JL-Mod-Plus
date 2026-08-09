/*
 * Copyright 2026 H3NB
 *
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

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <fstream>
#include <limits>
#include <string>
#include <vector>

#include "ma_player.h"
#include "smaf_file.h"

namespace {

constexpr uint32_t kSampleRate = 44100;
constexpr uint16_t kChannels = 2;
constexpr uint16_t kBitsPerSample = 16;
constexpr size_t kRenderFrames = 2048;
constexpr uint64_t kMaxInputBytes = 64ULL * 1024ULL * 1024ULL;
constexpr uint64_t kMaxOutputBytes = 128ULL * 1024ULL * 1024ULL;

void writeLe16(std::ostream& output, uint16_t value) {
    output.put(static_cast<char>(value & 0xff));
    output.put(static_cast<char>((value >> 8) & 0xff));
}

void writeLe32(std::ostream& output, uint32_t value) {
    output.put(static_cast<char>(value & 0xff));
    output.put(static_cast<char>((value >> 8) & 0xff));
    output.put(static_cast<char>((value >> 16) & 0xff));
    output.put(static_cast<char>((value >> 24) & 0xff));
}

void writeWavHeader(std::ostream& output, uint32_t dataBytes) {
    output.write("RIFF", 4);
    writeLe32(output, dataBytes + 36U);
    output.write("WAVE", 4);
    output.write("fmt ", 4);
    writeLe32(output, 16U);
    writeLe16(output, 1U);
    writeLe16(output, kChannels);
    writeLe32(output, kSampleRate);
    writeLe32(output, kSampleRate * kChannels * (kBitsPerSample / 8U));
    writeLe16(output, kChannels * (kBitsPerSample / 8U));
    writeLe16(output, kBitsPerSample);
    output.write("data", 4);
    writeLe32(output, dataBytes);
}

bool readFile(const char* path, std::vector<uint8_t>& bytes) {
    std::ifstream input(path, std::ios::binary | std::ios::ate);
    if (!input) return false;

    const std::streamoff size = input.tellg();
    if (size <= 0 || static_cast<uint64_t>(size) > kMaxInputBytes) return false;

    bytes.resize(static_cast<size_t>(size));
    input.seekg(0, std::ios::beg);
    const std::streamsize expected = static_cast<std::streamsize>(size);
    input.read(reinterpret_cast<char*>(bytes.data()), expected);
    return input.gcount() == expected && !input.bad();
}

bool renderToWav(const char* sourcePath, const char* targetPath) {
    std::vector<uint8_t> source;
    if (!readFile(sourcePath, source)) return false;

    fxchain::smaf::SmafFile file;
    if (!file.parse(source.data(), source.size())) return false;

    fxchain::smaf::MaPlayer player;
    if (!player.init(file, kSampleRate)) return false;

    std::ofstream output(targetPath, std::ios::binary | std::ios::trunc);
    if (!output) return false;

    writeWavHeader(output, 0U);
    if (!output) {
        output.close();
        std::remove(targetPath);
        return false;
    }

    std::vector<float> floatBuffer(kRenderFrames * kChannels);
    std::vector<int16_t> pcmBuffer(kRenderFrames * kChannels);
    uint64_t dataBytes = 0;

    while (true) {
        const int frames = player.render(floatBuffer.data(), static_cast<int>(kRenderFrames));
        if (frames < 0 || frames > static_cast<int>(kRenderFrames)) {
            output.close();
            std::remove(targetPath);
            return false;
        }
        if (frames == 0) break;

        const size_t samples = static_cast<size_t>(frames) * kChannels;
        const uint64_t nextBytes = dataBytes + samples * sizeof(int16_t);
        if (nextBytes > kMaxOutputBytes || nextBytes > std::numeric_limits<uint32_t>::max() - 36ULL) {
            output.close();
            std::remove(targetPath);
            return false;
        }

        for (size_t i = 0; i < samples; ++i) {
            float sample = floatBuffer[i];
            if (!std::isfinite(sample)) {
                output.close();
                std::remove(targetPath);
                return false;
            }
            sample = std::clamp(sample, -1.0f, 1.0f);
            const int value = static_cast<int>(std::lrint(sample * 32767.0f));
            pcmBuffer[i] = static_cast<int16_t>(value);
        }

        for (size_t i = 0; i < samples; ++i) {
            const uint16_t sample = static_cast<uint16_t>(pcmBuffer[i]);
            writeLe16(output, sample);
        }
        if (!output) {
            output.close();
            std::remove(targetPath);
            return false;
        }
        dataBytes = nextBytes;
    }

    output.seekp(0, std::ios::beg);
    writeWavHeader(output, static_cast<uint32_t>(dataBytes));
    output.flush();
    const bool ok = output.good() && dataBytes > 0;
    output.close();
    if (!ok) std::remove(targetPath);
    return ok;
}

class UtfChars final {
public:
    UtfChars(JNIEnv* env, jstring value) : env_(env), value_(value), chars_(nullptr) {
        if (value_) chars_ = env_->GetStringUTFChars(value_, nullptr);
    }

    ~UtfChars() {
        if (chars_) env_->ReleaseStringUTFChars(value_, chars_);
    }

    const char* get() const { return chars_; }

private:
    JNIEnv* env_;
    jstring value_;
    const char* chars_;
};

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_ru_woesss_j2me_mmapi_audio_SmafNativeRenderer_nativeRenderToWav(
        JNIEnv* env, jclass, jstring sourcePath, jstring targetPath) {
    if (!sourcePath || !targetPath) return JNI_FALSE;

    UtfChars source(env, sourcePath);
    UtfChars target(env, targetPath);
    if (!source.get() || !target.get()) return JNI_FALSE;

    return renderToWav(source.get(), target.get()) ? JNI_TRUE : JNI_FALSE;
}
