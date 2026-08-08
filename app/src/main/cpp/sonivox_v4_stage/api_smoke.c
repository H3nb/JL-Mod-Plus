/*
 * Compile/link smoke surface for the SONiVOX APIs used by JL-Mod Plus.
 *
 * This function is never called by the app. Keeping the references in the
 * staging shared library makes ndk-build verify the v4.0.1 public API we need
 * before the active EAS wrapper is switched over.
 */
#include "eas.h"
#include "eas_reverb.h"

#if defined(__GNUC__) || defined(__clang__)
__attribute__((visibility("default")))
#endif
EAS_RESULT jlmod_sonivox_v4_api_smoke(EAS_FILE_LOCATOR locator)
{
    EAS_DATA_HANDLE eas = NULL;
    EAS_HANDLE stream = NULL;
    EAS_RESULT result = EAS_Init(&eas);
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
