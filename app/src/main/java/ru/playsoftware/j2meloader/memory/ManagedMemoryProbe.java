/*
 * Copyright 2026 JL-Mod Plus contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.playsoftware.j2meloader.memory;

/** Managed ART scalar used only by the debug feasibility self-test. */
final class ManagedMemoryProbe {
    private static volatile int value;

    private ManagedMemoryProbe() {
    }

    static void set(int next) {
        value = next;
    }

    static int get() {
        return value;
    }
}
