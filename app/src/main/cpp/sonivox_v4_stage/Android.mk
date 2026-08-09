# Copyright 2026 H3NB
# SPDX-License-Identifier: Apache-2.0
#
# SONiVOX v4.0.1 build profiles for JL-Mod Plus. SONiVOX selects its render
# rate at compile time, so both supported rates are built as separate static
# engines and selected by the Java MMAPI layer at runtime.

LOCAL_PATH := $(call my-dir)
SONIVOX_V4 := $(LOCAL_PATH)/../sonivox_v4
SONIVOX_V4_HOST := $(SONIVOX_V4)/arm-wt-22k/host_src
SONIVOX_V4_LIB := $(SONIVOX_V4)/arm-wt-22k/lib_src

# iMelody, RTTTL/RTX and Nokia OTA remain useful Java ME-era formats. Their
# pinned v4.0.1 sources still implement the pre-v4 32-bit state/set/get parser
# callbacks. The *_arm64_compat.c translation units keep those algorithms
# behind pointer-width-safe adapters.
SONIVOX_V4_SRC_FILES := \
	api_smoke.c \
	eas_public_compat.c \
	eas_imelody_arm64_compat.c \
	eas_rtttl_arm64_compat.c \
	eas_ota_arm64_compat.c \
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

SONIVOX_V4_CFLAGS := \
	-O2 \
	-fvisibility=hidden \
	-Dfalse=0 \
	-Wno-unused-parameter \
	-Wno-unused-value \
	-Wno-unused-variable \
	-Wno-unused-function \
	-Wno-misleading-indentation \
	-Wno-attributes \
	-Wformat \
	-Werror=incompatible-function-pointer-types

# Compatibility default: genuine 22.05 kHz SONiVOX renderer.
include $(CLEAR_VARS)
LOCAL_MODULE := sonivox_v4_22k
LOCAL_SRC_FILES := $(SONIVOX_V4_SRC_FILES)
LOCAL_C_INCLUDES := \
	$(LOCAL_PATH)/generated \
	$(SONIVOX_V4_HOST) \
	$(SONIVOX_V4_LIB) \
	$(SONIVOX_V4)/fakes
LOCAL_CFLAGS += $(SONIVOX_V4_CFLAGS)
LOCAL_CONLYFLAGS += -std=c11
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

# Higher-quality option: same compatibility profile at genuine 44.1 kHz.
include $(CLEAR_VARS)
LOCAL_MODULE := sonivox_v4_44k
LOCAL_SRC_FILES := $(SONIVOX_V4_SRC_FILES)
LOCAL_C_INCLUDES := \
	$(LOCAL_PATH)/generated_44k \
	$(LOCAL_PATH)/generated \
	$(SONIVOX_V4_HOST) \
	$(SONIVOX_V4_LIB) \
	$(SONIVOX_V4)/fakes
LOCAL_CFLAGS += $(SONIVOX_V4_CFLAGS)
LOCAL_CONLYFLAGS += -std=c11
LOCAL_EXPORT_C_INCLUDES := \
	$(LOCAL_PATH)/include \
	$(LOCAL_PATH)/generated_44k \
	$(LOCAL_PATH)/generated \
	$(SONIVOX_V4_HOST) \
	$(SONIVOX_V4_LIB)
LOCAL_EXPORT_LDLIBS := -lm -llog
ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
	LOCAL_ARM_NEON := false
endif
include $(BUILD_STATIC_LIBRARY)
