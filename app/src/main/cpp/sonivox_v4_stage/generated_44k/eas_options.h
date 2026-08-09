/*
 * JL-Mod Plus SONiVOX 4 staging configuration — 44.1 kHz variant.
 *
 * Keep this profile aligned with generated/eas_options.h except for the
 * compile-time sample-rate selector. SONiVOX configures its renderer at
 * build time, so the app builds separate 22.05 kHz and 44.1 kHz engines.
 */
#ifndef JL_MOD_PLUS_SONIVOX_V4_EAS_OPTIONS_44K_H
#define JL_MOD_PLUS_SONIVOX_V4_EAS_OPTIONS_44K_H

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

/* WAV remains on the dedicated dr_wav path. */

#endif // JL_MOD_PLUS_SONIVOX_V4_EAS_OPTIONS_44K_H
