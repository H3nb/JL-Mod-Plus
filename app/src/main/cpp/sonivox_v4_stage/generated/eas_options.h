/*
 * JL-Mod Plus SONiVOX 4 staging configuration.
 *
 * This mirrors the compatibility profile we intend to use when the v4 engine
 * replaces the legacy embedded SONiVOX build. Keep the profile explicit here
 * rather than inheriting upstream CMake defaults, which are not tailored for
 * Java ME/MMAPI compatibility.
 */
#ifndef JL_MOD_PLUS_SONIVOX_V4_EAS_OPTIONS_H
#define JL_MOD_PLUS_SONIVOX_V4_EAS_OPTIONS_H

#define UNIFIED_DEBUG_MESSAGES

#define EAS_WT_SYNTH
#define NUM_OUTPUT_CHANNELS 2
#define MAX_SYNTH_VOICES 64

#define _FILTER_ENABLED
#define DLS_SYNTHESIZER
#define _REVERB_ENABLED
#define _CHORUS_ENABLED

#define _IMELODY_PARSER
#define _RTTTL_PARSER
#define _OTA_PARSER
#define _XMF_PARSER
#define _RMID_PARSER
#define MMAPI_SUPPORT

#define _16_BIT_SAMPLES
#define _SAMPLE_RATE_22050

#define _SF2_SUPPORT
#define _FLOAT_DCF

/*
 * Intentionally disabled in the first migration stage:
 * - EAS_FM_SYNTH / EAS_HYBRID_SYNTH
 * - _WAVE_PARSER / _IMA_DECODER (WAV belongs to the dedicated dr_wav path)
 * - JET_INTERFACE (not required by JSR-135)
 * - _ZLIB_UNPACKER (enable only if compressed XMF compatibility needs it)
 */

#endif // JL_MOD_PLUS_SONIVOX_V4_EAS_OPTIONS_H
