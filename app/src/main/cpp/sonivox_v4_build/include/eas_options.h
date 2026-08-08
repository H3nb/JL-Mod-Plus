/*
 * JL-Mod Plus SONiVOX 4.0.1 feature profile.
 *
 * Keep this intentionally narrower than upstream defaults: preserve the
 * Java ME formats we rely on, add SF2, and leave WAV/IMA/JET outside EAS.
 */
#ifndef JL_MOD_SONIVOX_V4_EAS_OPTIONS_H
#define JL_MOD_SONIVOX_V4_EAS_OPTIONS_H

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

/* Deliberately disabled:
 *   EAS_FM_SYNTH / EAS_HYBRID_SYNTH
 *   _WAVE_PARSER / _IMA_DECODER
 *   JET_INTERFACE
 *   MP3_SUPPORT
 *   _ZLIB_UNPACKER
 */

#endif // JL_MOD_SONIVOX_V4_EAS_OPTIONS_H
