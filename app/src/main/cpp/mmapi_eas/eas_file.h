// SPDX-License-Identifier: Apache-2.0
#ifndef MMAPI_EAS_FILE_H
#define MMAPI_EAS_FILE_H
#include "eas_types.h"
#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

namespace mmapi::eas {
// Stable address: EAS stores this locator for all parser track cursors.
class MemoryFile final {
    std::vector<uint8_t> bytes;
    static int readAt(void *handle, void *buffer, int offset, int length);
    static int size(void *handle);
public:
    explicit MemoryFile(std::vector<uint8_t> data);
    EAS_FILE locator{this, readAt, size};
    static std::vector<uint8_t> readFile(const std::string &path, size_t limit);
    // Validate an RMID container and expose its sole SMF to the existing parser.
    // Other synthesis formats keep their original bytes.
    static std::vector<uint8_t> synthesisMedia(std::vector<uint8_t> data);
};
// The Sonivox parser needs random access while loading a bank, not a full
// in-memory copy. The locator is used synchronously and never escapes parsing.
class DiskFile final {
    FILE *file = nullptr;
    int length = 0;
    static int readAt(void *handle, void *buffer, int offset, int count);
    static int size(void *handle);
public:
    explicit DiskFile(const std::string &path);
    ~DiskFile();
    DiskFile(const DiskFile &) = delete;
    DiskFile &operator=(const DiskFile &) = delete;
    EAS_FILE locator{this, readAt, size};
};
}
#endif
