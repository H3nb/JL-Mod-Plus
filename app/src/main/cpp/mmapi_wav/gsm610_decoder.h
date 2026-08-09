//
// Copyright 2026 H3NB
// SPDX-License-Identifier: Apache-2.0
//

#ifndef MMAPI_GSM610_DECODER_H
#define MMAPI_GSM610_DECODER_H

#include <cstdint>
#include <vector>

namespace mmapi {
namespace wav {

enum class Gsm610DecodeResult {
    NotGsm,
    Decoded,
    Invalid
};

Gsm610DecodeResult decodeGsm610WaveFile(const char *path,
                                        std::vector<uint8_t> &pcmWave);

} // namespace wav
} // namespace mmapi

#endif // MMAPI_GSM610_DECODER_H
