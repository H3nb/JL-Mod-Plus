// SPDX-License-Identifier: Apache-2.0
#ifndef MMAPI_EAS_FILE_H
#define MMAPI_EAS_FILE_H
#include "eas_types.h"
#include <cstdint>
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
}
#endif
