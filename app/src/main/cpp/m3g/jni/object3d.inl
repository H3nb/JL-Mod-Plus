/*
* Copyright (c) 2009 Nokia Corporation and/or its subsidiary(-ies).
* All rights reserved.
* This component and the accompanying materials are made available
* under the terms of "Eclipse Public License v1.0"
* which accompanies this distribution, and is available
* at the URL "http://www.eclipse.org/legal/epl-v10.html".
*
* Initial Contributors:
* Nokia Corporation - initial contribution.
*
* Contributors:
*
* Description:
*
*/

#include "javax_microedition_m3g_Object3D.h"

#include <stdlib.h>

static M3Gulong* m3gAllocNativeReferences(JNIEnv* aEnv,
                                          jlongArray aReferences,
                                          jlong** javaReferences,
                                          jsize* length)
{
    *javaReferences = NULL;
    *length = aReferences ? aEnv->GetArrayLength(aReferences) : 0;
    if (aReferences == NULL || *length == 0) {
        return NULL;
    }

    *javaReferences = aEnv->GetLongArrayElements(aReferences, NULL);
    if (*javaReferences == NULL) {
        M3G_RAISE_EXCEPTION(aEnv, "java/lang/OutOfMemoryError");
        return NULL;
    }

    M3Gulong* nativeReferences =
        (M3Gulong*)calloc((size_t)*length, sizeof(M3Gulong));
    if (nativeReferences == NULL) {
        aEnv->ReleaseLongArrayElements(aReferences, *javaReferences, JNI_ABORT);
        *javaReferences = NULL;
        M3G_RAISE_EXCEPTION(aEnv, "java/lang/OutOfMemoryError");
    }
    return nativeReferences;
}

static void m3gCopyNativeReferences(jlong* javaReferences,
                                    const M3Gulong* nativeReferences,
                                    jsize length)
{
    for (jsize i = 0; i < length; ++i) {
        javaReferences[i] = (jlong)nativeReferences[i];
    }
}

JNIEXPORT jint JNICALL Java_javax_microedition_m3g_Object3D__1animate
(JNIEnv* aEnv, jclass, jlong aHObject, jint aTime)
{
    M3G_DO_LOCK
    jint anim = (jint)m3gAnimate((M3GObject)aHObject, aTime);
    M3G_DO_UNLOCK(aEnv)
    return anim;
}

JNIEXPORT jlong JNICALL Java_javax_microedition_m3g_Object3D__1getAnimationTrack
(JNIEnv* aEnv, jclass, jlong aHObject, jint aIndex)
{
    M3G_DO_LOCK
    jlong handle = (jlong)m3gGetAnimationTrack((M3GObject)aHObject, aIndex);
    M3G_DO_UNLOCK(aEnv)
    return handle;
}

JNIEXPORT jlong JNICALL Java_javax_microedition_m3g_Object3D__1find
(JNIEnv* aEnv, jclass, jlong aHObject, jint aUserID)
{
    M3G_DO_LOCK
    jlong target = (jlong)m3gFind((M3GObject)aHObject, aUserID);
    M3G_DO_UNLOCK(aEnv)
    return target;
}

JNIEXPORT jint JNICALL Java_javax_microedition_m3g_Object3D__1getUserID
(JNIEnv* aEnv, jclass, jlong aHObject)
{
    M3G_DO_LOCK
    jint id = (jint)m3gGetUserID((M3GObject)aHObject);
    M3G_DO_UNLOCK(aEnv)
    return id;
}

JNIEXPORT jint JNICALL Java_javax_microedition_m3g_Object3D__1addAnimationTrack
(JNIEnv* aEnv, jclass, jlong aHObject, jlong aHTrack)
{
    M3G_DO_LOCK
    jint ret = (jint)m3gAddAnimationTrack((M3GObject)aHObject, (M3GAnimationTrack)aHTrack);
    M3G_DO_UNLOCK(aEnv)
    return ret;
}

JNIEXPORT jint JNICALL Java_javax_microedition_m3g_Object3D__1getAnimationTrackCount
(JNIEnv* aEnv, jclass, jlong aHObject)
{
    M3G_DO_LOCK
    jint count = (jint)m3gGetAnimationTrackCount((M3GObject)aHObject);
    M3G_DO_UNLOCK(aEnv)
    return count;
}

JNIEXPORT void JNICALL Java_javax_microedition_m3g_Object3D__1removeAnimationTrack
(JNIEnv* aEnv, jclass, jlong aHObject, jlong aHTrack)
{
    M3G_DO_LOCK
    m3gRemoveAnimationTrack((M3GObject)aHObject, (M3GAnimationTrack)aHTrack);
    M3G_DO_UNLOCK(aEnv)
}

JNIEXPORT void JNICALL Java_javax_microedition_m3g_Object3D__1setUserID
(JNIEnv* aEnv, jclass, jlong aHObject, jint aUserID)
{
    M3G_DO_LOCK
    m3gSetUserID((M3GObject)aHObject, aUserID);
    M3G_DO_UNLOCK(aEnv)
}

JNIEXPORT void JNICALL Java_javax_microedition_m3g_Object3D__1addRef
(JNIEnv* aEnv, jclass, jlong aObject)
{
    M3G_DO_LOCK
    m3gAddRef((M3GObject) aObject);
    M3G_DO_UNLOCK(aEnv)
}

JNIEXPORT jlong JNICALL Java_javax_microedition_m3g_Object3D__1duplicate
(JNIEnv* aEnv, jclass, jlong aHObject, jlongArray aHReferences)
{
    jlong* javaReferences = NULL;
    jsize referenceCount = 0;
    M3Gulong* nativeReferences =
        m3gAllocNativeReferences(aEnv, aHReferences,
                                 &javaReferences, &referenceCount);
    if (aHReferences != NULL && referenceCount > 0 && nativeReferences == NULL) {
        return 0;
    }

    M3G_DO_LOCK
    M3GObject duplicate = m3gDuplicate((M3GObject)aHObject, nativeReferences);
    M3G_DO_UNLOCK(aEnv)

    const M3Gbool copyBack = duplicate != NULL && !aEnv->ExceptionCheck();
    if (copyBack && javaReferences != NULL) {
        m3gCopyNativeReferences(javaReferences, nativeReferences, referenceCount);
    }

    free(nativeReferences);
    if (javaReferences != NULL) {
        aEnv->ReleaseLongArrayElements(aHReferences, javaReferences,
                                       copyBack ? 0 : JNI_ABORT);
    }
    return (jlong)duplicate;
}

JNIEXPORT jint JNICALL Java_javax_microedition_m3g_Object3D__1getReferences
(JNIEnv* aEnv, jclass, jlong aHObject, jlongArray aHReferences)
{
    jlong* javaReferences = NULL;
    jsize referenceCount = 0;
    M3Gulong* nativeReferences =
        m3gAllocNativeReferences(aEnv, aHReferences,
                                 &javaReferences, &referenceCount);
    if (aHReferences != NULL && referenceCount > 0 && nativeReferences == NULL) {
        return 0;
    }

    M3G_DO_LOCK
    jint ret = m3gGetReferences((M3GObject)aHObject,
                                 nativeReferences, referenceCount);
    M3G_DO_UNLOCK(aEnv)

    const M3Gbool copyBack = ret > 0 && !aEnv->ExceptionCheck();
    if (copyBack && javaReferences != NULL) {
        m3gCopyNativeReferences(javaReferences, nativeReferences, ret);
    }

    free(nativeReferences);
    if (javaReferences != NULL) {
        aEnv->ReleaseLongArrayElements(aHReferences, javaReferences,
                                       copyBack ? 0 : JNI_ABORT);
    }
    return ret;
}
