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
JL_MEM_PRODUCTION_SRC := memory_engine_production.cpp mutation_bridge.cpp result_store.cpp \
    result_store_scan.cpp result_store_refine.cpp result_store_relative.cpp result_cursor.cpp \
    result_alias_cursor.cpp ordinary_result_store.cpp
ifeq ($(NDK_DEBUG),1)
LOCAL_SRC_FILES := $(JL_MEM_PRODUCTION_SRC) result_store_shadow_bridge.cpp result_store_auto_probe.cpp \
    ordinary_result_store_probe.cpp
else
LOCAL_SRC_FILES := $(JL_MEM_PRODUCTION_SRC) memory_engine_compat_bridge.cpp
endif
LOCAL_CPPFLAGS := -std=c++23 -Wall -Wextra -Werror -Wpedantic -Wformat=2 -Wimplicit-fallthrough
LOCAL_CPP_FEATURES := exceptions
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)
