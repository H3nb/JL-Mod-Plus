// SPDX-License-Identifier: Apache-2.0
#include "eas_file.h"
#include <cstdio>
#include <cstring>
#include <stdexcept>
#include <vector>
using Bytes = std::vector<uint8_t>;
using mmapi::eas::MemoryFile;
static void require(bool value, const char *message) {
    if (!value) throw std::runtime_error(message);
}
static void little32(Bytes &bytes, size_t offset, uint32_t value) {
    for (int i = 0; i < 4; ++i) bytes[offset + i] = value >> (i * 8);
}
static Bytes chunk(const char *name, const Bytes &payload) {
    Bytes result(8); std::memcpy(result.data(), name, 4);
    little32(result, 4, static_cast<uint32_t>(payload.size()));
    result.insert(result.end(), payload.begin(), payload.end());
    if (payload.size() & 1) result.push_back(0);
    return result;
}
static Bytes container(const std::vector<Bytes> &children, const char *type = "RMID") {
    Bytes result(type, type + 4);
    for (const auto &child : children) result.insert(result.end(), child.begin(), child.end());
    return chunk("RIFF", result);
}
static void rejected(const Bytes &bytes, const char *message = "Invalid RIFF/RMID") {
    try { MemoryFile::synthesisMedia(bytes); }
    catch (const std::runtime_error &error) {
        require(std::strstr(error.what(), message), "RMID rejection lost diagnostic"); return;
    }
    throw std::runtime_error("Corrupt or unsupported RMID accepted");
}
int main() {
    try {
        const Bytes smf = {'M','T','h','d',0,0,0,6,0,0,0,1,1,0xe0,
            'M','T','r','k',0,0,0,4,0,0xff,0x2f,0};
        const auto data = chunk("data", smf);
        Bytes info = {'I','N','F','O'};
        auto name = chunk("INAM", {'o','d','d'});
        info.insert(info.end(), name.begin(), name.end());
        auto valid = container({chunk("JUNK", {1,2,3}), chunk("LIST", info), data,
            chunk("tail", {4})});
        require(MemoryFile::synthesisMedia(valid) == smf, "Wrapped SMF altered");
        require(MemoryFile::synthesisMedia(smf) == smf, "Raw SMF altered");
        auto wave = container({chunk("data", {1,2})}, "WAVE");
        require(MemoryFile::synthesisMedia(wave) == wave, "WAVE normalized into synthesis");
        rejected(container({data, data}));
        rejected(container({chunk("JUNK", {1,2})}));
        rejected(container({chunk("data", Bytes(14))}));
        auto truncated = valid; truncated.pop_back(); rejected(truncated);
        auto overflow = valid; little32(overflow, 16, 0xffffffff); rejected(overflow);
        auto rootOverflow = valid; little32(rootOverflow, 4, 0xffffffff); rejected(rootOverflow);
        auto trailing = valid; trailing.push_back(0); rejected(trailing);
        auto oversized = valid; oversized.resize(16 * 1024 * 1024 + 1);
        little32(oversized, 4, static_cast<uint32_t>(oversized.size() - 8)); rejected(oversized);
        auto padding = container({data, chunk("JUNK", {1})});
        padding.pop_back(); little32(padding, 4, static_cast<uint32_t>(padding.size() - 8));
        rejected(padding);
        auto badInfo = container({data, chunk("LIST", {'I','N','F','O',0})}); rejected(badInfo);
        auto dls = container({}, "DLS ");
        rejected(container({data, dls}), "embedded DLS");
        Bytes nested = {'I','N','F','O'};
        nested.insert(nested.end(), dls.begin(), dls.end());
        rejected(container({data, chunk("LIST", nested)}), "embedded DLS");
        auto excessive = chunk("JUNK", {});
        for (int i = 0; i < 32; ++i) excessive = container({excessive}, "TEST");
        rejected(container({data, excessive}));
        // All proper prefixes are rejected once the complete recognition header exists.
        for (size_t length = 12; length < valid.size(); ++length)
            rejected(Bytes(valid.begin(), valid.begin() + length));
        auto oddSmf = smf; oddSmf.push_back(0);
        require(MemoryFile::synthesisMedia(container({chunk("data", oddSmf)})) == oddSmf,
            "Odd SMF data padding leaked into parser");
        std::puts("PASS RMID bounded extraction, padding, nested chunks, unsupported DLS, raw SMF/WAVE");
        return 0;
    } catch (const std::exception &error) { std::fprintf(stderr, "%s\n", error.what()); return 1; }
}
