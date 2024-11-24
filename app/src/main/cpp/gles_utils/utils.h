#pragma once

#include <jni.h>
#include <android/log.h>

#ifdef __cplusplus
extern "C" {
#endif

unsigned int GLES1_glReadPixels(int, int, int, int, void *);
unsigned int GLES2_glReadPixels(int, int, int, int, void *);

JNIEXPORT void JNICALL Java_ru_woesss_gles_GLESUtils_blit
        (JNIEnv *, jclass, jint, jint, jint, jint, jobject);

JNIEXPORT void JNICALL Java_ru_woesss_gles_GLESUtils_blit2
        (JNIEnv *, jclass, jint, jint, jint, jint, jobject);

#define  LOG_TAG    "GLES_Utils"
#define  LOGE(...)  __android_log_print(ANDROID_LOG_ERROR,LOG_TAG,__VA_ARGS__);
#define GLES_UTILS_RAISE_EXCEPTION(aEnv, aException, aMsg){\
                 if (aEnv != NULL){\
                jclass jException = aEnv->FindClass(aException);\
                if (jException != NULL){\
                    aEnv->ThrowNew(jException, aMsg);\
         }}}\

#ifdef __cplusplus
}
#endif
