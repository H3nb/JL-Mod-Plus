/* SPDX-License-Identifier: Apache-2.0 */
package javax.microedition.lcdui;

/**
 * Android-independent, authoritative MIDP text value. Guest setters commit atomically:
 * on validation failure neither the contents nor their constraints/capacity change.
 */
final class TextFieldValue {
    private static final int FLAGS = TextField.PASSWORD | TextField.UNEDITABLE
            | TextField.SENSITIVE | TextField.NON_PREDICTIVE
            | TextField.INITIAL_CAPS_WORD | TextField.INITIAL_CAPS_SENTENCE;

    private String text = "";
    private int maxSize = 1;
    private int constraints = TextField.ANY;

    synchronized String getString() { return text; }
    synchronized int size() { return text.length(); }
    synchronized int getMaxSize() { return maxSize; }
    synchronized int getConstraints() { return constraints; }

    synchronized void setString(String candidate) {
        String next = candidate == null ? "" : candidate;
        check(next, maxSize, constraints);
        text = next;
    }

    synchronized void setChars(char[] data, int offset, int length) {
        if (data == null) {
            setString(null);
            return;
        }
        checkArrayRange(data.length, offset, length);
        setString(new String(data, offset, length));
    }

    synchronized int getChars(char[] destination) {
        if (destination == null) throw new NullPointerException("destination");
        if (destination.length < text.length()) throw new ArrayIndexOutOfBoundsException();
        text.getChars(0, text.length(), destination, 0);
        return text.length();
    }

    synchronized void insert(String source, int position) {
        if (source == null) throw new NullPointerException("source");
        int at = Math.max(0, Math.min(position, text.length()));
        setString(new StringBuilder(text).insert(at, source).toString());
    }

    synchronized void insert(char[] data, int offset, int length, int position) {
        if (data == null) throw new NullPointerException("data");
        checkArrayRange(data.length, offset, length);
        insert(new String(data, offset, length), position);
    }

    synchronized void delete(int offset, int length) {
        if (offset < 0 || length < 0 || offset > text.length()
                || length > text.length() - offset) {
            throw new StringIndexOutOfBoundsException();
        }
        setString(new StringBuilder(text).delete(offset, offset + length).toString());
    }

    synchronized int setMaxSize(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("max size must be > 0");
        String next = text.length() > capacity ? text.substring(0, capacity) : text;
        check(next, capacity, constraints);
        maxSize = capacity;
        text = next;
        return maxSize;
    }

    synchronized void setConstraints(int flags) {
        int base = flags & TextField.CONSTRAINT_MASK;
        if ((flags & ~(FLAGS | TextField.CONSTRAINT_MASK)) != 0
                || base > TextField.DECIMAL) {
            throw new IllegalArgumentException("Invalid MIDP text constraints: " + flags);
        }
        constraints = flags;
        if (!legal(text, flags)) text = "";
    }

    static boolean legal(String candidate, int flags) {
        if (candidate.isEmpty()) return true;
        return switch (flags & TextField.CONSTRAINT_MASK) {
            case TextField.NUMERIC -> numeric(candidate);
            case TextField.DECIMAL ->
                    candidate.matches("-?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)");
            default -> true; // ANY, EMAILADDR, PHONENUMBER and URL have implementation-defined syntax.
        };
    }

    private static boolean numeric(String candidate) {
        if (!candidate.matches("-?[0-9]+")) return false;
        try {
            Integer.parseInt(candidate);
            return true;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    private static void check(String candidate, int limit, int flags) {
        if (candidate.length() > limit || !legal(candidate, flags)) {
            throw new IllegalArgumentException("Invalid MIDP text value");
        }
    }

    private static void checkArrayRange(int size, int offset, int length) {
        if (offset < 0 || length < 0 || offset > size || length > size - offset) {
            throw new ArrayIndexOutOfBoundsException();
        }
    }
}
