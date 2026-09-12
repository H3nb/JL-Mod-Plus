/*
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

package io.github.h3nb.jlmodplus.memory;

import androidx.annotation.Nullable;

import java.math.BigInteger;

/**
 * Builds one integral primitive plane for an AUTO known-value query.
 *
 * <p>AUTO input is intentionally parsed in a wider domain than the candidate type. This prevents
 * a bound that is outside a narrow primitive domain from accidentally removing that whole plane.
 * For example, {@code BYTE < 1000} is true for every byte, while {@code BYTE BETWEEN 0..1000}
 * still has the representable overlap {@code 0..127}. Explicit typed searches remain strict in
 * {@link ManagedJavaMemoryEngine} and never use this helper.</p>
 */
final class ManagedAutoKnownQuery {
    private ManagedAutoKnownQuery() {
    }

    static Result forIntegralType(int type, int predicate, @Nullable String firstText,
                                  @Nullable String secondText) {
        BigInteger minimum = minimum(type);
        BigInteger maximum = maximum(type);
        if (minimum == null || maximum == null) return Result.invalid();

        BigInteger first = parseWideInteger(firstText);
        if (first == null) return Result.invalid();
        BigInteger second = null;
        if (predicate == MemoryEngineContract.PREDICATE_BETWEEN) {
            second = parseWideInteger(secondText);
            if (second == null || first.compareTo(second) > 0) return Result.invalid();
        }

        switch (predicate) {
            case MemoryEngineContract.PREDICATE_EQUAL:
                return inRange(first, minimum, maximum)
                        ? Result.bounds(first.longValue(), 0L) : Result.invalid();
            case MemoryEngineContract.PREDICATE_NOT_EQUAL:
                return inRange(first, minimum, maximum)
                        ? Result.bounds(first.longValue(), 0L) : Result.all();
            case MemoryEngineContract.PREDICATE_GREATER:
                if (first.compareTo(minimum) < 0) return Result.all();
                if (first.compareTo(maximum) >= 0) return Result.invalid();
                return Result.bounds(first.longValue(), 0L);
            case MemoryEngineContract.PREDICATE_GREATER_OR_EQUAL:
                if (first.compareTo(minimum) <= 0) return Result.all();
                if (first.compareTo(maximum) > 0) return Result.invalid();
                return Result.bounds(first.longValue(), 0L);
            case MemoryEngineContract.PREDICATE_LESS:
                if (first.compareTo(maximum) > 0) return Result.all();
                if (first.compareTo(minimum) <= 0) return Result.invalid();
                return Result.bounds(first.longValue(), 0L);
            case MemoryEngineContract.PREDICATE_LESS_OR_EQUAL:
                if (first.compareTo(maximum) >= 0) return Result.all();
                if (first.compareTo(minimum) < 0) return Result.invalid();
                return Result.bounds(first.longValue(), 0L);
            case MemoryEngineContract.PREDICATE_BETWEEN:
                if (second.compareTo(minimum) < 0 || first.compareTo(maximum) > 0) {
                    return Result.invalid();
                }
                BigInteger clippedFirst = first.max(minimum);
                BigInteger clippedSecond = second.min(maximum);
                if (clippedFirst.equals(minimum) && clippedSecond.equals(maximum)) {
                    return Result.all();
                }
                return Result.bounds(clippedFirst.longValue(), clippedSecond.longValue());
            default:
                return Result.invalid();
        }
    }

    private static boolean inRange(BigInteger value, BigInteger minimum, BigInteger maximum) {
        return value.compareTo(minimum) >= 0 && value.compareTo(maximum) <= 0;
    }

    @Nullable
    private static BigInteger minimum(int type) {
        switch (type) {
            case MemoryEngineContract.TYPE_BYTE:
                return BigInteger.valueOf(Byte.MIN_VALUE);
            case MemoryEngineContract.TYPE_SHORT:
                return BigInteger.valueOf(Short.MIN_VALUE);
            case MemoryEngineContract.TYPE_CHAR:
                return BigInteger.ZERO;
            case MemoryEngineContract.TYPE_INT:
                return BigInteger.valueOf(Integer.MIN_VALUE);
            case MemoryEngineContract.TYPE_LONG:
                return BigInteger.valueOf(Long.MIN_VALUE);
            default:
                return null;
        }
    }

    @Nullable
    private static BigInteger maximum(int type) {
        switch (type) {
            case MemoryEngineContract.TYPE_BYTE:
                return BigInteger.valueOf(Byte.MAX_VALUE);
            case MemoryEngineContract.TYPE_SHORT:
                return BigInteger.valueOf(Short.MAX_VALUE);
            case MemoryEngineContract.TYPE_CHAR:
                return BigInteger.valueOf(Character.MAX_VALUE);
            case MemoryEngineContract.TYPE_INT:
                return BigInteger.valueOf(Integer.MAX_VALUE);
            case MemoryEngineContract.TYPE_LONG:
                return BigInteger.valueOf(Long.MAX_VALUE);
            default:
                return null;
        }
    }

    @Nullable
    private static BigInteger parseWideInteger(@Nullable String text) {
        if (text == null) return null;
        String value = text.trim();
        if (value.isEmpty()) return null;

        int start = 0;
        boolean negative = false;
        char first = value.charAt(0);
        if (first == '+' || first == '-') {
            negative = first == '-';
            start = 1;
        }
        if (start == value.length()) return null;

        boolean hexadecimal = value.regionMatches(true, start, "0x", 0, 2);
        int digitsStart = hexadecimal ? start + 2 : start;
        if (digitsStart == value.length()) return null;
        try {
            BigInteger parsed = new BigInteger(value.substring(digitsStart), hexadecimal ? 16 : 10);
            return negative ? parsed.negate() : parsed;
        } catch (NumberFormatException error) {
            return null;
        }
    }

    static final class Result {
        final boolean valid;
        final boolean alwaysMatch;
        final long first;
        final long second;

        private Result(boolean valid, boolean alwaysMatch, long first, long second) {
            this.valid = valid;
            this.alwaysMatch = alwaysMatch;
            this.first = first;
            this.second = second;
        }

        static Result invalid() {
            return new Result(false, false, 0L, 0L);
        }

        static Result all() {
            return new Result(true, true, 0L, 0L);
        }

        static Result bounds(long first, long second) {
            return new Result(true, false, first, second);
        }
    }
}
