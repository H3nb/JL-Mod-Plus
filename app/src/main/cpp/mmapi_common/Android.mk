# Copyright 2026 H3NB
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := mmapi_common

LOCAL_SRC_FILES = \
	src/PlayerListener.cpp \
	src/BasePlayer.cpp \
	src/jstring.cpp \
	src/jbytearray.cpp \

LOCAL_CFLAGS += -O2 \

LOCAL_C_INCLUDES := \
	$(LOCAL_PATH)/include/mmapi \
	$(LOCAL_PATH)/include/util \

LOCAL_LDLIBS := -llog

# Keep the audio JNI library ready for 16 KB page-size devices when an older
# NDK is used by a downstream build. NDK r28+ applies these flags by default.
LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384

LOCAL_SHARED_LIBRARIES := oboe

LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)/include
LOCAL_EXPORT_LDLIBS := $(LOCAL_LDLIBS)
LOCAL_EXPORT_SHARED_LIBRARIES := $(LOCAL_SHARED_LIBRARIES)

ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
    LOCAL_ARM_NEON := false
endif

# Don't strip debug builds
ifeq ($(NDK_DEBUG),1)
    cmd-strip :=
endif

include $(BUILD_SHARED_LIBRARY)

$(call import-module,prefab/oboe)
