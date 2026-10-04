# SPDX-License-Identifier: Apache-2.0
LOCAL_PATH := $(call my-dir)
ifndef JLMOD_AUDIO_DEPS
$(error JLMOD_AUDIO_DEPS must point to the pinned native audio dependency build)
endif
JLMOD_AUDIO_PREFIX := $(JLMOD_AUDIO_DEPS)/install-$(TARGET_ARCH_ABI)
JLMOD_AUDIO_SUFFIX := $(if $(filter armeabi-v7a,$(TARGET_ARCH_ABI)),_neon,)
define jlmod_audio_prebuilt
include $$(CLEAR_VARS)
LOCAL_MODULE := jlmod_$(1)
LOCAL_MODULE_FILENAME := lib$(1)$$(JLMOD_AUDIO_SUFFIX)
LOCAL_SRC_FILES := $$(JLMOD_AUDIO_PREFIX)/lib/lib$(1)$$(JLMOD_AUDIO_SUFFIX).so
LOCAL_EXPORT_C_INCLUDES := $$(JLMOD_AUDIO_PREFIX)/include
include $$(PREBUILT_SHARED_LIBRARY)
endef
$(foreach library,avformat avcodec avutil swresample avdevice avfilter swscale,$(eval $(call jlmod_audio_prebuilt,$(library))))
