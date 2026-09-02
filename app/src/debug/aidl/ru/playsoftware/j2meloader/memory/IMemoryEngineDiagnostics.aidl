/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 */

package ru.playsoftware.j2meloader.memory;

import android.os.Bundle;

/** Debug-only, pull-based diagnostics for release-comparison Memory Editor benchmarks. */
interface IMemoryEngineDiagnostics {
    Bundle snapshot();

    /**
     * Re-scan the configured target through the v2 ResultStore equality kernel and compare it
     * with the currently published legacy explicit-type equality result. Debug builds only.
     */
    Bundle validateKnownEqualShadow(int valueType);
}
