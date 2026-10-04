// SPDX-License-Identifier: Apache-2.0
#ifndef MMAPI_LEGACY_SMAF_H
#define MMAPI_LEGACY_SMAF_H
#include <string>
namespace mmapi::pcm {
// The caller owns an empty temporary output and publishes it only on success.
// Non-waveform/non-ADPCM input is left to the existing SMAF playback route.
bool convertLegacySmaf(const std::string &input, const std::string &output);
}
#endif
