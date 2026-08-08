//
// Created by woesss on 09.07.2023.
//

#include <cstdio>
#include "eas_util.h"
#include "libsonivox/eas_types.h"

namespace mmapi {
    namespace eas {

        const char *EAS_GetErrorString(int32_t errorCode) {
            if (errorCode <= EAS_SUCCESS) {
                const int64_t idx = -static_cast<int64_t>(errorCode);
                const auto count = static_cast<int64_t>(sizeof(EAS_ERRORS) / sizeof(EAS_ERRORS[0]));
                if (idx >= 0 && idx < count) {
                    return EAS_ERRORS[idx];
                }
            }

            if (errorCode == EAS_EOF) {
                return "EAS_EOF";
            }
            if (errorCode == EAS_STREAM_BUFFERING) {
                return "EAS_STREAM_BUFFERING";
            }
            if (errorCode == EAS_BUFFER_FULL) {
                return "EAS_BUFFER_FULL";
            }

            thread_local char str[24];
            snprintf(str, sizeof(str), "%d", errorCode);
            return str;
        }

        const char *EAS_GetFileTypeString(int32_t type) {
            const auto count = static_cast<int32_t>(sizeof(EAS_FILE_TYPES) / sizeof(EAS_FILE_TYPES[0]));
            if (type >= 0 && type < count) {
                return EAS_FILE_TYPES[type];
            }
            return EAS_FILE_TYPES[0];
        }
    } // namespace eas
} // namespace mmapi
