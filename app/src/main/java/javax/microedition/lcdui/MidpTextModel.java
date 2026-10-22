/* SPDX-License-Identifier: Apache-2.0 */
package javax.microedition.lcdui;

/**
 * The MIDP text value contract, independent of Android's editor and input method.
 * Mutations validate before publishing a new value; a rejected mutation is atomic.
 */
final class MidpTextModel {
    private String text = "";
    private int maxSize = 1;
    private int constraints = TextField.ANY;

    synchronized String getString() { return text; }
    synchronized int size() { return text.length(); }
    synchronized int getMaxSize() { return maxSize; }
    synchronized int getConstraints() { return constraints; }

    synchronized void setString(String value) {
        String candidate = value == null ? "" : value;
        checkCandidate(candidate, maxSize, constraints);
        text = candidate;
    }

    synchronized void setChars(char[] data, int offset, int length) {
        if (data == null) { setString(null); return; }
        checkRange(data.length, offset, length);
        setString(new String(data, offset, length));
    }

    synchronized int getChars(char[] dst) {
        if (dst == null) throw new NullPointerException("data");
        if (dst.length < text.length()) throw new ArrayIndexOutOfBoundsException();
        text.getChars(0, text.length(), dst, 0);
        return text.length();
    }

    synchronized void insert(String value, int position) {
        if (value == null) throw new NullPointerException("src");
        int at = Math.max(0, Math.min(position, text.length()));
        setString(text.substring(0, at) + value + text.substring(at));
    }

    synchronized void insert(char[] data, int offset, int length, int position) {
        if (data == null) throw new NullPointerException("data");
        checkRange(data.length, offset, length);
        insert(new String(data, offset, length), position);
    }

    synchronized void delete(int offset, int length) {
        checkStringRange(text.length(), offset, length);
        setString(text.substring(0, offset) + text.substring(offset + length));
    }

    synchronized int setMaxSize(int requested) {
        if (requested <= 0) throw new IllegalArgumentException("maxSize must be positive");
        String candidate = text.length() > requested ? text.substring(0, requested) : text;
        checkCandidate(candidate, requested, constraints);
        maxSize = requested;
        text = candidate;
        return maxSize;
    }

    synchronized void setConstraints(int requested) {
        if (!validConstraints(requested)) throw new IllegalArgumentException("Invalid constraints");
        if (!validText(text, requested)) text = "";
        constraints = requested;
    }

    static boolean validConstraints(int requested) {
        int flags = TextField.PASSWORD | TextField.UNEDITABLE | TextField.SENSITIVE
                | TextField.NON_PREDICTIVE | TextField.INITIAL_CAPS_WORD
                | TextField.INITIAL_CAPS_SENTENCE;
        int kind = requested & TextField.CONSTRAINT_MASK;
        return (requested & ~(flags | TextField.CONSTRAINT_MASK)) == 0
                && kind >= TextField.ANY && kind <= TextField.DECIMAL;
    }

    static boolean validText(String text, int constraints) {
        if (text.isEmpty()) return true;
        return switch (constraints & TextField.CONSTRAINT_MASK) {
            case TextField.NUMERIC -> {
                if (!text.matches("-?[0-9]+")) yield false;
                try { Integer.parseInt(text); yield true; }
                catch (NumberFormatException ignored) { yield false; }
            }
            case TextField.DECIMAL ->
                text.matches("-?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)");
            case TextField.ANY -> true;
            default -> text.indexOf('\n') < 0 && text.indexOf('\r') < 0;
        };
    }

    /** Incomplete user drafts are never exposed through the MIDP getter. */
    static boolean incompleteDraft(String value, int constraints) {
        return switch (constraints & TextField.CONSTRAINT_MASK) {
            case TextField.NUMERIC -> value.equals("-");
            case TextField.DECIMAL -> value.equals("-") || value.equals(".") || value.equals("-.");
            default -> false;
        };
    }

    private static void checkCandidate(String candidate, int capacity, int constraints) {
        if (candidate.length() > capacity || !validText(candidate, constraints)) {
            throw new IllegalArgumentException("Text violates capacity or input constraints");
        }
    }

    private static void checkRange(int size, int offset, int length) {
        if (offset < 0 || length < 0 || offset > size || length > size - offset) {
            throw new ArrayIndexOutOfBoundsException();
        }
    }

    private static void checkStringRange(int size, int offset, int length) {
        if (offset < 0 || length < 0 || offset > size || length > size - offset) {
            throw new StringIndexOutOfBoundsException();
        }
    }
}
