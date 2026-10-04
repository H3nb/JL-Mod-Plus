# Copyright (C) 2009 The Android Open Source Project
# Modified for JL-Mod Plus.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := sonivox
LOCAL_SRC_FILES := host_src/eas_config.c \
    lib_src/eas_chorus.c lib_src/eas_dlssynth.c lib_src/eas_flog.c \
    lib_src/eas_math.c lib_src/eas_mdls.c lib_src/eas_midi.c \
    lib_src/eas_mixbuf.c lib_src/eas_mixer.c lib_src/eas_pan.c \
    lib_src/eas_pcm.c lib_src/eas_public.c lib_src/eas_reverb.c \
    lib_src/eas_smf.c lib_src/eas_tonecontrol.c lib_src/eas_voicemgt.c \
    lib_src/eas_xmf.c lib_src/eas_sndlibmgt.c lib_src/eas_wtengine.c \
    lib_src/eas_wtsynth.c lib_src/wt_200k_G.c lib_src/eas_sf2.c \
    lib_src/eas_filter_float.c lib_src/eas_imelody.c lib_src/eas_ota.c \
    lib_src/eas_rtttl.c lib_src/eas_imaadpcm.c lib_src/eas_ima_tables.c
LOCAL_CFLAGS := -O2 -fvisibility=hidden -Wno-unused-parameter
LOCAL_C_INCLUDES := $(LOCAL_PATH)/host_src $(LOCAL_PATH)/lib_src $(LOCAL_PATH)/fakes
LOCAL_EXPORT_C_INCLUDES := $(LOCAL_C_INCLUDES)
include $(BUILD_STATIC_LIBRARY)
