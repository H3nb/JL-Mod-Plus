# Copyright 2026 H3NB
# SPDX-License-Identifier: Apache-2.0
#
# Staging build for EmbeddedSynth/sonivox v4.0.1. It is intentionally kept as
# a separate static module until compatibility tests justify switching mmapi_eas
# away from the existing engine.

LOCAL_PATH := $(call my-dir)
SONIVOX_V4_ROOT := $(LOCAL_PATH)/../sonivox_v4/arm-wt-22k

include $(CLEAR_VARS)

LOCAL_MODULE := sonivox_v4

LOCAL_SRC_FILES := \
	../sonivox_v4/arm-wt-22k/host_src/eas_config.c \
	../sonivox_v4/arm-wt-22k/host_src/eas_report.c \
	../sonivox_v4/arm-wt-22k/host_src/eas_hostmm.c \
	../sonivox_v4/arm-wt-22k/lib_src/eas_chorus.c \
	../sonivox_v4/arm-wt-22k/lib_src/eas_dlssynth.c \
	../sonivox_v4/arm-wt-22k/lib_src/eas_flog.c \
	../sonivox_v4/arm-wt-22k/lib_src/eas_imelody.c \
	../sonivox_v4/arm-wt-22k/lib_src/eas_math.c \
	../sonivox_v4/arm-wt-22k/lib_src/eas_mdls.c \
	../sonivox_v4/arm-wt-22k/lib_src/eas_midi.c \
	../sonivox_v4/arm-wt-22k/lib_src/eas_mixbuf.c \
	../sonivox_v4/arm-wt-22k/lib_src/eas_mixer.c \
	../sonivox_v4/arm-wt-22k/lib_src/eas_ota.c \
	../sonivox_v4/arm-wt-22k/lib_src/eas_pan.c \
	../sonivox_v4/arm-wt-22k/lib_src/eas_pcm.c \
	../sonivox_v4/arm-wt-22k/lib_src/eas_public.c \
	../sonivox_v4/arm-wt-22k/lib_src/eas_reverb.c \
	../sonivox_v4/arm-wt-22k/lib_src/eas_rtttl.c \
	../sonivox_v4/arm-wt-22k/lib_src/eas_smf.c \
	../sonivox_v4/arm-wt-22k/lib_src/eas_tonecontrol.c \
	../sonivox_v4/arm-wt-22k/lib_src/eas_voicemgt.c \
	../sonivox_v4/arm-wt-22k/lib_src/eas_wtengine.c \
	../sonivox_v4/arm-wt-22k/lib_src/eas_wtsynth.c \
	../sonivox_v4/arm-wt-22k/lib_src/eas_xmf.c \
	../sonivox_v4/arm-wt-22k/lib_src/eas_sndlibmgt.c \
	../sonivox_v4/arm-wt-22k/lib_src/eas_sf2.c \
	../sonivox_v4/arm-wt-22k/lib_src/eas_filter_float.c \
	../sonivox_v4/arm-wt-22k/lib_src/wt_200k_G.c \
	../sonivox_v4/arm-wt-22k/src/rmidi.c

LOCAL_CFLAGS += \
	-O2 \
	-Wno-unused-parameter \
	-Wno-unused-value \
	-Wno-unused-variable \
	-Wno-unused-function \
	-Wno-misleading-indentation \
	-Wno-attributes \
	-Wformat

LOCAL_C_INCLUDES := \
	$(LOCAL_PATH)/include \
	$(SONIVOX_V4_ROOT)/host_src \
	$(SONIVOX_V4_ROOT)/lib_src

LOCAL_EXPORT_C_INCLUDES := \
	$(LOCAL_PATH)/include \
	$(SONIVOX_V4_ROOT)/host_src \
	$(SONIVOX_V4_ROOT)/lib_src

# The library is linked statically when it eventually replaces the old module.
LOCAL_CFLAGS += -DSONIVOX_STATIC_DEFINE

include $(BUILD_STATIC_LIBRARY)
