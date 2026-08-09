/*
 * JL-Mod Plus SONiVOX 4 integration configuration.
 *
 * Keep the Java ME/MMAPI compatibility profile explicit here instead of
 * inheriting upstream defaults. The renderer is fixed at 44.1 kHz; this is an
 * implementation detail below JSR-135, not an exposed application setting.
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

#define _XMF_PARSER
#define _RMID_PARSER
#define _IMELODY_PARSER
#define _RTTTL_PARSER
#define _OTA_PARSER
#define MMAPI_SUPPORT

#define _16_BIT_SAMPLES
#define _SAMPLE_RATE_44100

#define _SF2_SUPPORT
#define _FLOAT_DCF

/*
 * Intentionally disabled:
 * - EAS_FM_SYNTH / EAS_HYBRID_SYNTH
 * - _WAVE_PARSER / _IMA_DECODER (WAV belongs to the dedicated dr_wav path)
 * - JET_INTERFACE (not required by JSR-135)
 * - _ZLIB_UNPACKER (enable only if compressed XMF compatibility needs it)
 */

#endif // JL_MOD_PLUS_SONIVOX_V4_EAS_OPTIONS_H
