# Copyright 2026 H3NB
# SPDX-License-Identifier: Apache-2.0
#
# Temporary compile-only staging module for the pinned SONiVOX v4.0.1 source.
# It is deliberately separate from the active legacy `sonivox` module so the
# engine upgrade can be validated before changing runtime routing.

LOCAL_PATH := $(call my-dir)
SONIVOX_V4 := $(LOCAL_PATH)/../sonivox_v4
SONIVOX_V4_HOST := $(SONIVOX_V4)/arm-wt-22k/host_src
SONIVOX_V4_LIB := $(SONIVOX_V4)/arm-wt-22k/lib_src
SONIVOX_V4_SRC := $(SONIVOX_V4)/arm-wt-22k/src

include $(CLEAR_VARS)

LOCAL_MODULE := sonivox_v4_stage

LOCAL_SRC_FILES := \
	api_smoke.c \
	../sonivox_v4/arm-wt-22k/host_src/eas_config.c \
	../sonivox_v4/arm-wt-22k/host_src/eas_report.c \
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
	../sonivox_v4/arm-wt-22k/lib_src/eas_xmf.c \
	../sonivox_v4/arm-wt-22k/src/rmidi.c \
	../sonivox_v4/arm-wt-22k/lib_src/eas_sndlibmgt.c \
	../sonivox_v4/arm-wt-22k/lib_src/eas_wtengine.c \
	../sonivox_v4/arm-wt-22k/lib_src/eas_wtsynth.c \
	../sonivox_v4/arm-wt-22k/lib_src/wt_200k_G.c \
	../sonivox_v4/arm-wt-22k/src/hostmm_ng.c \
	../sonivox_v4/arm-wt-22k/lib_src/eas_sf2.c \
	../sonivox_v4/arm-wt-22k/lib_src/eas_filter_float.c

LOCAL_C_INCLUDES := \
	$(LOCAL_PATH)/generated \
	$(SONIVOX_V4_HOST) \
	$(SONIVOX_V4_LIB) \
	$(SONIVOX_V4)/fakes

LOCAL_CFLAGS += \
	-O2 \
	-fvisibility=hidden \
	-Dfalse=0 \
	-Wno-unused-parameter \
	-Wno-unused-value \
	-Wno-unused-variable \
	-Wno-unused-function \
	-Wno-misleading-indentation \
	-Wno-attributes \
	-Wformat

LOCAL_CONLYFLAGS += -std=c11
LOCAL_LDLIBS += -lm -llog

# Keep the staging library valid on 16 KB page-size Android devices too.
LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384

# Don't strip debug builds so CI failures remain diagnosable.
ifeq ($(NDK_DEBUG),1)
	cmd-strip :=
endif

# A shared staging module is intentional: ndk-build will compile it even though
# no runtime code links against it yet. It will be removed/converted once v4
# replaces the active legacy SONiVOX library.
include $(BUILD_SHARED_LIBRARY)
