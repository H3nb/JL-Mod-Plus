# Copyright 2026 H3NB
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.

LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := mmapi_eas
LOCAL_SRC_FILES := \
	eas_file.cpp \
	eas_player.cpp \
	eas_player_jni.cpp \
	eas_util.cpp

LOCAL_CFLAGS += -O2
LOCAL_LDFLAGS += \
	-Wl,-z,max-page-size=16384 \
	-Wl,-z,common-page-size=16384 \
	-Wl,-Bsymbolic \
	-Wl,--exclude-libs,ALL
LOCAL_C_INCLUDES := $(LOCAL_PATH)
LOCAL_ARM_MODE := arm
LOCAL_SHARED_LIBRARIES := mmapi_common
LOCAL_STATIC_LIBRARIES := sonivox_v4

ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
    LOCAL_ARM_NEON := false
endif

ifeq ($(NDK_DEBUG),1)
    cmd-strip :=
endif

include $(BUILD_SHARED_LIBRARY)

$(call import-module,prefab/oboe)
