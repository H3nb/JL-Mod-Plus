LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := jlmem
LOCAL_SRC_FILES := memory_scan.cpp memory_scan_jni_aliases.cpp live_tracker.cpp
LOCAL_CPP_FEATURES := exceptions
LOCAL_CPPFLAGS += -std=c++17 -Wall -Wextra -include cstdio -include cstdlib -include new
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := jlprobe
LOCAL_SRC_FILES := target_probe.cpp
LOCAL_CPPFLAGS += -std=c++17 -Wall -Wextra
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := jlremote
LOCAL_SRC_FILES := remote_engine.cpp
LOCAL_CPP_FEATURES := exceptions
LOCAL_CPPFLAGS += -std=c++17 -Wall -Wextra -include cstdio -include cstdlib
include $(BUILD_SHARED_LIBRARY)
