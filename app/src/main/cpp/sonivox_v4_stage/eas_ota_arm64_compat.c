/*
 * Copyright 2026 H3NB
 * SPDX-License-Identifier: Apache-2.0
 *
 * ARM64/LP64 compatibility bridge for SONiVOX v4.0.1 Nokia OTA ringtones.
 *
 * The parser algorithm remains the pinned upstream implementation. Only its
 * stale pre-v4 callback ABI is isolated below and adapted to the current
 * S_FILE_PARSER_INTERFACE. Do not add format-specific behavior here.
 */

#include <android/log.h>

#include "legacy_parser_abi.h"
#include "eas_data.h"
#include "eas_miditypes.h"
#include "eas_report.h"
#include "eas_host.h"
#include "eas_midi.h"
#include "eas_config.h"
#include "eas_vm_protos.h"
#include "eas_otadata.h"

/*
 * The pinned OTA source uses Android's historical ALOGD/ALOGV convenience
 * macros, but NDK 29's public logging headers do not provide them. Keep this
 * compatibility local to the translation unit instead of patching upstream.
 */
#ifndef ALOGD
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "Sonivox", __VA_ARGS__)
#endif
#ifndef ALOGV
#define ALOGV(...) __android_log_print(ANDROID_LOG_VERBOSE, "Sonivox", __VA_ARGS__)
#endif

#define S_FILE_PARSER_INTERFACE static JL_LEGACY_FILE_PARSER_INTERFACE
#define EAS_STATE EAS_I32
#define EAS_OTA_Parser JL_OTA_LegacyParser
#include "../sonivox_v4/arm-wt-22k/lib_src/eas_ota.c"
#undef EAS_OTA_Parser
#undef EAS_STATE
#undef S_FILE_PARSER_INTERFACE

static EAS_RESULT JL_OTA_State(S_EAS_DATA *pEASData,
                               EAS_VOID_PTR pInstData,
                               EAS_STATE *pState)
{
    EAS_I32 legacyState = EAS_STATE_ERROR;
    EAS_RESULT result;

    if (pState == NULL)
        return EAS_ERROR_INVALID_PARAMETER;

    result = OTA_State(pEASData, pInstData, &legacyState);
    if (result == EAS_SUCCESS)
        *pState = (EAS_STATE) legacyState;
    return result;
}

static EAS_RESULT JL_OTA_SetData(S_EAS_DATA *pEASData,
                                 EAS_VOID_PTR pInstData,
                                 EAS_I32 param,
                                 EAS_IPTR value)
{
    S_OTA_DATA *pData = (S_OTA_DATA *) pInstData;
    (void) pEASData;

    if (pData == NULL)
        return EAS_ERROR_INVALID_PARAMETER;

    switch (param)
    {
        case PARSER_DATA_METADATA_CB:
            if (value == 0)
                return EAS_ERROR_INVALID_PARAMETER;
            EAS_HWMemCpy(&pData->metadata, (void *) value, sizeof(S_METADATA_CB));
            return EAS_SUCCESS;

        default:
            return EAS_ERROR_INVALID_PARAMETER;
    }
}

static EAS_RESULT JL_OTA_GetData(S_EAS_DATA *pEASData,
                                 EAS_VOID_PTR pInstData,
                                 EAS_I32 param,
                                 EAS_IPTR *pValue)
{
    S_OTA_DATA *pData = (S_OTA_DATA *) pInstData;
    (void) pEASData;

    if ((pData == NULL) || (pValue == NULL))
        return EAS_ERROR_INVALID_PARAMETER;

    switch (param)
    {
        case PARSER_DATA_FILE_TYPE:
            *pValue = EAS_FILE_OTA;
            break;

        case PARSER_DATA_SYNTH_HANDLE:
            *pValue = (EAS_IPTR) pData->pSynth;
            break;

        case PARSER_DATA_GAIN_OFFSET:
            *pValue = OTA_GAIN_OFFSET;
            break;

        default:
            return EAS_ERROR_INVALID_PARAMETER;
    }

    return EAS_SUCCESS;
}

const S_FILE_PARSER_INTERFACE EAS_OTA_Parser =
{
    OTA_CheckFileType,
    OTA_Prepare,
    OTA_Time,
    OTA_Event,
    JL_OTA_State,
    OTA_Close,
    OTA_Reset,
#ifdef JET_INTERFACE
    OTA_Pause,
    OTA_Resume,
#else
    NULL,
    NULL,
#endif
    NULL,
    JL_OTA_SetData,
    JL_OTA_GetData,
    NULL
};
