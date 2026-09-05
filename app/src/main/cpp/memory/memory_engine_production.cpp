/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

// Final migration work is kept in the same translation unit as the transitional engine so it can
// reuse the authoritative parser, immutable SearchState and guarded mutation primitives without
// exporting a second internal ABI. Delete this wrapper when the legacy Candidate search database
// is fully replaced and the native module can be split normally again.
#include "memory_engine_compilation_unit.cpp"
#include "memory_engine_v2_extensions.inc"
#include "memory_engine_auto_refine_extension.inc"
#include "memory_engine_relative_extension.inc"
#include "memory_engine_refresh_extension.inc"
#include "memory_engine_revision_extension.inc"
#include "memory_engine_ordinary_extension.inc"
#include "memory_engine_compact_extension.inc"
#include "memory_engine_compact_owner_extension.inc"
#include "memory_engine_ordinary_debug_bridge.inc"
#include "memory_engine_filter_compat_extension.inc"
#include "memory_engine_search_compat_extension.inc"
