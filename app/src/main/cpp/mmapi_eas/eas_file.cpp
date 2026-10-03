// SPDX-License-Identifier: Apache-2.0
#include "eas_file.h"
#include <algorithm>
#include <array>
#include <cstdio>
#include <cstring>
#include <memory>
#include <stdexcept>

namespace mmapi::eas {
namespace {
bool tag(const std::vector<uint8_t> &data, size_t offset, const char *value) {
    return offset <= data.size() && data.size() - offset >= 4
        && std::memcmp(data.data() + offset, value, 4) == 0;
}
uint32_t little32(const std::vector<uint8_t> &data, size_t offset) {
    return uint32_t(data[offset]) | (uint32_t(data[offset + 1]) << 8)
        | (uint32_t(data[offset + 2]) << 16) | (uint32_t(data[offset + 3]) << 24);
}
[[noreturn]] void corruptRmid() { throw std::runtime_error("Invalid RIFF/RMID container"); }
}
MemoryFile::MemoryFile(std::vector<uint8_t> data) : bytes(std::move(data)) {}
int MemoryFile::size(void *handle) {
    return static_cast<int>(static_cast<MemoryFile *>(handle)->bytes.size());
}
int MemoryFile::readAt(void *handle, void *buffer, int offset, int length) {
    const auto &data = static_cast<MemoryFile *>(handle)->bytes;
    if (offset < 0 || length < 0 || static_cast<size_t>(offset) > data.size()) return -1;
    size_t count = std::min(static_cast<size_t>(length), data.size() - offset);
    if (count) std::memcpy(buffer, data.data() + offset, count);
    return static_cast<int>(count);
}
std::vector<uint8_t> MemoryFile::readFile(const std::string &path, size_t limit) {
    std::unique_ptr<FILE, decltype(&fclose)> file(fopen(path.c_str(), "rb"), fclose);
    if (!file) throw std::runtime_error("Unable to open synthesis file");
    if (fseek(file.get(), 0, SEEK_END) != 0) throw std::runtime_error("Unable to seek synthesis file");
    long length = ftell(file.get());
    if (length < 0 || static_cast<size_t>(length) > limit)
        throw std::runtime_error("Synthesis file exceeds configured size limit");
    if (fseek(file.get(), 0, SEEK_SET) != 0) throw std::runtime_error("Unable to rewind synthesis file");
    std::vector<uint8_t> data(static_cast<size_t>(length));
    if (!data.empty() && fread(data.data(), 1, data.size(), file.get()) != data.size())
        throw std::runtime_error("Unable to read synthesis file");
    return data;
}
std::vector<uint8_t> MemoryFile::synthesisMedia(std::vector<uint8_t> data) {
    if (data.size() < 12 || !tag(data, 0, "RIFF") || !tag(data, 8, "RMID")) return data;
    // Size is checked without addition, including on 32-bit shipped ABIs.
    if (data.size() > 16 * 1024 * 1024 || little32(data, 4) != data.size() - 8) corruptRmid();
    struct Children { size_t cursor; size_t end; };
    // Nested RIFF/LIST chunks are inspected for embedded banks as well as bounds.
    // Reject pathological nesting rather than using an unbounded call stack.
    std::array<Children, 32> levels{};
    size_t depth = 1, smfOffset = 0, smfSize = 0;
    levels[0] = {12, data.size()};
    while (depth) {
        auto &level = levels[depth - 1];
        if (level.cursor == level.end) { --depth; continue; }
        if (level.end - level.cursor < 8) corruptRmid();
        const size_t chunk = level.cursor, payload = chunk + 8;
        const size_t length = little32(data, chunk + 4);
        if (length > level.end - payload) corruptRmid();
        const size_t end = payload + length;
        if ((length & 1) && end == level.end) corruptRmid();
        level.cursor = end + (length & 1);
        if (depth == 1 && tag(data, chunk, "data")) {
            if (smfOffset || length < 14 || !tag(data, payload, "MThd")) corruptRmid();
            smfOffset = payload; smfSize = length;
        } else if (tag(data, chunk, "RIFF") || tag(data, chunk, "LIST")) {
            if (length < 4) corruptRmid();
            if (tag(data, payload, "DLS "))
                throw std::runtime_error("RIFF/RMID embedded DLS banks are unsupported");
            if (depth == levels.size()) corruptRmid();
            levels[depth++] = {payload + 4, end};
        }
    }
    if (!smfOffset) corruptRmid();
    // Reuse the owned snapshot, with no second allocation or borrowed locator.
    data.erase(data.begin(), data.begin() + smfOffset);
    data.resize(smfSize);
    return data;
}
}
