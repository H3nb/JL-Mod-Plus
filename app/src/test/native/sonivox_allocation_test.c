/* SPDX-License-Identifier: Apache-2.0 */
#include "eas.h"
#include "eas_host.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

extern void JL_EAS_TestFailAfter(int count);
extern int JL_EAS_TestOutstanding(void);
extern void JL_EAS_Realtime(int active);
extern int JL_EAS_RealtimeAllocations(void);

struct source { unsigned char *bytes; int length; };
static int readAt(void *handle, void *buffer, int offset, int count) {
    struct source *source = handle;
    if (offset < 0 || offset > source->length || count < 0) return -1;
    if (count > source->length - offset) count = source->length - offset;
    memcpy(buffer, source->bytes + offset, (size_t)count);
    return count;
}
static int length(void *handle) { return ((struct source *)handle)->length; }

static void testInit(void) {
    for (int failure = 0; failure < 128; ++failure) {
        EAS_DATA_HANDLE context = NULL;
        JL_EAS_TestFailAfter(failure);
        EAS_RESULT result = EAS_Init(&context);
        JL_EAS_TestFailAfter(-1);
        if (result == EAS_SUCCESS) {
            assert(context);
            assert(EAS_Shutdown(context) == EAS_SUCCESS);
            assert(JL_EAS_TestOutstanding() == 0);
            printf("init: %d allocation failure points reclaimed\n", failure);
            return;
        }
        assert(result == EAS_ERROR_MALLOC_FAILED);
        assert(!context);
        assert(JL_EAS_TestOutstanding() == 0);
    }
    assert(!"init allocation sweep did not reach success");
}

static void testMidi(void) {
    for (int failure = 0; failure < 128; ++failure) {
        EAS_DATA_HANDLE context = NULL;
        EAS_HANDLE stream = NULL;
        assert(EAS_Init(&context) == EAS_SUCCESS);
        JL_EAS_TestFailAfter(failure);
        EAS_RESULT result = EAS_OpenMIDIStream(context, &stream, NULL);
        JL_EAS_TestFailAfter(-1);
        if (result == EAS_SUCCESS) {
            unsigned char note[] = { 0x90, 60, 100 };
            EAS_PCM samples[512];
            EAS_I32 rendered;
            assert(EAS_WriteMIDIStream(context, stream, note, sizeof(note)) == EAS_SUCCESS);
            JL_EAS_Realtime(1);
            assert(EAS_Render(context, samples, 256, &rendered) == EAS_SUCCESS);
            assert(JL_EAS_RealtimeAllocations() == 0);
            JL_EAS_Realtime(0);
            assert(rendered == 256);
            assert(EAS_CloseMIDIStream(context, stream) == EAS_SUCCESS);
        } else assert(result == EAS_ERROR_MALLOC_FAILED);
        assert(EAS_Shutdown(context) == EAS_SUCCESS);
        assert(JL_EAS_TestOutstanding() == 0);
        if (result == EAS_SUCCESS) {
            printf("MIDI: %d allocation failure points reclaimed; render allocations 0\n", failure);
            return;
        }
    }
    assert(!"MIDI allocation sweep did not reach success");
}

static void testBank(const char *path) {
    FILE *file = fopen(path, "rb");
    assert(file);
    assert(fseek(file, 0, SEEK_END) == 0);
    long size = ftell(file);
    assert(size > 7 && size <= 128 * 1024 * 1024);
    rewind(file);
    struct source source = { malloc((size_t)size), (int)size };
    assert(source.bytes);
    assert(fread(source.bytes, 1, (size_t)size, file) == (size_t)size);
    fclose(file);
    EAS_FILE locator = { &source, readAt, length };
    int succeeded = 0;
    for (int failure = 0; failure < 512; ++failure) {
        EAS_DATA_HANDLE context = NULL;
        assert(EAS_Init(&context) == EAS_SUCCESS);
        JL_EAS_TestFailAfter(failure);
        EAS_RESULT result = EAS_LoadDLSCollection(context, NULL, &locator);
        JL_EAS_TestFailAfter(-1);
        assert(EAS_Shutdown(context) == EAS_SUCCESS);
        assert(JL_EAS_TestOutstanding() == 0);
        if (result == EAS_SUCCESS) {
            printf("bank %s: %d allocation failure points reclaimed\n", path, failure);
            succeeded = 1;
            break;
        }
    }
    assert(succeeded);
    /* Truncation must fail and still release parser-owned temporary blocks. */
    source.length = 16;
    EAS_DATA_HANDLE context = NULL;
    assert(EAS_Init(&context) == EAS_SUCCESS);
    assert(EAS_LoadDLSCollection(context, NULL, &locator) != EAS_SUCCESS);
    assert(EAS_Shutdown(context) == EAS_SUCCESS);
    assert(JL_EAS_TestOutstanding() == 0);
    free(source.bytes);
}

int main(int argc, char **argv) {
    testInit();
    testMidi();
    for (int i = 1; i < argc; ++i) testBank(argv[i]);
    puts("PASS: all context allocations reclaimed after injected failures");
    return 0;
}
