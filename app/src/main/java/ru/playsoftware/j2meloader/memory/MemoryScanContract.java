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

public final class MemoryScanContract {
    public static final int SCOPE_JAVA_FAST = 0;
    public static final int SCOPE_JAVA_THOROUGH = 1;

    public static final int TYPE_AUTO = 0;
    public static final int TYPE_INT8 = 1;
    public static final int TYPE_INT16 = 2;
    public static final int TYPE_UINT16 = 3;
    public static final int TYPE_INT32 = 4;
    public static final int TYPE_INT64 = 5;
    public static final int TYPE_FLOAT32 = 6;
    public static final int TYPE_FLOAT64 = 7;

    public static final String STATE_NO_TARGET = "NO_TARGET";
    public static final String STATE_IDLE = "IDLE";
    public static final String STATE_RUNNING = "RUNNING";
    public static final String STATE_COMPLETE = "COMPLETE";
    public static final String STATE_ERROR = "ERROR";
    public static final String STATE_CANCELLED = "CANCELLED";

    public static final String KEY_ACTIVE = "active";
    public static final String KEY_GENERATION = "generation";
    public static final String KEY_CAPABILITY = "capability";
    public static final String KEY_MANAGED_SELF_TEST = "managedSelfTest";
    public static final String KEY_STATE = "state";
    public static final String KEY_MESSAGE = "message";
    public static final String KEY_OPERATION_ID = "operationId";
    public static final String KEY_RESULT_COUNT = "resultCount";
    public static final String KEY_DIAGNOSTICS = "diagnostics";
    public static final String KEY_QUERY = "query";
    public static final String KEY_SCOPE = "scope";
    public static final String KEY_VALUE_TYPE = "valueType";
    public static final String KEY_SUCCESS = "success";
    public static final String KEY_GC_COUNT = "gcCount";
    public static final String KEY_GC_TIME_MS = "gcTimeMs";
    public static final String KEY_GC_COUNT_DELTA = "gcCountDelta";
    public static final String KEY_GC_TIME_DELTA_MS = "gcTimeDeltaMs";

    private MemoryScanContract() {
    }

    public static boolean isSearchType(int type) {
        return type >= TYPE_AUTO && type <= TYPE_FLOAT64;
    }

    public static boolean isCandidateType(int type) {
        return type >= TYPE_INT8 && type <= TYPE_FLOAT64;
    }

    public static String scopeName(int scope) {
        return scope == SCOPE_JAVA_THOROUGH ? "Java Thorough" : "Java Fast";
    }

    public static String typeName(int type) {
        return switch (type) {
            case TYPE_AUTO -> "Auto";
            case TYPE_INT8 -> "Int8";
            case TYPE_INT16 -> "Int16";
            case TYPE_UINT16 -> "UInt16";
            case TYPE_INT32 -> "Int32";
            case TYPE_INT64 -> "Int64";
            case TYPE_FLOAT32 -> "Float32";
            case TYPE_FLOAT64 -> "Float64";
            default -> "Unknown";
        };
    }
}
