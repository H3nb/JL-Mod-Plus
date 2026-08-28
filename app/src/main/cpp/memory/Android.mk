LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := jlmem_target
LOCAL_SRC_FILES := target_probe.cpp
LOCAL_CPPFLAGS := -std=c++17 -Wall -Wextra -Werror
LOCAL_CPP_FEATURES := exceptions
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := jlmem
LOCAL_SRC_FILES := memory_engine.cpp
LOCAL_CPPFLAGS := -std=c++17 -Wall -Wextra -Werror
LOCAL_CPP_FEATURES := exceptions
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)
