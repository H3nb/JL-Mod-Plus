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
#include "javax_microedition_m3g_Transform.h"

#include <string.h>

static_assert(sizeof(Matrix) == 72,
              "Transform.java matrix storage must match native Matrix size");

static M3Gbool m3gReadTransformMatrix(JNIEnv* aEnv,
                                      jbyteArray aMatrix,
                                      Matrix* matrix)
{
    jbyte bytes[sizeof(Matrix)];
    aEnv->GetByteArrayRegion(aMatrix, 0, (jsize)sizeof(Matrix), bytes);
    if (aEnv->ExceptionCheck()) {
        return M3G_FALSE;
    }
    memcpy(matrix, bytes, sizeof(*matrix));
    return M3G_TRUE;
}

static M3Gbool m3gWriteTransformMatrix(JNIEnv* aEnv,
                                       jbyteArray aMatrix,
                                       const Matrix* matrix)
{
    jbyte bytes[sizeof(Matrix)];
    memcpy(bytes, matrix, sizeof(*matrix));
    aEnv->SetByteArrayRegion(aMatrix, 0, (jsize)sizeof(Matrix), bytes);
    return aEnv->ExceptionCheck() ? M3G_FALSE : M3G_TRUE;
}

JNIEXPORT void JNICALL Java_javax_microedition_m3g_Transform__1transformArray
(JNIEnv* aEnv, jclass, jbyteArray aMatrix, jlong aHArray, jfloatArray aOutArray, jboolean aW)
{
    Matrix matrix;
    if (!m3gReadTransformMatrix(aEnv, aMatrix, &matrix)) {
        return;
    }

    jfloat* outArray = aEnv->GetFloatArrayElements(aOutArray, NULL);
    if (outArray == NULL)
    {
        M3G_RAISE_EXCEPTION(aEnv, "java/lang/OutOfMemoryError");
        return;
    }

    int outArrayLen = aEnv->GetArrayLength(aOutArray);

    M3G_DO_LOCK
    m3gTransformArray((M3GVertexArray)aHArray, &matrix, (M3Gfloat *)outArray, outArrayLen, (M3Gbool)aW);
    M3G_DO_UNLOCK(aEnv)

    aEnv->ReleaseFloatArrayElements(aOutArray, outArray, 0);
}

JNIEXPORT void JNICALL Java_javax_microedition_m3g_Transform__1rotateQuat
(JNIEnv* aEnv, jclass, jbyteArray aMatrix, jfloat aQx, jfloat aQy, jfloat aQz, jfloat aQw)
{
    M3GQuat quat;
    Matrix matrix;

    if (aQx == 0 && aQy == 0 && aQz == 0 && aQw == 0)
    {
        M3G_RAISE_EXCEPTION(aEnv, "java/lang/IllegalArgumentException");
        return;
    }

    if (!m3gReadTransformMatrix(aEnv, aMatrix, &matrix)) {
        return;
    }

    quat.x = aQx;
    quat.y = aQy;
    quat.z = aQz;
    quat.w = aQw;

    M3G_DO_LOCK
    m3gNormalizeQuat(&quat);
    m3gPostRotateMatrixQuat(&matrix, (const Quat *)&quat);
    M3G_DO_UNLOCK(aEnv)

    m3gWriteTransformMatrix(aEnv, aMatrix, &matrix);
}

JNIEXPORT void JNICALL Java_javax_microedition_m3g_Transform__1setIdentity
(JNIEnv* aEnv, jclass, jbyteArray aMatrix)
{
    Matrix matrix;
    M3G_DO_LOCK
    m3gIdentityMatrix(&matrix);
    M3G_DO_UNLOCK(aEnv)
    m3gWriteTransformMatrix(aEnv, aMatrix, &matrix);
}

JNIEXPORT void JNICALL Java_javax_microedition_m3g_Transform__1translate
(JNIEnv* aEnv, jclass, jbyteArray aMatrix, jfloat aTx, jfloat aTy, jfloat aTz)
{
    Matrix matrix;
    if (!m3gReadTransformMatrix(aEnv, aMatrix, &matrix)) {
        return;
    }

    M3G_DO_LOCK
    m3gPostTranslateMatrix(&matrix, aTx, aTy, aTz);
    M3G_DO_UNLOCK(aEnv)

    m3gWriteTransformMatrix(aEnv, aMatrix, &matrix);
}

JNIEXPORT void JNICALL Java_javax_microedition_m3g_Transform__1getMatrix
(JNIEnv* aEnv, jclass, jbyteArray aMatrix, jfloatArray aDstArray)
{
    Matrix matrix;

    if (aDstArray == NULL)
    {
        M3G_RAISE_EXCEPTION(aEnv, "java/lang/NullPointerException");
        return;
    }

    if (aEnv->GetArrayLength(aDstArray) < 16)
    {
        M3G_RAISE_EXCEPTION(aEnv, "java/lang/IllegalArgumentException");
        return;
    }

    if (!m3gReadTransformMatrix(aEnv, aMatrix, &matrix)) {
        return;
    }

    float* dstArray = (float*)(aEnv->GetFloatArrayElements(aDstArray, NULL));
    if (dstArray == NULL)
    {
        M3G_RAISE_EXCEPTION(aEnv, "java/lang/OutOfMemoryError");
        return;
    }

    M3G_DO_LOCK
    m3gGetMatrixRows(&matrix, dstArray);
    M3G_DO_UNLOCK(aEnv)

    aEnv->ReleaseFloatArrayElements(aDstArray, dstArray, 0);
}

JNIEXPORT void JNICALL Java_javax_microedition_m3g_Transform__1transformTable
(JNIEnv* aEnv, jclass, jbyteArray aMatrix, jfloatArray aTableArray)
{
    // null pointers are never passed
    M3Gfloat *v = (M3Gfloat *)(aEnv->GetFloatArrayElements(aTableArray, NULL));
    if (v == NULL)
    {
        M3G_RAISE_EXCEPTION(aEnv, "java/lang/OutOfMemoryError");
        return;
    }
    int tabelArrayLen = aEnv->GetArrayLength(aTableArray);
    Matrix matrix;
    if (!m3gReadTransformMatrix(aEnv, aMatrix, &matrix)) {
        aEnv->ReleaseFloatArrayElements(aTableArray, v, JNI_ABORT);
        return;
    }

    {
        M3Gint i;
        M3GVec4 vec;

        M3G_DO_LOCK
        for (i = 0; i < tabelArrayLen; i += 4)
        {
            m3gSetVec4(&vec, v[i + 0], v[i + 1], v[i + 2], v[i + 3]);
            m3gTransformVec4(&matrix, &vec);
            v[i + 0] = vec.x;
            v[i + 1] = vec.y;
            v[i + 2] = vec.z;
            v[i + 3] = vec.w;
        }
        M3G_DO_UNLOCK(aEnv)
    }

    aEnv->ReleaseFloatArrayElements(aTableArray, v, 0);
}

JNIEXPORT void JNICALL Java_javax_microedition_m3g_Transform__1scale
(JNIEnv* aEnv, jclass, jbyteArray aMatrix, jfloat aSx, jfloat aSy, jfloat aSz)
{
    Matrix matrix;
    if (!m3gReadTransformMatrix(aEnv, aMatrix, &matrix)) {
        return;
    }

    M3G_DO_LOCK
    m3gPostScaleMatrix(&matrix, aSx, aSy, aSz);
    M3G_DO_UNLOCK(aEnv)

    m3gWriteTransformMatrix(aEnv, aMatrix, &matrix);
}

JNIEXPORT void JNICALL Java_javax_microedition_m3g_Transform__1mul
(JNIEnv* aEnv, jclass, jbyteArray aProdArray, jbyteArray aLeftArray, jbyteArray aRightArray)
{

    if (aRightArray == NULL || aLeftArray == NULL || aProdArray == NULL)
    {
        M3G_RAISE_EXCEPTION(aEnv, "java/lang/NullPointerException");
        return;
    }

    Matrix right;
    Matrix left;
    Matrix prod;
    if (!m3gReadTransformMatrix(aEnv, aRightArray, &right) ||
        !m3gReadTransformMatrix(aEnv, aLeftArray, &left)) {
        return;
    }

    M3G_DO_LOCK
    m3gMatrixProduct(&prod, &left, &right);
    M3G_DO_UNLOCK(aEnv)

    m3gWriteTransformMatrix(aEnv, aProdArray, &prod);
}

JNIEXPORT void JNICALL Java_javax_microedition_m3g_Transform__1setMatrix
(JNIEnv* aEnv, jclass, jbyteArray aMatrix, jfloatArray aSrcArray)
{
    Matrix matrix;

    if (aSrcArray == NULL)
    {
        M3G_RAISE_EXCEPTION(aEnv, "java/lang/NullPointerException");
        return;
    }
    if (aEnv->GetArrayLength(aSrcArray) < 16)
    {
        M3G_RAISE_EXCEPTION(aEnv, "java/lang/IllegalArgumentException");
        return;
    }

    float* srcArray = aEnv->GetFloatArrayElements(aSrcArray, NULL);
    if (srcArray == NULL)
    {
        M3G_RAISE_EXCEPTION(aEnv, "java/lang/OutOfMemoryError");
        return;
    }

    M3G_DO_LOCK
    m3gSetMatrixRows(&matrix, (const float *)srcArray);
    M3G_DO_UNLOCK(aEnv)

    aEnv->ReleaseFloatArrayElements(aSrcArray, srcArray, JNI_ABORT);
    m3gWriteTransformMatrix(aEnv, aMatrix, &matrix);
}

JNIEXPORT void JNICALL Java_javax_microedition_m3g_Transform__1transpose
(JNIEnv* aEnv, jclass, jbyteArray aMatrix)
{
    Matrix matrix;
    Matrix tpos;
    if (!m3gReadTransformMatrix(aEnv, aMatrix, &matrix)) {
        return;
    }

    M3G_DO_LOCK
    m3gMatrixTranspose(&tpos, &matrix);
    M3G_DO_UNLOCK(aEnv)

    m3gWriteTransformMatrix(aEnv, aMatrix, &tpos);
}

JNIEXPORT void JNICALL Java_javax_microedition_m3g_Transform__1invert
(JNIEnv* aEnv, jclass, jbyteArray aMatrix)
{
    Matrix matrix;
    if (!m3gReadTransformMatrix(aEnv, aMatrix, &matrix)) {
        return;
    }

    M3G_BEGIN_PROFILE(M3G_PROFILE_TRANSFORM_INVERT);
    M3G_DO_LOCK
    if (!m3gInvertMatrix(&matrix))
    {
        M3G_RAISE_EXCEPTION(aEnv, "java/lang/ArithmeticException");
        M3G_DO_UNLOCK(aEnv)
        return;
    }
    M3G_DO_UNLOCK(aEnv)
    M3G_END_PROFILE(M3G_PROFILE_TRANSFORM_INVERT);

    m3gWriteTransformMatrix(aEnv, aMatrix, &matrix);
}

JNIEXPORT void JNICALL Java_javax_microedition_m3g_Transform__1rotate
(JNIEnv* aEnv, jclass, jbyteArray aMatrix, jfloat aAngle, jfloat aAx, jfloat aAy, jfloat aAz)
{
    Matrix matrix;

    if (aAx == 0 && aAy == 0 && aAz == 0 && aAngle != 0)
    {
        M3G_RAISE_EXCEPTION(aEnv, "java/lang/IllegalArgumentException");
        return;
    }

    if (!m3gReadTransformMatrix(aEnv, aMatrix, &matrix)) {
        return;
    }

    M3G_DO_LOCK
    m3gPostRotateMatrix(&matrix, aAngle, aAx, aAy, aAz);
    M3G_DO_UNLOCK(aEnv)

    m3gWriteTransformMatrix(aEnv, aMatrix, &matrix);
}
