// SPDX-License-Identifier: Apache-2.0
#include "eas_file.h"
#include <algorithm>
#include <cstdio>
#include <cstring>
#include <memory>
#include <stdexcept>

namespace mmapi::eas {
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
}
