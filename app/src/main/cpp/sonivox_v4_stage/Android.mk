# Copyright 2026 H3NB
# SPDX-License-Identifier: Apache-2.0
#
# SONiVOX v4.0.1 build profile for JL-Mod Plus. The upstream source is pinned
# as a submodule; this ndk-build file intentionally preserves Java ME/MMAPI
# compatibility options instead of inheriting upstream desktop defaults.

LOCAL_PATH := $(call my-dir)
SONIVOX_V4 := $(LOCAL_PATH)/../sonivox_v4
SONIVOX_V4_HOST := $(SONIVOX_V4)/arm-wt-22k/host_src
SONIVOX_V4_LIB := $(SONIVOX_V4)/arm-wt-22k/lib_src

include $(CLEAR_VARS)

LOCAL_MODULE := sonivox_v4

# Upstream v4.0.1 keeps the optional iMelody/RTTTL/OTA parser sources, but
# those files still use the old 32-bit parser callback ABI (EAS_I32 for values
# that are EAS_IPTR in the current interface). Do not compile them on ARM64
# until they have a pointer-width-safe compatibility patch; suppressing the
# compiler errors would leave real pointer truncation bugs.
#
# eas_public.c also contains two MMAPI-only calls using the obsolete printf-
# style EAS_ReportEx signature. Compile it through a tiny compatibility
# translation unit that redirects only those stale calls to EAS_Report while
# keeping the pinned upstream source otherwise unchanged.
LOCAL_SRC_FILES := \
	api_smoke.c \
	eas_public_compat.c \
	../sonivox_v4/arm-wt-22k/host_src/eas_config.c \
	../sonivox_v4/arm-wt-22k/host_src/eas_report.c \
	../sonivox_v4/arm-wt-22k/lib_src/eas_chorus.c \
	../sonivox_v4/arm-wt-22k/lib_src/eas_dlssynth.c \
	../sonivox_v4/arm-wt-22k/lib_src/eas_flog.c \
	../sonivox_v4/arm-wt-22k/lib_src/eas_math.c \
	../sonivox_v4/arm-wt-22k/lib_src/eas_mdls.c \
	../sonivox_v4/arm-wt-22k/lib_src/eas_midi.c \
	../sonivox_v4/arm-wt-22k/lib_src/eas_mixbuf.c \
	../sonivox_v4/arm-wt-22k/lib_src/eas_mixer.c \
	../sonivox_v4/arm-wt-22k/lib_src/eas_pan.c \
	../sonivox_v4/arm-wt-22k/lib_src/eas_pcm.c \
	../sonivox_v4/arm-wt-22k/lib_src/eas_reverb.c \
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

# Consumers keep the existing libsonivox/... include surface while the source
# itself comes from the pinned v4 submodule.
LOCAL_EXPORT_C_INCLUDES := \
	$(LOCAL_PATH)/include \
	$(LOCAL_PATH)/generated \
	$(SONIVOX_V4_HOST) \
	$(SONIVOX_V4_LIB)
LOCAL_EXPORT_LDLIBS := -lm -llog

ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
	LOCAL_ARM_NEON := false
endif

include $(BUILD_STATIC_LIBRARY)
