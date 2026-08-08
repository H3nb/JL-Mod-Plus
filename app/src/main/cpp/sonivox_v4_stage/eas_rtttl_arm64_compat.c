/*
 * Copyright 2026 H3NB
 * SPDX-License-Identifier: Apache-2.0
 *
 * ARM64/LP64 compatibility bridge for SONiVOX v4.0.1 RTTTL/RTX.
 *
 * The parser algorithm remains the pinned upstream implementation. Only its
 * stale pre-v4 callback ABI is isolated below and adapted to the current
 * S_FILE_PARSER_INTERFACE. Do not add format-specific behavior here.
 */

#include "legacy_parser_abi.h"
#include "eas_data.h"
#include "eas_miditypes.h"
#include "eas_report.h"
#include "eas_host.h"
#include "eas_midi.h"
#include "eas_config.h"
#include "eas_vm_protos.h"
#include "eas_rtttldata.h"
#include "eas_ctype.h"

#define S_FILE_PARSER_INTERFACE static JL_LEGACY_FILE_PARSER_INTERFACE
#define EAS_STATE EAS_I32
#define EAS_RTTTL_Parser JL_RTTTL_LegacyParser
#include "../sonivox_v4/arm-wt-22k/lib_src/eas_rtttl.c"
#undef EAS_RTTTL_Parser
#undef EAS_STATE
#undef S_FILE_PARSER_INTERFACE

/*
 * Upstream RTTTL_Prepare() frees pInstData when header parsing fails after
 * VMInitMIDI() succeeds. The generic EAS stream still owns that handle and
 * EAS_CloseFile() will subsequently pass it to pfClose, which is a UAF in the
 * dynamic-memory model used on Android. Keep parser-instance ownership with
 * the stream; release only the synth acquired by this prepare attempt and let
 * RTTTL_Close() close the file/free the parser instance exactly once.
 */
static EAS_RESULT JL_RTTTL_Prepare(S_EAS_DATA *pEASData,
                                   EAS_VOID_PTR pInstData)
{
    S_RTTTL_DATA *pData = (S_RTTTL_DATA *) pInstData;
    EAS_RESULT result;

    if (pData == NULL)
        return EAS_ERROR_INVALID_PARAMETER;
    if (pData->state != EAS_STATE_OPEN)
        return EAS_ERROR_NOT_VALID_IN_THIS_STATE;

    result = VMInitMIDI(pEASData, &pData->pSynth);
    if (result != EAS_SUCCESS)
        return result;

    pData->state = EAS_STATE_ERROR;
    result = RTTTL_ParseHeader(pEASData, pData,
                               (EAS_BOOL) (pData->metadata.callback != NULL));
    if (result != EAS_SUCCESS)
    {
        if (pData->pSynth != NULL)
        {
            VMMIDIShutdown(pEASData, pData->pSynth);
            pData->pSynth = NULL;
        }
        return result;
    }

    pData->state = EAS_STATE_READY;
    return EAS_SUCCESS;
}

static EAS_RESULT JL_RTTTL_State(S_EAS_DATA *pEASData,
                                 EAS_VOID_PTR pInstData,
                                 EAS_STATE *pState)
{
    EAS_I32 legacyState = EAS_STATE_ERROR;
    EAS_RESULT result;

    if (pState == NULL)
        return EAS_ERROR_INVALID_PARAMETER;

    result = RTTTL_State(pEASData, pInstData, &legacyState);
    if (result == EAS_SUCCESS)
        *pState = (EAS_STATE) legacyState;
    return result;
}

static EAS_RESULT JL_RTTTL_SetData(S_EAS_DATA *pEASData,
                                   EAS_VOID_PTR pInstData,
                                   EAS_I32 param,
                                   EAS_IPTR value)
{
    S_RTTTL_DATA *pData = (S_RTTTL_DATA *) pInstData;
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

static EAS_RESULT JL_RTTTL_GetData(S_EAS_DATA *pEASData,
                                   EAS_VOID_PTR pInstData,
                                   EAS_I32 param,
                                   EAS_IPTR *pValue)
{
    S_RTTTL_DATA *pData = (S_RTTTL_DATA *) pInstData;
    (void) pEASData;

    if ((pData == NULL) || (pValue == NULL))
        return EAS_ERROR_INVALID_PARAMETER;

    switch (param)
    {
        case PARSER_DATA_FILE_TYPE:
            *pValue = EAS_FILE_RTTTL;
            break;

        case PARSER_DATA_SYNTH_HANDLE:
            *pValue = (EAS_IPTR) pData->pSynth;
            break;

        case PARSER_DATA_GAIN_OFFSET:
            *pValue = RTTTL_GAIN_OFFSET;
            break;

        default:
            return EAS_ERROR_INVALID_PARAMETER;
    }

    return EAS_SUCCESS;
}

const S_FILE_PARSER_INTERFACE EAS_RTTTL_Parser =
{
    RTTTL_CheckFileType,
    JL_RTTTL_Prepare,
    RTTTL_Time,
    RTTTL_Event,
    JL_RTTTL_State,
    RTTTL_Close,
    RTTTL_Reset,
#ifdef JET_INTERFACE
    RTTTL_Pause,
    RTTTL_Resume,
#else
    NULL,
    NULL,
#endif
    NULL,
    JL_RTTTL_SetData,
    JL_RTTTL_GetData,
    NULL
};
