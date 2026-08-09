# Copyright 2026 H3NB
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.

LOCAL_PATH := $(call my-dir)

MMAPI_EAS_SRC_FILES := \
	eas_file.cpp \
	eas_player.cpp \
	eas_player_jni.cpp \
	eas_util.cpp

MMAPI_EAS_LDFLAGS := \
	-Wl,-z,max-page-size=16384 \
	-Wl,-z,common-page-size=16384 \
	-Wl,-Bsymbolic \
	-Wl,--exclude-libs,ALL

# 22.05 kHz compatibility backend.
include $(CLEAR_VARS)
LOCAL_MODULE := mmapi_eas_22k
LOCAL_SRC_FILES := $(MMAPI_EAS_SRC_FILES)
LOCAL_CFLAGS += -O2
LOCAL_LDFLAGS += $(MMAPI_EAS_LDFLAGS)
LOCAL_C_INCLUDES := $(LOCAL_PATH)
LOCAL_ARM_MODE := arm
LOCAL_SHARED_LIBRARIES := mmapi_common
LOCAL_STATIC_LIBRARIES := sonivox_v4_22k
ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
    LOCAL_ARM_NEON := false
endif
ifeq ($(NDK_DEBUG),1)
    cmd-strip :=
endif
include $(BUILD_SHARED_LIBRARY)

# 44.1 kHz backend. The JNI source is shared; this define changes only the
# Java binding names so both native engines can coexist in one process.
include $(CLEAR_VARS)
LOCAL_MODULE := mmapi_eas_44k
LOCAL_SRC_FILES := $(MMAPI_EAS_SRC_FILES)
LOCAL_CFLAGS += -O2 -DEAS_JNI_44K
LOCAL_LDFLAGS += $(MMAPI_EAS_LDFLAGS)
LOCAL_C_INCLUDES := $(LOCAL_PATH)
LOCAL_ARM_MODE := arm
LOCAL_SHARED_LIBRARIES := mmapi_common
LOCAL_STATIC_LIBRARIES := sonivox_v4_44k
ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
    LOCAL_ARM_NEON := false
endif
ifeq ($(NDK_DEBUG),1)
    cmd-strip :=
endif
include $(BUILD_SHARED_LIBRARY)

$(call import-module,prefab/oboe)
