//
// Created by woesss on 11.07.2020.
//

#include "utils.h"
#include <android/bitmap.h>
#include <vector>

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
    void *pixels;
    ret = AndroidBitmap_lockPixels(env, bitmap_buffer, &pixels);
    if (ret < 0) {
        LOGE("AndroidBitmap_lockPixels() failed! error=%d", ret)
        GLES_UTILS_RAISE_EXCEPTION(env, "java/lang/IllegalStateException",
                                   "AndroidBitmap_lockPixels() failed!")
        return;
    }

    static std::vector<uint8_t> buf;
    if (buf.capacity() < width * height * 4) {
        buf.resize(width * height * 4, 0);
    }
    uint8_t *data = buf.data();
    ret = readPixels(x, y, width, height, data);

    if (ret == 0) {
        const uint32_t bs = info.stride;
        pixels = ((uint8_t *) pixels) + x * 4 /*RGBA*/ + bs * y;
        for (int i = height - 1; i >= 0; --i) {
            uint8_t *src = &data[i * width * 4];
            uint8_t *dst = static_cast<uint8_t *>(pixels);
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
            pixels = ((uint8_t *) pixels) + bs;
        }
    } else {
        LOGE("glReadPixels() failed! error=%d", ret)
    }
    ret = AndroidBitmap_unlockPixels(env, bitmap_buffer);
    if (ret < 0) {
        LOGE("AndroidBitmap_unlockPixels() failed! error=%d", ret)
        GLES_UTILS_RAISE_EXCEPTION(env, "java/lang/IllegalStateException",
                                   "AndroidBitmap_unlockPixels() failed!")
    }
}

JNIEXPORT void JNICALL Java_ru_woesss_gles_GLESUtils_blit
        (JNIEnv *env, jclass /*clazz*/,
         jint x, jint y, jint width, jint height, jobject bitmap_buffer) {
    blit(env, x, y, width, height, bitmap_buffer, GLES1_glReadPixels);
}

JNIEXPORT void JNICALL Java_ru_woesss_gles_GLESUtils_blit2
        (JNIEnv *env, jclass /*clazz*/,
         jint x, jint y, jint width, jint height, jobject bitmap_buffer) {
    blit(env, x, y, width, height, bitmap_buffer, GLES2_glReadPixels);
}

#ifdef __cplusplus
}
#endif
