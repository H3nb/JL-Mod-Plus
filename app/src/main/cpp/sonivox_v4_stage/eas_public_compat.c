/*
 * Copyright 2026 H3NB
 * SPDX-License-Identifier: Apache-2.0
 *
 * Compatibility translation unit for the pinned SONiVOX v4.0.1 eas_public.c.
 *
 * The MMAPI-only block in upstream eas_public.c still has two printf-style
 * EAS_ReportEx(...) calls. The current host API uses EAS_ReportEx for hashed
 * debug messages and EAS_Report for printf-style messages. Pre-include the
 * current declaration, then redirect only the stale source-level calls while
 * compiling the otherwise unmodified pinned upstream file.
 *
 * This can be removed when upstream fixes those calls or when the pinned
 * SONiVOX revision is updated to a version containing the fix.
 */

#include "../sonivox_v4/arm-wt-22k/host_src/eas_report.h"

#define EAS_ReportEx EAS_Report
#include "../sonivox_v4/arm-wt-22k/lib_src/eas_public.c"
#undef EAS_ReportEx
