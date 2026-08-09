//
// Copyright 2026 H3NB
// SPDX-License-Identifier: Apache-2.0
//

#include "gsm610_decoder.h"

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <limits>
#include <vector>

extern "C" {
#include "gsm.h"
}

namespace mmapi {
namespace wav {
namespace {

constexpr uint16_t kGsm610Format = 0x0031;
constexpr uint16_t kChannels = 1;
constexpr uint32_t kSampleRate = 8000;
constexpr uint16_t kBlockAlign = 65;
constexpr uint16_t kSamplesPerBlock = 320;
constexpr uint16_t kPcmBitsPerSample = 16;
constexpr uint64_t kMaxPcmBytes = 64ULL * 1024ULL * 1024ULL;

uint16_t readLe16(const uint8_t *data) {
    return static_cast<uint16_t>(data[0])
            | static_cast<uint16_t>(data[1]) << 8;
}

uint32_t readLe32(const uint8_t *data) {
    return static_cast<uint32_t>(data[0])
            | static_cast<uint32_t>(data[1]) << 8
            | static_cast<uint32_t>(data[2]) << 16
            | static_cast<uint32_t>(data[3]) << 24;
}

bool readExact(std::ifstream &input, void *data, std::streamsize size) {
    input.read(static_cast<char *>(data), size);
    return input.gcount() == size && !input.bad();
}

void appendLe16(std::vector<uint8_t> &output, uint16_t value) {
    output.push_back(static_cast<uint8_t>(value & 0xff));
    output.push_back(static_cast<uint8_t>((value >> 8) & 0xff));
}

void appendLe32(std::vector<uint8_t> &output, uint32_t value) {
    output.push_back(static_cast<uint8_t>(value & 0xff));
    output.push_back(static_cast<uint8_t>((value >> 8) & 0xff));
    output.push_back(static_cast<uint8_t>((value >> 16) & 0xff));
    output.push_back(static_cast<uint8_t>((value >> 24) & 0xff));
}

void appendFourCc(std::vector<uint8_t> &output, const char *value) {
    output.insert(output.end(), value, value + 4);
}

void writePcmHeader(std::vector<uint8_t> &output, uint32_t dataBytes) {
    output.clear();
    output.reserve(44ULL + dataBytes);
    appendFourCc(output, "RIFF");
    appendLe32(output, dataBytes + 36U);
    appendFourCc(output, "WAVE");
    appendFourCc(output, "fmt ");
    appendLe32(output, 16U);
    appendLe16(output, 1U);
    appendLe16(output, kChannels);
    appendLe32(output, kSampleRate);
    appendLe32(output, kSampleRate * kChannels * (kPcmBitsPerSample / 8U));
    appendLe16(output, kChannels * (kPcmBitsPerSample / 8U));
    appendLe16(output, kPcmBitsPerSample);
    appendFourCc(output, "data");
    appendLe32(output, dataBytes);
}

class GsmHandle final {
public:
    GsmHandle() : value(gsm_create()) {
    }

    ~GsmHandle() {
        if (value != nullptr) {
            gsm_destroy(value);
        }
    }

    gsm get() const {
        return value;
    }

private:
    gsm value;
};

} // namespace

Gsm610DecodeResult decodeGsm610WaveFile(const char *path,
                                        std::vector<uint8_t> &pcmWave) {
    pcmWave.clear();
    if (path == nullptr) {
        return Gsm610DecodeResult::NotGsm;
    }

    std::ifstream input(path, std::ios::binary);
    if (!input) {
        return Gsm610DecodeResult::NotGsm;
    }

    input.seekg(0, std::ios::end);
    const std::streamoff fileSize = input.tellg();
    if (fileSize < 12) {
        return Gsm610DecodeResult::NotGsm;
    }
    input.seekg(0, std::ios::beg);

    uint8_t riff[12];
    if (!readExact(input, riff, sizeof(riff))
            || std::memcmp(riff, "RIFF", 4) != 0
            || std::memcmp(riff + 8, "WAVE", 4) != 0) {
        return Gsm610DecodeResult::NotGsm;
    }

    const uint64_t declaredEnd = static_cast<uint64_t>(readLe32(riff + 4)) + 8ULL;
    if (declaredEnd < 12 || declaredEnd > static_cast<uint64_t>(fileSize)) {
        return Gsm610DecodeResult::NotGsm;
    }

    bool sawFmt = false;
    bool isGsm = false;
    uint16_t channels = 0;
    uint32_t sampleRate = 0;
    uint16_t blockAlign = 0;
    uint16_t bitsPerSample = 0;
    uint16_t extraSize = 0;
    uint16_t samplesPerBlock = 0;
    uint64_t fmtChunkSize = 0;
    uint64_t dataOffset = 0;
    uint64_t dataSize = 0;
    int dataChunkCount = 0;
    uint32_t factFrames = 0;

    uint64_t position = 12;
    while (position + 8 <= declaredEnd) {
        input.seekg(static_cast<std::streamoff>(position), std::ios::beg);
        uint8_t header[8];
        if (!readExact(input, header, sizeof(header))) {
            return isGsm ? Gsm610DecodeResult::Invalid : Gsm610DecodeResult::NotGsm;
        }

        const uint64_t chunkSize = readLe32(header + 4);
        const uint64_t dataStart = position + 8;
        const uint64_t dataEnd = dataStart + chunkSize;
        const uint64_t paddedEnd = dataEnd + (chunkSize & 1ULL);
        if (dataEnd < dataStart || paddedEnd < dataEnd || paddedEnd > declaredEnd) {
            return isGsm ? Gsm610DecodeResult::Invalid : Gsm610DecodeResult::NotGsm;
        }

        if (std::memcmp(header, "fmt ", 4) == 0) {
            if (sawFmt) {
                return isGsm ? Gsm610DecodeResult::Invalid : Gsm610DecodeResult::NotGsm;
            }
            if (chunkSize < 16) {
                return Gsm610DecodeResult::NotGsm;
            }
            uint8_t format[20] = {};
            const std::streamsize bytesToRead = static_cast<std::streamsize>(
                    std::min<uint64_t>(chunkSize, sizeof(format)));
            if (!readExact(input, format, bytesToRead)) {
                return Gsm610DecodeResult::NotGsm;
            }

            sawFmt = true;
            isGsm = readLe16(format) == kGsm610Format;
            if (isGsm) {
                if (chunkSize < 20) {
                    return Gsm610DecodeResult::Invalid;
                }
                fmtChunkSize = chunkSize;
                channels = readLe16(format + 2);
                sampleRate = readLe32(format + 4);
                blockAlign = readLe16(format + 12);
                bitsPerSample = readLe16(format + 14);
                extraSize = readLe16(format + 16);
                samplesPerBlock = readLe16(format + 18);
            }
        } else if (std::memcmp(header, "fact", 4) == 0 && chunkSize >= 4) {
            uint8_t fact[4];
            if (!readExact(input, fact, sizeof(fact))) {
                return isGsm ? Gsm610DecodeResult::Invalid : Gsm610DecodeResult::NotGsm;
            }
            factFrames = readLe32(fact);
        } else if (std::memcmp(header, "data", 4) == 0) {
            ++dataChunkCount;
            if (dataChunkCount == 1) {
                dataOffset = dataStart;
                dataSize = chunkSize;
            }
        }

        position = paddedEnd;
    }

    if (!sawFmt || !isGsm) {
        return Gsm610DecodeResult::NotGsm;
    }

    if (channels != kChannels
            || sampleRate != kSampleRate
            || blockAlign != kBlockAlign
            || bitsPerSample != 0
            || extraSize < 2
            || 18ULL + extraSize > fmtChunkSize
            || samplesPerBlock != kSamplesPerBlock
            || dataChunkCount != 1
            || dataSize == 0
            || dataSize % kBlockAlign != 0) {
        return Gsm610DecodeResult::Invalid;
    }

    const uint64_t blockCount = dataSize / kBlockAlign;
    if (blockCount > std::numeric_limits<uint64_t>::max() / kSamplesPerBlock) {
        return Gsm610DecodeResult::Invalid;
    }
    const uint64_t capacityFrames = blockCount * kSamplesPerBlock;
    const uint64_t outputFrames = factFrames != 0 ? factFrames : capacityFrames;
    if (outputFrames == 0
            || outputFrames > capacityFrames
            || outputFrames > kMaxPcmBytes / sizeof(int16_t)) {
        return Gsm610DecodeResult::Invalid;
    }

    const uint64_t dataBytes = outputFrames * sizeof(int16_t);
    if (dataBytes > std::numeric_limits<uint32_t>::max() - 36ULL) {
        return Gsm610DecodeResult::Invalid;
    }

    GsmHandle state;
    if (state.get() == nullptr) {
        return Gsm610DecodeResult::Invalid;
    }
    int wav49 = 1;
    if (gsm_option(state.get(), GSM_OPT_WAV49, &wav49) < 0) {
        return Gsm610DecodeResult::Invalid;
    }

    writePcmHeader(pcmWave, static_cast<uint32_t>(dataBytes));
    input.seekg(static_cast<std::streamoff>(dataOffset), std::ios::beg);

    uint64_t remainingFrames = outputFrames;
    uint8_t block[kBlockAlign];
    gsm_signal decoded[kSamplesPerBlock];
    for (uint64_t blockIndex = 0;
            blockIndex < blockCount && remainingFrames > 0;
            ++blockIndex) {
        if (!readExact(input, block, kBlockAlign)
                || gsm_decode(state.get(), block, decoded) != 0
                || gsm_decode(state.get(), block + 33, decoded + 160) != 0) {
            pcmWave.clear();
            return Gsm610DecodeResult::Invalid;
        }

        const uint64_t framesToWrite = std::min<uint64_t>(remainingFrames,
                                                           kSamplesPerBlock);
        for (uint64_t i = 0; i < framesToWrite; ++i) {
            appendLe16(pcmWave, static_cast<uint16_t>(decoded[i]));
        }
        remainingFrames -= framesToWrite;
    }

    if (remainingFrames != 0 || pcmWave.size() != 44ULL + dataBytes) {
        pcmWave.clear();
        return Gsm610DecodeResult::Invalid;
    }

    return Gsm610DecodeResult::Decoded;
}

} // namespace wav
} // namespace mmapi
