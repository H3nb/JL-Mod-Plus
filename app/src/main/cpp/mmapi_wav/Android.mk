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

LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := mmapi_wav

LOCAL_SRC_FILES := \
	wav_player.cpp \
	wav_player_jni.cpp \
	gsm610_decoder.cpp \
	../libgsm/src/add.c \
	../libgsm/src/decode.c \
	../libgsm/src/gsm_create.c \
	../libgsm/src/gsm_decode.c \
	../libgsm/src/gsm_destroy.c \
	../libgsm/src/gsm_option.c \
	../libgsm/src/long_term.c \
	../libgsm/src/rpe.c \
	../libgsm/src/short_term.c \
	../libgsm/src/table.c

LOCAL_CFLAGS += -O2 -DSASR -DWAV49 -Wno-comment -Wno-unused-parameter
LOCAL_CPPFLAGS += -std=c++17

LOCAL_C_INCLUDES := \
	$(LOCAL_PATH) \
	$(LOCAL_PATH)/../dr_libs \
	$(LOCAL_PATH)/../libgsm/inc

LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384

LOCAL_SHARED_LIBRARIES := mmapi_common

# Don't strip debug builds.
ifeq ($(NDK_DEBUG),1)
    cmd-strip :=
endif

include $(BUILD_SHARED_LIBRARY)

$(call import-module,prefab/oboe)
