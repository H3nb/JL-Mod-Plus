/* SPDX-License-Identifier: Apache-2.0 */
/* JL-Mod Plus memory-only Sonivox host. Locators remain owned by Player;
 * duplicated handles only copy a cursor. No FILE or Java reference is retained. */
#include "eas_host.h"
#include "eas_report.h"
#include <limits.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define HOST_FILES 128
struct eas_hw_file_tag {
    EAS_FILE locator;
    int position;
    int used;
};
/* The core has parser failure paths whose temporary blocks are not published
 * back to EAS_DATA. Host ownership makes all such blocks deterministic at
 * shutdown. Allocation and list mutation occur only with rendering quiesced. */
struct allocation { struct allocation *previous, *next; max_align_t alignment; };
struct eas_hw_inst_data_tag {
    struct eas_hw_file_tag files[HOST_FILES];
    struct allocation *allocations;
};
static _Thread_local int realtime;
static _Thread_local int realtimeAllocations;
void JL_EAS_Realtime(int active) { realtime = active; if (active) realtimeAllocations = 0; }
int JL_EAS_RealtimeAllocations(void) { return realtimeAllocations; }

#ifdef JL_EAS_TEST_ALLOCATOR
/* Standalone fault tests only; no shipping JNI surface or mutable test policy. */
static int failAfter = -1, outstanding;
void JL_EAS_TestFailAfter(int count) { failAfter = count; }
int JL_EAS_TestOutstanding(void) { return outstanding; }
static int failAllocation(void) {
    if (failAfter < 0) return 0;
    if (failAfter == 0) return 1;
    --failAfter; return 0;
}
#endif

EAS_RESULT EAS_HWInit(EAS_HW_DATA_HANDLE *out) {
#ifdef JL_EAS_TEST_ALLOCATOR
    if (failAllocation()) { *out = NULL; return EAS_ERROR_MALLOC_FAILED; }
#endif
    *out = calloc(1, sizeof(struct eas_hw_inst_data_tag));
#ifdef JL_EAS_TEST_ALLOCATOR
    if (*out) ++outstanding;
#endif
    return *out ? EAS_SUCCESS : EAS_ERROR_MALLOC_FAILED;
}
EAS_RESULT EAS_HWShutdown(EAS_HW_DATA_HANDLE host) {
    if (host) {
        while (host->allocations) {
            struct allocation *block = host->allocations;
            host->allocations = block->next;
#ifdef JL_EAS_TEST_ALLOCATOR
            --outstanding;
#endif
            free(block);
        }
    }
#ifdef JL_EAS_TEST_ALLOCATOR
    if (host) --outstanding;
#endif
    free(host); return EAS_SUCCESS;
}
void *EAS_HWMalloc(EAS_HW_DATA_HANDLE host, EAS_I32 size) {
    if (realtime) ++realtimeAllocations;
    if (realtime || !host || size <= 0 || (size_t)size > SIZE_MAX - sizeof(struct allocation)) return NULL;
#ifdef JL_EAS_TEST_ALLOCATOR
    if (failAllocation()) return NULL;
#endif
    struct allocation *block = malloc(sizeof(*block) + (size_t)size);
    if (!block) return NULL;
    block->previous = NULL;
    block->next = host->allocations;
    if (block->next) block->next->previous = block;
    host->allocations = block;
#ifdef JL_EAS_TEST_ALLOCATOR
    ++outstanding;
#endif
    return block + 1;
}
void EAS_HWFree(EAS_HW_DATA_HANDLE host, void *data) {
    if (!data) return;
    struct allocation *block = (struct allocation *)data - 1;
    if (block->previous) block->previous->next = block->next;
    else host->allocations = block->next;
    if (block->next) block->next->previous = block->previous;
#ifdef JL_EAS_TEST_ALLOCATOR
    --outstanding;
#endif
    free(block);
}
void *EAS_HWMemCpy(void *dst, const void *src, EAS_I32 n) { return memcpy(dst, src, (size_t)n); }
void *EAS_HWMemSet(void *dst, int value, EAS_I32 n) { return memset(dst, value, (size_t)n); }
EAS_I32 EAS_HWMemCmp(const void *a, const void *b, EAS_I32 n) { return memcmp(a, b, (size_t)n); }

EAS_RESULT EAS_HWOpenFile(EAS_HW_DATA_HANDLE host, EAS_FILE_LOCATOR locator,
        EAS_FILE_HANDLE *out, EAS_FILE_MODE mode) {
    if (!host || !locator || !out || !locator->handle || !locator->readAt || !locator->size)
        return EAS_ERROR_INVALID_PARAMETER;
    *out = NULL;
    if (mode != EAS_FILE_READ) return EAS_ERROR_INVALID_FILE_MODE;
    for (int i = 0; i < HOST_FILES; ++i) {
        struct eas_hw_file_tag *file = &host->files[i];
        if (!file->used) {
            file->locator = *locator; file->position = 0; file->used = 1;
            *out = file; return EAS_SUCCESS;
        }
    }
    return EAS_ERROR_MAX_FILES_OPEN;
}
EAS_RESULT EAS_HWReadFile(EAS_HW_DATA_HANDLE host, EAS_FILE_HANDLE file, void *buffer,
        EAS_I32 length, EAS_I32 *read) {
    if (read) *read = 0;
    if (!file || !file->used) return EAS_ERROR_INVALID_HANDLE;
    if (length < 0 || (!buffer && length)) return EAS_ERROR_INVALID_PARAMETER;
    int count = file->locator.readAt(file->locator.handle, buffer, file->position, length);
    if (count < 0 || count > length || count > INT_MAX - file->position)
        return EAS_ERROR_FILE_READ_FAILED;
    file->position += count;
    if (read) *read = count;
    return count == length ? EAS_SUCCESS : EAS_EOF;
}
EAS_RESULT EAS_HWGetByte(EAS_HW_DATA_HANDLE host, EAS_FILE_HANDLE file, void *value) {
    return EAS_HWReadFile(host, file, value, 1, NULL);
}
EAS_RESULT EAS_HWGetWord(EAS_HW_DATA_HANDLE host, EAS_FILE_HANDLE file, void *value, EAS_BOOL msb) {
    unsigned char bytes[2] = {0};
    EAS_RESULT r = EAS_HWReadFile(host, file, bytes, 2, NULL);
    *(EAS_U16 *)value = msb ? ((EAS_U16)bytes[0] << 8) | bytes[1] : ((EAS_U16)bytes[1] << 8) | bytes[0];
    return r;
}
EAS_RESULT EAS_HWGetDWord(EAS_HW_DATA_HANDLE host, EAS_FILE_HANDLE file, void *value, EAS_BOOL msb) {
    unsigned char b[4] = {0};
    EAS_RESULT r = EAS_HWReadFile(host, file, b, 4, NULL);
    *(EAS_U32 *)value = msb ? ((EAS_U32)b[0] << 24) | ((EAS_U32)b[1] << 16) | ((EAS_U32)b[2] << 8) | b[3]
        : ((EAS_U32)b[3] << 24) | ((EAS_U32)b[2] << 16) | ((EAS_U32)b[1] << 8) | b[0];
    return r;
}
EAS_RESULT EAS_HWFilePos(EAS_HW_DATA_HANDLE host, EAS_FILE_HANDLE file, EAS_I32 *position) {
    if (!file || !file->used || !position) return EAS_ERROR_INVALID_HANDLE;
    *position = file->position; return EAS_SUCCESS;
}
EAS_RESULT EAS_HWFileLength(EAS_HW_DATA_HANDLE host, EAS_FILE_HANDLE file, EAS_I32 *length) {
    if (!file || !file->used || !length) return EAS_ERROR_INVALID_HANDLE;
    *length = file->locator.size(file->locator.handle);
    return *length >= 0 ? EAS_SUCCESS : EAS_ERROR_FILE_LENGTH;
}
EAS_RESULT EAS_HWFileSeek(EAS_HW_DATA_HANDLE host, EAS_FILE_HANDLE file, EAS_I32 position) {
    if (!file || !file->used) return EAS_ERROR_INVALID_HANDLE;
    if (position < 0 || position > file->locator.size(file->locator.handle)) return EAS_ERROR_FILE_SEEK;
    file->position = position; return EAS_SUCCESS;
}
EAS_RESULT EAS_HWFileSeekOfs(EAS_HW_DATA_HANDLE host, EAS_FILE_HANDLE file, EAS_I32 delta) {
    if (!file || !file->used) return EAS_ERROR_INVALID_HANDLE;
    int64_t next = (int64_t)file->position + delta;
    if (next < 0 || next > INT_MAX) return EAS_ERROR_FILE_SEEK;
    return EAS_HWFileSeek(host, file, (EAS_I32)next);
}
EAS_RESULT EAS_HWDupHandle(EAS_HW_DATA_HANDLE host, EAS_FILE_HANDLE file, EAS_FILE_HANDLE *out) {
    if (!file || !file->used) return EAS_ERROR_INVALID_HANDLE;
    EAS_RESULT r = EAS_HWOpenFile(host, &file->locator, out, EAS_FILE_READ);
    if (r == EAS_SUCCESS) (*out)->position = file->position;
    return r;
}
EAS_RESULT EAS_HWCloseFile(EAS_HW_DATA_HANDLE host, EAS_FILE_HANDLE file) {
    if (!file || !file->used) return EAS_ERROR_INVALID_HANDLE;
    memset(file, 0, sizeof(*file)); return EAS_SUCCESS;
}
EAS_RESULT EAS_HWVibrate(EAS_HW_DATA_HANDLE host, EAS_BOOL state) { return EAS_SUCCESS; }
EAS_RESULT EAS_HWLED(EAS_HW_DATA_HANDLE host, EAS_BOOL state) { return EAS_SUCCESS; }
EAS_RESULT EAS_HWBackLight(EAS_HW_DATA_HANDLE host, EAS_BOOL state) { return EAS_SUCCESS; }
EAS_BOOL EAS_HWYield(EAS_HW_DATA_HANDLE host) { return EAS_FALSE; }
void *EAS_HWRegisterSignalHandler(void) { return NULL; }
EAS_RESULT EAS_HWUnRegisterSignalHandler(void *cookie) { return EAS_SUCCESS; }
/* EAS reporting can occur during render. The production host keeps it silent;
 * management/JNI errors carry the checked EAS_RESULT to Java. */
void EAS_ReportEx(int severity, unsigned long hash, int serial, ...) {}
void EAS_Report(int severity, const char *format, ...) {}
void EAS_ReportX(int severity, const char *format, ...) {}
void EAS_SetDebugLevel(int severity) {}
void EAS_SetDebugFile(void *file, int flush) {}
