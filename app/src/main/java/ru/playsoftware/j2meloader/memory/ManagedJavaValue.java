/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package ru.playsoftware.j2meloader.memory;

import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.math.BigInteger;

/** Typed raw-bit codec and predicate evaluator for the managed primitive planes. */
final class ManagedJavaValue {
	private static final BigInteger UNSIGNED_LONG_MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);

	private ManagedJavaValue() {
	}

	static boolean isSupportedType(int type) {
		return type >= MemoryEngineContract.TYPE_BYTE && type <= MemoryEngineContract.TYPE_DOUBLE;
	}

	static boolean isFloating(int type) {
		return type == MemoryEngineContract.TYPE_FLOAT || type == MemoryEngineContract.TYPE_DOUBLE;
	}

	static int typeForClass(Class<?> type) {
		if (type == byte.class) return MemoryEngineContract.TYPE_BYTE;
		if (type == short.class) return MemoryEngineContract.TYPE_SHORT;
		if (type == char.class) return MemoryEngineContract.TYPE_CHAR;
		if (type == int.class) return MemoryEngineContract.TYPE_INT;
		if (type == long.class) return MemoryEngineContract.TYPE_LONG;
		if (type == float.class) return MemoryEngineContract.TYPE_FLOAT;
		if (type == double.class) return MemoryEngineContract.TYPE_DOUBLE;
		return MemoryEngineContract.TYPE_AUTO;
	}

	static int byteWidth(int type) {
		switch (type) {
			case MemoryEngineContract.TYPE_BYTE: return 1;
			case MemoryEngineContract.TYPE_SHORT:
			case MemoryEngineContract.TYPE_CHAR: return 2;
			case MemoryEngineContract.TYPE_INT:
			case MemoryEngineContract.TYPE_FLOAT: return 4;
			case MemoryEngineContract.TYPE_LONG:
			case MemoryEngineContract.TYPE_DOUBLE: return 8;
			default: return 0;
		}
	}

	static long readField(Field field, @Nullable Object owner, int type)
			throws IllegalAccessException {
		switch (type) {
			case MemoryEngineContract.TYPE_BYTE: return field.getByte(owner);
			case MemoryEngineContract.TYPE_SHORT: return field.getShort(owner);
			case MemoryEngineContract.TYPE_CHAR: return field.getChar(owner);
			case MemoryEngineContract.TYPE_INT: return field.getInt(owner);
			case MemoryEngineContract.TYPE_LONG: return field.getLong(owner);
			case MemoryEngineContract.TYPE_FLOAT:
				return Float.floatToRawIntBits(field.getFloat(owner)) & 0xffffffffL;
			case MemoryEngineContract.TYPE_DOUBLE:
				return Double.doubleToRawLongBits(field.getDouble(owner));
			default: throw new IllegalArgumentException("unsupported primitive type");
		}
	}

	static long readArrayElement(Object array, int index, int type) {
		switch (type) {
			case MemoryEngineContract.TYPE_BYTE: return ((byte[]) array)[index];
			case MemoryEngineContract.TYPE_SHORT: return ((short[]) array)[index];
			case MemoryEngineContract.TYPE_CHAR: return ((char[]) array)[index];
			case MemoryEngineContract.TYPE_INT: return ((int[]) array)[index];
			case MemoryEngineContract.TYPE_LONG: return ((long[]) array)[index];
			case MemoryEngineContract.TYPE_FLOAT:
				return Float.floatToRawIntBits(((float[]) array)[index]) & 0xffffffffL;
			case MemoryEngineContract.TYPE_DOUBLE:
				return Double.doubleToRawLongBits(((double[]) array)[index]);
			default: throw new IllegalArgumentException("unsupported primitive type");
		}
	}

	static void writeField(Field field, @Nullable Object owner, int type, long bits)
			throws IllegalAccessException {
		switch (type) {
			case MemoryEngineContract.TYPE_BYTE: field.setByte(owner, (byte) bits); return;
			case MemoryEngineContract.TYPE_SHORT: field.setShort(owner, (short) bits); return;
			case MemoryEngineContract.TYPE_CHAR: field.setChar(owner, (char) bits); return;
			case MemoryEngineContract.TYPE_INT: field.setInt(owner, (int) bits); return;
			case MemoryEngineContract.TYPE_LONG: field.setLong(owner, bits); return;
			case MemoryEngineContract.TYPE_FLOAT: field.setFloat(owner, Float.intBitsToFloat((int) bits)); return;
			case MemoryEngineContract.TYPE_DOUBLE: field.setDouble(owner, Double.longBitsToDouble(bits)); return;
			default: throw new IllegalArgumentException("unsupported primitive type");
		}
	}

	static void writeArrayElement(Object array, int index, int type, long bits) {
		switch (type) {
			case MemoryEngineContract.TYPE_BYTE: ((byte[]) array)[index] = (byte) bits; return;
			case MemoryEngineContract.TYPE_SHORT: ((short[]) array)[index] = (short) bits; return;
			case MemoryEngineContract.TYPE_CHAR: ((char[]) array)[index] = (char) bits; return;
			case MemoryEngineContract.TYPE_INT: ((int[]) array)[index] = (int) bits; return;
			case MemoryEngineContract.TYPE_LONG: ((long[]) array)[index] = bits; return;
			case MemoryEngineContract.TYPE_FLOAT: ((float[]) array)[index] = Float.intBitsToFloat((int) bits); return;
			case MemoryEngineContract.TYPE_DOUBLE: ((double[]) array)[index] = Double.longBitsToDouble(bits); return;
			default: throw new IllegalArgumentException("unsupported primitive type");
		}
	}

	/** Parses one editable/search value into the canonical raw bits for its exact primitive type. */
	static boolean parse(@Nullable String text, int type, long[] output) {
		if (text == null || output == null || output.length == 0) return false;
		String value = text.trim();
		if (value.isEmpty()) return false;
		try {
			switch (type) {
				case MemoryEngineContract.TYPE_BYTE:
				case MemoryEngineContract.TYPE_SHORT:
				case MemoryEngineContract.TYPE_CHAR:
				case MemoryEngineContract.TYPE_INT:
				case MemoryEngineContract.TYPE_LONG:
					BigInteger integer = parseInteger(value);
					if (integer == null) return false;
					BigInteger minimum;
					BigInteger maximum;
					switch (type) {
						case MemoryEngineContract.TYPE_BYTE:
							minimum = BigInteger.valueOf(Byte.MIN_VALUE);
							maximum = BigInteger.valueOf(Byte.MAX_VALUE);
							break;
						case MemoryEngineContract.TYPE_SHORT:
							minimum = BigInteger.valueOf(Short.MIN_VALUE);
							maximum = BigInteger.valueOf(Short.MAX_VALUE);
							break;
						case MemoryEngineContract.TYPE_CHAR:
							minimum = BigInteger.ZERO;
							maximum = BigInteger.valueOf(Character.MAX_VALUE);
							break;
						case MemoryEngineContract.TYPE_INT:
							minimum = BigInteger.valueOf(Integer.MIN_VALUE);
							maximum = BigInteger.valueOf(Integer.MAX_VALUE);
							break;
						default:
							minimum = BigInteger.valueOf(Long.MIN_VALUE);
							maximum = BigInteger.valueOf(Long.MAX_VALUE);
					}
					if (integer.compareTo(minimum) < 0 || integer.compareTo(maximum) > 0) {
						return false;
					}
					output[0] = integer.longValue();
					return true;
				case MemoryEngineContract.TYPE_FLOAT:
				case MemoryEngineContract.TYPE_DOUBLE:
					if (hasFloatingSuffix(value)) return false;
					double parsed = Double.parseDouble(value);
					if (!Double.isFinite(parsed)) return false;
					if (type == MemoryEngineContract.TYPE_FLOAT) {
						float floatValue = (float) parsed;
						if (!Float.isFinite(floatValue)) return false;
						output[0] = Float.floatToRawIntBits(floatValue) & 0xffffffffL;
					} else {
						output[0] = Double.doubleToRawLongBits(parsed);
					}
					return true;
				default: return false;
			}
		} catch (NumberFormatException exception) {
			return false;
		}
	}

	/** Matches the native integer parser's decimal/0x syntax without narrowing overflow. */
	@Nullable
	private static BigInteger parseInteger(String value) {
		int start = 0;
		boolean negative = false;
		if (value.charAt(0) == '+' || value.charAt(0) == '-') {
			negative = value.charAt(0) == '-';
			start = 1;
		}
		if (start == value.length()) return null;
		boolean hexadecimal = value.regionMatches(true, start, "0x", 0, 2);
		int base = hexadecimal ? 16 : 10;
		int digitsStart = hexadecimal ? start + 2 : start;
		if (digitsStart == value.length()) return null;
		String digits = value.substring(digitsStart);
		try {
			BigInteger parsed = new BigInteger(digits, base);
			return negative ? parsed.negate() : parsed;
		} catch (NumberFormatException exception) {
			return null;
		}
	}

	private static boolean hasFloatingSuffix(String value) {
		char last = value.charAt(value.length() - 1);
		return last == 'f' || last == 'F' || last == 'd' || last == 'D';
	}

	/** Parses a non-negative relative magnitude using the unsigned primitive width. */
	static boolean parseMagnitude(@Nullable String text, int type, long[] output) {
		if (text == null || output == null || output.length == 0) return false;
		String value = text.trim();
		if (value.startsWith("+")) value = value.substring(1);
		if (value.isEmpty() || value.startsWith("-")) return false;
		try {
			if (isFloating(type)) {
				if (hasFloatingSuffix(value)) return false;
				double magnitude = Double.parseDouble(value);
				if (!Double.isFinite(magnitude) || magnitude < 0.0) return false;
				if (type == MemoryEngineContract.TYPE_FLOAT) {
					float rounded = (float) magnitude;
					if (!Float.isFinite(rounded)) return false;
					output[0] = Float.floatToRawIntBits(rounded) & 0xffffffffL;
				} else {
					output[0] = Double.doubleToRawLongBits(magnitude);
				}
				return true;
			}
			boolean hexadecimal = value.startsWith("0x") || value.startsWith("0X");
			BigInteger parsed = new BigInteger(hexadecimal ? value.substring(2) : value,
					hexadecimal ? 16 : 10);
			if (parsed.signum() < 0) return false;
			int width = byteWidth(type) * 8;
			if (width <= 0) return false;
			BigInteger maximum = width == 64 ? UNSIGNED_LONG_MAX : BigInteger.ONE.shiftLeft(width).subtract(BigInteger.ONE);
			if (parsed.compareTo(maximum) > 0) return false;
			output[0] = parsed.longValue();
			return true;
		} catch (NumberFormatException exception) {
			return false;
		}
	}

	static boolean validKnownQuery(int type, int predicate, long first, long second) {
		if (!isSupportedType(type) || predicate < MemoryEngineContract.PREDICATE_EQUAL
				|| predicate > MemoryEngineContract.PREDICATE_BETWEEN) return false;
		if (predicate != MemoryEngineContract.PREDICATE_BETWEEN && second != 0L) return false;
		if (!hasCanonicalBits(type, first)
				|| (predicate == MemoryEngineContract.PREDICATE_BETWEEN && !hasCanonicalBits(type, second))) {
			return false;
		}
		if (isNan(type, first) || (predicate == MemoryEngineContract.PREDICATE_BETWEEN && isNan(type, second))) {
			return false;
		}
		return predicate != MemoryEngineContract.PREDICATE_BETWEEN || compare(type, first, second) <= 0;
	}

	static boolean validRelativeQuery(int type, int predicate, long first, long second) {
		if (!isSupportedType(type) || predicate < MemoryEngineContract.PREDICATE_CHANGED
				|| predicate > MemoryEngineContract.PREDICATE_DECREASED_BY_RANGE) return false;
		boolean needsMagnitude = predicate >= MemoryEngineContract.PREDICATE_INCREASED_BY;
		if (!needsMagnitude) return first == 0L && second == 0L;
		boolean needsSecond = predicate == MemoryEngineContract.PREDICATE_INCREASED_BY_RANGE
				|| predicate == MemoryEngineContract.PREDICATE_DECREASED_BY_RANGE;
		if (isFloating(type)) {
			if (!hasCanonicalBits(type, first) || (!needsSecond && second != 0L)
					|| (needsSecond && !hasCanonicalBits(type, second))) return false;
		} else if (!hasCanonicalMagnitude(type, first) || (!needsSecond && second != 0L)
				|| (needsSecond && !hasCanonicalMagnitude(type, second))) return false;
		if (isFloating(type)) {
			double firstValue = asDouble(type, first);
			double secondValue = asDouble(type, second);
			return Double.isFinite(firstValue) && firstValue >= 0.0
					&& (!needsSecond
					|| (Double.isFinite(secondValue) && secondValue >= 0.0 && firstValue <= secondValue));
		}
		long maximum = byteWidth(type) == 8 ? -1L : (1L << (byteWidth(type) * 8)) - 1L;
		if (byteWidth(type) == 8) {
			if (needsSecond && Long.compareUnsigned(first, second) > 0) return false;
			return true;
		}
		if (first < 0L || second < 0L || first > maximum || second > maximum) return false;
		return !needsSecond || first <= second;
	}

	static boolean matchesKnown(int type, int predicate, long current, long first, long second) {
		if (!validKnownQuery(type, predicate, first, second) || isNan(type, current)) return false;
		int relation = compare(type, current, first);
		switch (predicate) {
			case MemoryEngineContract.PREDICATE_EQUAL: return relation == 0;
			case MemoryEngineContract.PREDICATE_NOT_EQUAL: return relation != 0;
			case MemoryEngineContract.PREDICATE_GREATER: return relation > 0;
			case MemoryEngineContract.PREDICATE_LESS: return relation < 0;
			case MemoryEngineContract.PREDICATE_GREATER_OR_EQUAL: return relation >= 0;
			case MemoryEngineContract.PREDICATE_LESS_OR_EQUAL: return relation <= 0;
			case MemoryEngineContract.PREDICATE_BETWEEN:
				return compare(type, current, first) >= 0 && compare(type, current, second) <= 0;
			default: return false;
		}
	}

	static boolean matchesRelative(int type, int predicate, long current, long reference,
	                               long first, long second) {
		if (!validRelativeQuery(type, predicate, first, second) || isNan(type, current)
				|| isNan(type, reference)) return false;
		if (predicate == MemoryEngineContract.PREDICATE_CHANGED) return !same(type, current, reference);
		if (predicate == MemoryEngineContract.PREDICATE_UNCHANGED) return same(type, current, reference);
		if (predicate == MemoryEngineContract.PREDICATE_INCREASED) return compare(type, current, reference) > 0;
		if (predicate == MemoryEngineContract.PREDICATE_DECREASED) return compare(type, current, reference) < 0;
		if (isFloating(type)) {
			double delta = asDouble(type, current) - asDouble(type, reference);
			double wanted = asDouble(type, first);
			if (predicate == MemoryEngineContract.PREDICATE_INCREASED_BY) return delta == wanted;
			if (predicate == MemoryEngineContract.PREDICATE_DECREASED_BY) return -delta == wanted;
			if (predicate == MemoryEngineContract.PREDICATE_CHANGED_BY) return Math.abs(delta) == Math.abs(wanted);
			if (predicate == MemoryEngineContract.PREDICATE_INCREASED_BY_RANGE) {
				return delta >= wanted && delta <= asDouble(type, second);
			}
			return -delta >= wanted && -delta <= asDouble(type, second);
		}
		boolean increased = compare(type, current, reference) >= 0;
		long magnitude = unsignedMagnitude(type, current, reference);
		int magnitudeComparison = Long.compareUnsigned(magnitude, first);
		if (predicate == MemoryEngineContract.PREDICATE_INCREASED_BY) return increased && magnitudeComparison == 0;
		if (predicate == MemoryEngineContract.PREDICATE_DECREASED_BY) return !increased && magnitudeComparison == 0;
		if (predicate == MemoryEngineContract.PREDICATE_CHANGED_BY) return magnitudeComparison == 0;
		int upper = Long.compareUnsigned(magnitude, second);
		if (predicate == MemoryEngineContract.PREDICATE_INCREASED_BY_RANGE) {
			return increased && magnitudeComparison >= 0 && upper <= 0;
		}
		return !increased && magnitudeComparison >= 0 && upper <= 0;
	}

	static String format(int type, long bits) {
		switch (type) {
			case MemoryEngineContract.TYPE_BYTE: return Byte.toString((byte) bits);
			case MemoryEngineContract.TYPE_SHORT: return Short.toString((short) bits);
			case MemoryEngineContract.TYPE_CHAR: return Integer.toString((int) bits & 0xffff);
			case MemoryEngineContract.TYPE_INT: return Integer.toString((int) bits);
			case MemoryEngineContract.TYPE_LONG: return Long.toString(bits);
			case MemoryEngineContract.TYPE_FLOAT: return Float.toString(Float.intBitsToFloat((int) bits));
			case MemoryEngineContract.TYPE_DOUBLE: return Double.toString(Double.longBitsToDouble(bits));
			default: return "?";
		}
	}

	static boolean isNan(int type, long bits) {
		return type == MemoryEngineContract.TYPE_FLOAT ? Float.isNaN(Float.intBitsToFloat((int) bits))
				: type == MemoryEngineContract.TYPE_DOUBLE && Double.isNaN(Double.longBitsToDouble(bits));
	}

	private static boolean same(int type, long left, long right) {
		return compare(type, left, right) == 0;
	}

	private static int compare(int type, long left, long right) {
		switch (type) {
			case MemoryEngineContract.TYPE_BYTE: return Byte.compare((byte) left, (byte) right);
			case MemoryEngineContract.TYPE_SHORT: return Short.compare((short) left, (short) right);
			case MemoryEngineContract.TYPE_CHAR: return Integer.compare((int) left & 0xffff, (int) right & 0xffff);
			case MemoryEngineContract.TYPE_INT: return Integer.compare((int) left, (int) right);
			case MemoryEngineContract.TYPE_LONG: return Long.compare(left, right);
			case MemoryEngineContract.TYPE_FLOAT: {
				float leftValue = Float.intBitsToFloat((int) left);
				float rightValue = Float.intBitsToFloat((int) right);
				return leftValue < rightValue ? -1 : leftValue > rightValue ? 1 : 0;
			}
			case MemoryEngineContract.TYPE_DOUBLE: {
				double leftValue = Double.longBitsToDouble(left);
				double rightValue = Double.longBitsToDouble(right);
				return leftValue < rightValue ? -1 : leftValue > rightValue ? 1 : 0;
			}
			default: return 1;
		}
	}

	private static double asDouble(int type, long bits) {
		return type == MemoryEngineContract.TYPE_FLOAT
				? (double) Float.intBitsToFloat((int) bits) : Double.longBitsToDouble(bits);
	}

	private static boolean hasCanonicalBits(int type, long bits) {
		switch (type) {
			case MemoryEngineContract.TYPE_BYTE: return bits == (byte) bits;
			case MemoryEngineContract.TYPE_SHORT: return bits == (short) bits;
			case MemoryEngineContract.TYPE_CHAR: return bits >= 0L && bits <= 0xffffL;
			case MemoryEngineContract.TYPE_INT: return bits == (int) bits;
			case MemoryEngineContract.TYPE_FLOAT: return (bits & ~0xffffffffL) == 0L;
			case MemoryEngineContract.TYPE_LONG:
			case MemoryEngineContract.TYPE_DOUBLE: return true;
			default: return false;
		}
	}

	private static boolean hasCanonicalMagnitude(int type, long bits) {
		if (bits < 0L && type != MemoryEngineContract.TYPE_LONG) return false;
		int width = byteWidth(type) * 8;
		return width == 64 || (width > 0 && bits <= ((1L << width) - 1L));
	}

	private static long unsignedMagnitude(int type, long current, long reference) {
		boolean increased = compare(type, current, reference) >= 0;
		if (type == MemoryEngineContract.TYPE_LONG) {
			return increased ? current - reference : reference - current;
		}
		long mask = (1L << (byteWidth(type) * 8)) - 1L;
		long currentUnsigned = current & mask;
		long referenceUnsigned = reference & mask;
		return increased ? (currentUnsigned - referenceUnsigned) & mask
				: (referenceUnsigned - currentUnsigned) & mask;
	}
}
