# SPDX-License-Identifier: Apache-2.0
LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := mmapi_eas
LOCAL_SRC_FILES := eas_player.cpp eas_player_jni.cpp eas_file.cpp eas_host.c
LOCAL_CFLAGS := -O2 -fvisibility=hidden
LOCAL_CPPFLAGS := -std=c++17 -fexceptions
LOCAL_C_INCLUDES := $(LOCAL_PATH)
LOCAL_STATIC_LIBRARIES := sonivox
LOCAL_SHARED_LIBRARIES := oboe
LOCAL_LDLIBS := -llog -landroid
include $(BUILD_SHARED_LIBRARY)
$(call import-module,prefab/oboe)
