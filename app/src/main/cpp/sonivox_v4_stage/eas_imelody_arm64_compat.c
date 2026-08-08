/*
 * Copyright 2026 H3NB
 * SPDX-License-Identifier: Apache-2.0
 *
 * ARM64/LP64 compatibility bridge for SONiVOX v4.0.1 iMelody.
 *
 * The parser algorithm remains the pinned upstream implementation. Only its
 * stale pre-v4 callback ABI is isolated below and adapted to the current
 * S_FILE_PARSER_INTERFACE. Do not add format-specific behavior here.
 */

#include <string.h>

#include "legacy_parser_abi.h"
#include "eas_data.h"
#include "eas_miditypes.h"
#include "eas_report.h"
#include "eas_host.h"
#include "eas_midi.h"
#include "eas_config.h"
#include "eas_vm_protos.h"
#include "eas_imelodydata.h"
#include "eas_ctype.h"

/*
 * The upstream file has a modern EAS_STATE * forward declaration but an old
 * EAS_I32 * definition, plus old EAS_I32 set/get callbacks. Compile that
 * implementation against a private description of its historical ABI. All
 * headers are included above before EAS_STATE is temporarily mapped, so this
 * does not alter any public SONiVOX declarations or parser data structures.
 */
#define S_FILE_PARSER_INTERFACE static JL_LEGACY_FILE_PARSER_INTERFACE
#define EAS_STATE EAS_I32
#define EAS_iMelody_Parser JL_iMelody_LegacyParser
#include "../sonivox_v4/arm-wt-22k/lib_src/eas_imelody.c"
#undef EAS_iMelody_Parser
#undef EAS_STATE
#undef S_FILE_PARSER_INTERFACE

static EAS_RESULT JL_IMY_State(S_EAS_DATA *pEASData,
                               EAS_VOID_PTR pInstData,
                               EAS_STATE *pState)
{
    EAS_I32 legacyState = EAS_STATE_ERROR;
    EAS_RESULT result;

    if (pState == NULL)
        return EAS_ERROR_INVALID_PARAMETER;

    result = IMY_State(pEASData, pInstData, &legacyState);
    if (result == EAS_SUCCESS)
        *pState = (EAS_STATE) legacyState;
    return result;
}

static EAS_RESULT JL_IMY_SetData(S_EAS_DATA *pEASData,
                                 EAS_VOID_PTR pInstData,
                                 EAS_I32 param,
                                 EAS_IPTR value)
{
    S_IMELODY_DATA *pData = (S_IMELODY_DATA *) pInstData;
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

static EAS_RESULT JL_IMY_GetData(S_EAS_DATA *pEASData,
                                 EAS_VOID_PTR pInstData,
                                 EAS_I32 param,
                                 EAS_IPTR *pValue)
{
    S_IMELODY_DATA *pData = (S_IMELODY_DATA *) pInstData;
    (void) pEASData;

    if ((pData == NULL) || (pValue == NULL))
        return EAS_ERROR_INVALID_PARAMETER;

    switch (param)
    {
        case PARSER_DATA_FILE_TYPE:
            *pValue = EAS_FILE_IMELODY;
            break;

        case PARSER_DATA_SYNTH_HANDLE:
            *pValue = (EAS_IPTR) pData->pSynth;
            break;

        case PARSER_DATA_GAIN_OFFSET:
            *pValue = IMELODY_GAIN_OFFSET;
            break;

        default:
            return EAS_ERROR_INVALID_PARAMETER;
    }

    return EAS_SUCCESS;
}

/* Current v4 parser interface registered by eas_config.c. */
const S_FILE_PARSER_INTERFACE EAS_iMelody_Parser =
{
    IMY_CheckFileType,
    IMY_Prepare,
    IMY_Time,
    IMY_Event,
    JL_IMY_State,
    IMY_Close,
    IMY_Reset,
#ifdef JET_INTERFACE
    IMY_Pause,
    IMY_Resume,
#else
    NULL,
    NULL,
#endif
    NULL,
    JL_IMY_SetData,
    JL_IMY_GetData,
    NULL
};
