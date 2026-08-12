LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE    := javam3g
# Warning-zero is part of the supported ARM64 M3G contract; fail on regressions.
LOCAL_CFLAGS    := -O3 -Werror -DM3G_TARGET_ANDROID #-DM3G_DEBUG -DM3G_GL_ES_1_1
LOCAL_CXXFLAGS  := $(LOCAL_CFLAGS)

# Hosted M3G runtime characterization uses x86_64 only. Enable the inherited
# M3G render log and GL assertions there so a renderer failure is localized
# without changing the supported arm64-v8a production build. The inherited
# debug alignment assertion narrows pointers before masking their low bits;
# tolerate that diagnostic-only warning here so the assertion can run.
ifeq ($(TARGET_ARCH_ABI),x86_64)
    LOCAL_CFLAGS   += -DM3G_DEBUG -Wno-pointer-to-int-cast
    LOCAL_CXXFLAGS += -DM3G_DEBUG -Wno-pointer-to-int-cast
endif

LOCAL_LDLIBS    := -llog -lEGL -lGLESv1_CM -lz -ljnigraphics
LOCAL_C_INCLUDES := $(LOCAL_PATH)/inc/
LOCAL_SRC_FILES := \
	CSynchronization.cpp \
	m3g_android_java_api.cpp \
	src/m3g_core.c \
	src/m3g_android.cpp \
	src/m3g_android_gl.cpp

ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
    LOCAL_ARM_NEON := false
endif

# Don't strip debug builds
ifeq ($(NDK_DEBUG),1)
    cmd-strip :=
endif

include $(BUILD_SHARED_LIBRARY)
