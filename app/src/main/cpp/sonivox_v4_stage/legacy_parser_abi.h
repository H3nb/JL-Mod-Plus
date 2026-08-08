/*
 * Copyright 2026 H3NB
 * SPDX-License-Identifier: Apache-2.0
 *
 * Compatibility declarations for optional legacy SONiVOX ringtone parsers.
 *
 * SONiVOX v4 changed the generic parser ABI so state is returned through
 * EAS_STATE and parser values capable of carrying pointers use EAS_IPTR.
 * The iMelody, RTTTL and OTA sources retained their pre-v4 callback
 * signatures. This private interface describes that old ABI only while the
 * pinned upstream source is included by the compatibility translation units.
 * It must never be exposed to the rest of SONiVOX or JL-Mod Plus.
 */
#ifndef JL_MOD_PLUS_LEGACY_PARSER_ABI_H
#define JL_MOD_PLUS_LEGACY_PARSER_ABI_H

#include "eas_parser.h"

/*
 * Exact pre-v4 callback shape used by eas_imelody.c, eas_rtttl.c and
 * eas_ota.c. All parser operations other than state/set/get already match the
 * current S_FILE_PARSER_INTERFACE.
 */
typedef struct {
    EAS_RESULT (*pfCheckFileType)(struct s_eas_data_tag *pEASData,
                                  EAS_FILE_HANDLE fileHandle,
                                  EAS_VOID_PTR *ppHandle,
                                  EAS_I32 offset);
    EAS_RESULT (*pfPrepare)(struct s_eas_data_tag *pEASData, EAS_VOID_PTR pInstData);
    EAS_RESULT (*pfTime)(struct s_eas_data_tag *pEASData,
                         EAS_VOID_PTR pInstData,
                         EAS_U32 *pTime);
    EAS_RESULT (*pfEvent)(struct s_eas_data_tag *pEASData,
                          EAS_VOID_PTR pInstData,
                          EAS_INT parserMode);
    EAS_RESULT (*pfState)(struct s_eas_data_tag *pEASData,
                          EAS_VOID_PTR pInstData,
                          EAS_I32 *pState);
    EAS_RESULT (*pfClose)(struct s_eas_data_tag *pEASData, EAS_VOID_PTR pInstData);
    EAS_RESULT (*pfReset)(struct s_eas_data_tag *pEASData, EAS_VOID_PTR pInstData);
    EAS_RESULT (*pfPause)(struct s_eas_data_tag *pEASData, EAS_VOID_PTR pInstData);
    EAS_RESULT (*pfResume)(struct s_eas_data_tag *pEASData, EAS_VOID_PTR pInstData);
    EAS_RESULT (*pfLocate)(struct s_eas_data_tag *pEASData,
                           EAS_VOID_PTR pInstData,
                           EAS_I32 time,
                           EAS_BOOL *pParserDone);
    EAS_RESULT (*pfSetData)(struct s_eas_data_tag *pEASData,
                            EAS_VOID_PTR pInstData,
                            EAS_I32 param,
                            EAS_I32 value);
    EAS_RESULT (*pfGetData)(struct s_eas_data_tag *pEASData,
                            EAS_VOID_PTR pInstData,
                            EAS_I32 param,
                            EAS_I32 *pValue);
    EAS_RESULT (*pfGetMetaData)(struct s_eas_data_tag *pEASData,
                                EAS_VOID_PTR pInstData,
                                EAS_I32 *pMediaLength);
} JL_LEGACY_FILE_PARSER_INTERFACE;

/* EAS_IPTR is the only parser value type allowed to transport pointers. */
_Static_assert(sizeof(EAS_IPTR) >= sizeof(void *),
               "EAS_IPTR must be wide enough to carry a pointer");

#endif /* JL_MOD_PLUS_LEGACY_PARSER_ABI_H */
