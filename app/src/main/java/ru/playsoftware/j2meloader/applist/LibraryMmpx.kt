/*
 * MMPX style-preserving pixel-art magnification.
 *
 * Copyright (c) 2020 Morgan McGuire and Mara Gagiu
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 * Kotlin adaptation for JL-Mod Plus. Algorithm reference:
 * Morgan McGuire and Mara Gagiu, "MMPX Style-Preserving Pixel Art Magnification",
 * Journal of Graphics Techniques, 2021.
 */

package ru.playsoftware.j2meloader.applist

/**
 * Applies one exact 2x MMPX pass. Output colors are always copied from the source palette;
 * out-of-bounds neighborhood reads clamp to the nearest edge pixel.
 */
internal fun mmpx2x(
    source: IntArray,
    width: Int,
    height: Int,
): IntArray {
    require(width > 0 && height > 0)
    val sourcePixelCount = Math.multiplyExact(width, height)
    require(source.size >= sourcePixelCount)

    val outputWidth = Math.multiplyExact(width, 2)
    val outputHeight = Math.multiplyExact(height, 2)
    val output = IntArray(Math.multiplyExact(outputWidth, outputHeight))

    fun fetch(x: Int, y: Int): Int {
        val clampedX = x.coerceIn(0, width - 1)
        val clampedY = y.coerceIn(0, height - 1)
        return source[clampedY * width + clampedX]
    }

    for (srcY in 0 until height) {
        for (srcX in 0 until width) {
            val a = fetch(srcX - 1, srcY - 1)
            val b = fetch(srcX, srcY - 1)
            val c = fetch(srcX + 1, srcY - 1)
            val d = fetch(srcX - 1, srcY)
            val e = fetch(srcX, srcY)
            val f = fetch(srcX + 1, srcY)
            val g = fetch(srcX - 1, srcY + 1)
            val h = fetch(srcX, srcY + 1)
            val i = fetch(srcX + 1, srcY + 1)

            var j = e
            var k = e
            var l = e
            var m = e

            if (
                a != e || b != e || c != e || d != e ||
                f != e || g != e || h != e || i != e
            ) {
                val p = fetch(srcX, srcY - 2)
                val s = fetch(srcX, srcY + 2)
                val q = fetch(srcX - 2, srcY)
                val r = fetch(srcX + 2, srcY)
                val bl = mmpxLuma(b)
                val dl = mmpxLuma(d)
                val el = mmpxLuma(e)
                val fl = mmpxLuma(f)
                val hl = mmpxLuma(h)

                // 1:1 slopes.
                if (
                    d == b && d != h && d != f &&
                    (el >= dl || e == a) &&
                    anyEq3(e, a, c, g) &&
                    (el < dl || a != d || e != p || e != q)
                ) {
                    j = d
                }
                if (
                    b == f && b != d && b != h &&
                    (el >= bl || e == c) &&
                    anyEq3(e, a, c, i) &&
                    (el < bl || c != b || e != p || e != r)
                ) {
                    k = b
                }
                if (
                    h == d && h != f && h != b &&
                    (el >= hl || e == g) &&
                    anyEq3(e, a, g, i) &&
                    (el < hl || g != h || e != s || e != q)
                ) {
                    l = h
                }
                if (
                    f == h && f != b && f != d &&
                    (el >= fl || e == i) &&
                    anyEq3(e, c, g, i) &&
                    (el < fl || i != h || e != r || e != s)
                ) {
                    m = f
                }

                // Intersections and thin features.
                if (
                    e != f && allEq4(e, c, i, d, q) && allEq2(f, b, h) &&
                    f != fetch(srcX + 3, srcY)
                ) {
                    k = f
                    m = f
                }
                if (
                    e != d && allEq4(e, a, g, f, r) && allEq2(d, b, h) &&
                    d != fetch(srcX - 3, srcY)
                ) {
                    j = d
                    l = d
                }
                if (
                    e != h && allEq4(e, g, i, b, p) && allEq2(h, d, f) &&
                    h != fetch(srcX, srcY + 3)
                ) {
                    l = h
                    m = h
                }
                if (
                    e != b && allEq4(e, a, c, h, s) && allEq2(b, d, f) &&
                    b != fetch(srcX, srcY - 3)
                ) {
                    j = b
                    k = b
                }
                if (bl < el && allEq4(e, g, h, i, s) && noneEq4(e, a, d, c, f)) {
                    j = b
                    k = b
                }
                if (hl < el && allEq4(e, a, b, c, p) && noneEq4(e, d, g, i, f)) {
                    l = h
                    m = h
                }
                if (fl < el && allEq4(e, a, d, g, q) && noneEq4(e, b, c, i, h)) {
                    k = f
                    m = f
                }
                if (dl < el && allEq4(e, c, f, i, r) && noneEq4(e, b, a, g, h)) {
                    j = d
                    l = d
                }

                // 2:1 slopes.
                if (h != b) {
                    if (h != a && h != e && h != c) {
                        if (
                            allEq3(h, g, f, r) &&
                            noneEq2(h, d, fetch(srcX + 2, srcY - 1))
                        ) {
                            l = m
                        }
                        if (
                            allEq3(h, i, d, q) &&
                            noneEq2(h, f, fetch(srcX - 2, srcY - 1))
                        ) {
                            m = l
                        }
                    }
                    if (b != i && b != g && b != e) {
                        if (
                            allEq3(b, a, f, r) &&
                            noneEq2(b, d, fetch(srcX + 2, srcY + 1))
                        ) {
                            j = k
                        }
                        if (
                            allEq3(b, c, d, q) &&
                            noneEq2(b, f, fetch(srcX - 2, srcY + 1))
                        ) {
                            k = j
                        }
                    }
                }

                if (f != d) {
                    if (d != i && d != e && d != c) {
                        if (
                            allEq3(d, a, h, s) &&
                            noneEq2(d, b, fetch(srcX + 1, srcY + 2))
                        ) {
                            j = l
                        }
                        if (
                            allEq3(d, g, b, p) &&
                            noneEq2(d, h, fetch(srcX + 1, srcY - 2))
                        ) {
                            l = j
                        }
                    }
                    if (f != e && f != a && f != g) {
                        if (
                            allEq3(f, c, h, s) &&
                            noneEq2(f, b, fetch(srcX - 1, srcY + 2))
                        ) {
                            k = m
                        }
                        if (
                            allEq3(f, i, b, p) &&
                            noneEq2(f, h, fetch(srcX - 1, srcY - 2))
                        ) {
                            m = k
                        }
                    }
                }
            }

            val dstX = srcX * 2
            val dstY = srcY * 2
            val topOffset = dstY * outputWidth + dstX
            val bottomOffset = topOffset + outputWidth
            output[topOffset] = j
            output[topOffset + 1] = k
            output[bottomOffset] = l
            output[bottomOffset + 1] = m
        }
    }

    return output
}

private fun mmpxLuma(pixel: Int): Int {
    val alpha = (pixel ushr 24) and 0xff
    val red = (pixel ushr 16) and 0xff
    val green = (pixel ushr 8) and 0xff
    val blue = pixel and 0xff
    return (red + green + blue + 1) * (256 - alpha)
}

private fun allEq2(base: Int, a0: Int, a1: Int): Boolean = base == a0 && base == a1

private fun allEq3(base: Int, a0: Int, a1: Int, a2: Int): Boolean =
    base == a0 && base == a1 && base == a2

private fun allEq4(base: Int, a0: Int, a1: Int, a2: Int, a3: Int): Boolean =
    base == a0 && base == a1 && base == a2 && base == a3

private fun anyEq3(base: Int, a0: Int, a1: Int, a2: Int): Boolean =
    base == a0 || base == a1 || base == a2

private fun noneEq2(base: Int, a0: Int, a1: Int): Boolean = base != a0 && base != a1

private fun noneEq4(base: Int, a0: Int, a1: Int, a2: Int, a3: Int): Boolean =
    base != a0 && base != a1 && base != a2 && base != a3
