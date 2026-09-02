LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := jlmem_target
LOCAL_SRC_FILES := target_probe.cpp
LOCAL_CPPFLAGS := -std=c++23 -Wall -Wextra -Werror -Wpedantic -Wformat=2 -Wimplicit-fallthrough
LOCAL_CPP_FEATURES := exceptions
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := jlmem
LOCAL_SRC_FILES := memory_engine.cpp mutation_bridge.cpp result_store.cpp result_store_scan.cpp result_store_shadow_bridge.cpp result_cursor.cpp
LOCAL_CPPFLAGS := -std=c++23 -Wall -Wextra -Werror -Wpedantic -Wformat=2 -Wimplicit-fallthrough
LOCAL_CPP_FEATURES := exceptions
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)
