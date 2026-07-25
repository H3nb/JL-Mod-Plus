/*
* Copyright 2026 H3NB
*
* Copyright (c) 2005-2006 Nokia Corporation and/or its subsidiary(-ies).
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
* Description:  M3GCore function call synchronization for J9
*
*/

// INCLUDE FILES
#include "CSynchronization.hpp"

class M3gGlobals
{
public:
    M3gGlobals() : mSync(0) {}

public:
    CSynchronization* mSync;
};

#if defined(__WINSCW__)

#include <pls.h>
M3gGlobals* getM3gGlobals()
{
    // Access the PLS of this process.
    return Pls<M3gGlobals>(TUid::Uid(0x200211E2));
}

#else

M3gGlobals* getM3gGlobals()
{
    /* Function-local statics are initialized once by the C++ runtime, so the
     * process-wide M3G state cannot race during the first JNI call. */
    static M3gGlobals globals;
    return &globals;
}
#endif


// STATIC MEMBERS
/*static*/ //CSynchronization* CSynchronization::iSelf = NULL;

// -----------------------------------------------------------------------------
// CSynchronization::InstanceL
// -----------------------------------------------------------------------------
/*static*/ CSynchronization* CSynchronization::InstanceL()
{
    static pthread_mutex_t initGuard = PTHREAD_MUTEX_INITIALIZER;
    M3gGlobals* globals = getM3gGlobals();

    pthread_mutex_lock(&initGuard);
    if (!globals->mSync)
    {
        globals->mSync = CSynchronization::NewL();
    }
    CSynchronization* sync = globals->mSync;
    pthread_mutex_unlock(&initGuard);
    return sync;
}

// -----------------------------------------------------------------------------
// CSynchronization::NewL
// -----------------------------------------------------------------------------
/*static*/ CSynchronization* CSynchronization::NewL()
{
    CSynchronization* self = new /*(ELeave)*/ CSynchronization();
    //CleanupStack::PushL(self);
    self->ConstructL();
    //CleanupStack::Pop();
    return self;
}

// -----------------------------------------------------------------------------
// CSynchronization::ConstructL
// -----------------------------------------------------------------------------
void CSynchronization::ConstructL()
{
    /* M3G keeps mutable global error/object state, so every JNI entry point
     * must serialize access on Android as well as on the original devices. */
    pthread_mutex_init(&iGuard, NULL);
}

// -----------------------------------------------------------------------------
// CSynchronization::CSynchronization
// -----------------------------------------------------------------------------
CSynchronization::CSynchronization() : iErrorCode(0)
{
}

// -----------------------------------------------------------------------------
// CSynchronization::~CSynchronization
// -----------------------------------------------------------------------------
CSynchronization::~CSynchronization()
{
    pthread_mutex_destroy(&iGuard);
}

// -----------------------------------------------------------------------------
// CSynchronization::Lock
// -----------------------------------------------------------------------------
void CSynchronization::Lock()
{
    pthread_mutex_lock(&iGuard);
    iErrorCode = 0;
}

// -----------------------------------------------------------------------------
// CSynchronization::Unlock
// -----------------------------------------------------------------------------
void CSynchronization::Unlock()
{
    iErrorCode = 0;
    pthread_mutex_unlock(&iGuard);
}

// -----------------------------------------------------------------------------
// CSynchronization::SetErrorCode
// -----------------------------------------------------------------------------
void CSynchronization::SetErrorCode(int aCode)
{
    iErrorCode = aCode;
}

// -----------------------------------------------------------------------------
// CSynchronization::GetErrorCode
// -----------------------------------------------------------------------------
int CSynchronization::GetErrorCode()
{
    return iErrorCode;
}
