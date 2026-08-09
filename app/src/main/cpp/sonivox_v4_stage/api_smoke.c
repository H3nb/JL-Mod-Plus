/*
 * Compile/link smoke surface for the SONiVOX APIs used by JL-Mod Plus.
 *
 * This function is never called by the app. Keeping the references in the
 * SONiVOX v4 static library makes ndk-build verify the public API and optional
 * parser surface that the active EAS wrapper depends on.
 */
#include "eas.h"
#include "eas_parser.h"
#include "eas_reverb.h"

_Static_assert(sizeof(EAS_IPTR) == sizeof(void *),
               "EAS_IPTR must exactly match the platform pointer width");
_Static_assert(sizeof(EAS_STATE) >= sizeof(EAS_I32),
               "EAS_STATE must hold every legacy parser state value");

#ifdef _IMELODY_PARSER
extern const S_FILE_PARSER_INTERFACE EAS_iMelody_Parser;
#endif
#ifdef _RTTTL_PARSER
extern const S_FILE_PARSER_INTERFACE EAS_RTTTL_Parser;
#endif
#ifdef _OTA_PARSER
extern const S_FILE_PARSER_INTERFACE EAS_OTA_Parser;
#endif

/*
 * Type-check and retain the restored parser interfaces. eas_config.c also
 * registers these symbols; this independent list makes accidental ABI drift
 * fail at compile/link time even if the configuration module changes later.
 */
static const S_FILE_PARSER_INTERFACE *const jlmod_legacy_parser_smoke[] = {
#ifdef _IMELODY_PARSER
    &EAS_iMelody_Parser,
#endif
#ifdef _RTTTL_PARSER
    &EAS_RTTTL_Parser,
#endif
#ifdef _OTA_PARSER
    &EAS_OTA_Parser,
#endif
    NULL
};

#if defined(__GNUC__) || defined(__clang__)
__attribute__((visibility("default")))
#endif
EAS_RESULT jlmod_sonivox_v4_api_smoke(EAS_FILE_LOCATOR locator)
{
    EAS_DATA_HANDLE eas = NULL;
    EAS_HANDLE stream = NULL;
    EAS_RESULT result;
    (void) jlmod_legacy_parser_smoke;

    result = EAS_Init(&eas);
    if (result != EAS_SUCCESS) {
        return result;
    }

    (void) EAS_Config();
    (void) EAS_SetHeaderSearchFlag(eas, EAS_FALSE);
    (void) EAS_SetParameter(eas,
                            EAS_MODULE_REVERB,
                            EAS_PARAM_REVERB_BYPASS,
                            EAS_TRUE);

    if (locator != NULL) {
        /* SONiVOX 4 accepts both DLS and supported SF2 through this entry. */
        (void) EAS_LoadDLSCollection(eas, NULL, locator);

        result = EAS_OpenFile(eas, locator, &stream);
        if (result == EAS_SUCCESS) {
            EAS_I32 fileType = EAS_FILE_UNKNOWN;
            EAS_I32 duration = -1;
            (void) EAS_Prepare(eas, stream);
            (void) EAS_GetFileType(eas, stream, &fileType);
            (void) EAS_ParseMetaData(eas, stream, &duration);
            (void) EAS_CloseFile(eas, stream);
            stream = NULL;
        }

#ifdef MMAPI_SUPPORT
        (void) EAS_MMAPIToneControl(eas, locator, &stream);
        if (stream != NULL) {
            (void) EAS_CloseFile(eas, stream);
            stream = NULL;
        }
#endif
    }

    result = EAS_OpenMIDIStream(eas, &stream, NULL);
    if (result == EAS_SUCCESS) {
        EAS_U8 message[3] = {0x90, 60, 0};
        (void) EAS_WriteMIDIStream(eas, stream, message, 3);
        (void) EAS_CloseMIDIStream(eas, stream);
    }

    return EAS_Shutdown(eas);
}
