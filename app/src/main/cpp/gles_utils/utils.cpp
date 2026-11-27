//
// Created by woesss on 11.07.2020.
//

#include "utils.h"
#include <android/bitmap.h>
#include <cstdint>
#include <cstdlib>
#include <memory>

#ifdef __cplusplus
extern "C" {
#endif

static void blit(JNIEnv *env, jint x, jint y, jint width, jint height, jobject bitmap_buffer,
          unsigned int (*readPixels)(int, int, int, int, void *)) {
    int ret;
    AndroidBitmapInfo info;
    ret = AndroidBitmap_getInfo(env, bitmap_buffer, &info);
    if (ret < 0) {
        LOGE("AndroidBitmap_getInfo() failed! error=%d", ret)
        GLES_UTILS_RAISE_EXCEPTION(env, "java/lang/IllegalStateException",
                                   "AndroidBitmap_getInfo() failed!")
        return;
    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888
            || info.stride < static_cast<uint64_t>(info.width) * 4u
            || x < 0 || y < 0 || width < 0 || height < 0
            || static_cast<uint64_t>(x) + width > info.width
            || static_cast<uint64_t>(y) + height > info.height) {
        GLES_UTILS_RAISE_EXCEPTION(env, "java/lang/IllegalArgumentException",
                                  "GLES readback outside RGBA8888 Bitmap bounds")
        return;
    }
    if (width == 0 || height == 0) return;

    const uint64_t bytes = static_cast<uint64_t>(width) * height * 4u;
    if (bytes > SIZE_MAX) {
        GLES_UTILS_RAISE_EXCEPTION(env, "java/lang/OutOfMemoryError", "GLES readback too large")
        return;
    }
    // Allocate before pinning the bitmap; no shared scratch storage across contexts.
    std::unique_ptr<uint8_t, decltype(&std::free)> buf(
            static_cast<uint8_t *>(std::malloc(static_cast<size_t>(bytes))), &std::free);
    if (!buf) {
        GLES_UTILS_RAISE_EXCEPTION(env, "java/lang/OutOfMemoryError", "GLES readback allocation failed")
        return;
    }
    uint8_t *data = buf.get();
    ret = readPixels(x, y, width, height, data);
    if (ret != 0) {
        LOGE("glReadPixels() failed! error=%d", ret)
        return;
    }

    void *pixels;
    ret = AndroidBitmap_lockPixels(env, bitmap_buffer, &pixels);
    if (ret < 0) {
        LOGE("AndroidBitmap_lockPixels() failed! error=%d", ret)
        GLES_UTILS_RAISE_EXCEPTION(env, "java/lang/IllegalStateException",
                                   "AndroidBitmap_lockPixels() failed!")
        return;
    }

    const uint32_t bs = info.stride;
    for (int i = height - 1; i >= 0; --i) {
        const uint8_t *src = &data[static_cast<size_t>(i) * width * 4u];
        uint8_t *dst = static_cast<uint8_t *>(pixels)
                + static_cast<size_t>(y + height - 1 - i) * bs + static_cast<size_t>(x) * 4u;
        for (int j = 0; j < width; ++j) {
            uint32_t r = *src++;
            uint32_t g = *src++;
            uint32_t b = *src++;
            uint32_t a = *src++;
            uint32_t tmp = (a * r + (255 - a) * *dst) / 255;
            *dst++ = tmp > 255 ? 255 : tmp;
            tmp = (a * g + (255 - a) * *dst) / 255;
            *dst++ = tmp > 255 ? 255 : tmp;
            tmp = (a * b + (255 - a) * *dst) / 255;
            *dst++ = tmp > 255 ? 255 : tmp;
            *dst++ = 255;
        }
    }
    ret = AndroidBitmap_unlockPixels(env, bitmap_buffer);
    if (ret < 0) {
        LOGE("AndroidBitmap_unlockPixels() failed! error=%d", ret)
        GLES_UTILS_RAISE_EXCEPTION(env, "java/lang/IllegalStateException",
                                   "AndroidBitmap_unlockPixels() failed!")
    }
}

JNIEXPORT void JNICALL Java_io_github_h3nb_jlmodplus_gles_GLESUtils_blit
        (JNIEnv *env, jclass /*clazz*/,
         jint x, jint y, jint width, jint height, jobject bitmap_buffer) {
    blit(env, x, y, width, height, bitmap_buffer, GLES1_glReadPixels);
}

JNIEXPORT void JNICALL Java_io_github_h3nb_jlmodplus_gles_GLESUtils_blit2
        (JNIEnv *env, jclass /*clazz*/,
         jint x, jint y, jint width, jint height, jobject bitmap_buffer) {
    blit(env, x, y, width, height, bitmap_buffer, GLES2_glReadPixels);
}

#ifdef __cplusplus
}
#endif
