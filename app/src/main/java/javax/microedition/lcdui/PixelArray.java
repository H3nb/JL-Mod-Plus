// SPDX-License-Identifier: Apache-2.0
package javax.microedition.lcdui;

/** Validates a nonempty rectangular pixel array before any read or write. */
final class PixelArray {
    private PixelArray() {}

    static void checkBounds(int length, int offset, int stride, int width, int height) {
        long lastRow = (long) (height - 1) * stride;
        long first = offset + Math.min(0L, lastRow);
        long last = offset + Math.max(0L, lastRow) + width - 1;
        if (first < 0 || last >= length) throw new ArrayIndexOutOfBoundsException();
    }
}
