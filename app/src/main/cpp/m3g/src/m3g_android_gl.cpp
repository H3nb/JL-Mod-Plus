/*
* Copyright (c) 2003 Nokia Corporation and/or its subsidiary(-ies).
* All rights reserved.
* This component and the accompanying materials are made available
* under the terms of the License "Eclipse Public License v1.0"
* which accompanies this distribution, and is available
* at the URL "http://www.eclipse.org/legal/epl-v10.html".
*
* Initial Contributors:
* Nokia Corporation - initial contribution.
*
* Contributors:
*
* Description: Symbian offscreen GL context implementation
*
*/


/*!
 * \file
 * \brief Symbian offscreen GL context implementation
 */

#include "m3g_gl.h"
#include "m3g_defs.h"

//----------------------------------------------------------------------
// Private functions
//----------------------------------------------------------------------

//----------------------------------------------------------------------
// Internal functions
//----------------------------------------------------------------------

/*!
 * \brief
 */
M3Gbool m3gglLockNativeBitmap(M3GNativeBitmap bitmap,
                              M3Gubyte **ptr,
                              M3Gsizei *stride)
{
    (void) bitmap;
    (void) ptr;
    (void) stride;
    return M3G_FALSE;
}

/*!
 * \brief
 */
void m3gglReleaseNativeBitmap(M3GNativeBitmap bitmap)
{
    (void) bitmap;
}

/*!
 * \brief Queries properties of a native window
 */
extern "C" M3Gbool m3gglGetNativeBitmapParams(M3GNativeBitmap bitmap,
                                              M3GPixelFormat *format,
                                              M3Gint *width, M3Gint *height, M3Gint *pixels)
{
    (void) bitmap;
    (void) format;
    (void) width;
    (void) height;
    (void) pixels;
    return M3G_FALSE;
}

/*!
 * \brief Queries properties of a native window
 */
extern "C" M3Gbool m3gglGetNativeWindowParams(M3GNativeWindow wnd,
                                              M3GPixelFormat *format,
                                              M3Gint *width, M3Gint *height)
{
    (void) wnd;
    (void) format;
    (void) width;
    (void) height;
    return M3G_FALSE;
}

