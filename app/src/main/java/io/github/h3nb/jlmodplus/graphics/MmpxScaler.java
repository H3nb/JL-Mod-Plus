/*
 * Copyright 2020 Morgan McGuire & Mara Gagiu.
 * SPDX-License-Identifier: MIT
 *
 * Modified for JL-Mod Plus.
 * Adapted from the C99 MMPX reference:
 * https://github.com/ITotalJustice/mmpx
 * The original source is licensed under MIT.
 */
package io.github.h3nb.jlmodplus.graphics;

/**
 * Style-preserving MMPX pixel-art magnification.
 * The 2x quadrant decisions are preserved at every integer target scale;
 * odd scales resolve the central subpixels symmetrically, continuing
 * matching quadrants rather than inserting gaps in straight edges.
 * No interpolated palette colors are added.
 */
public final class MmpxScaler {
    private MmpxScaler() {}

    private static int pixel(int[] a, int w, int h, int x, int y) {
        return a[Math.max(0, Math.min(h - 1, y)) * w + Math.max(0, Math.min(w - 1, x))];
    }

    private static int luma(int color) {
        return (((color >>> 16) & 255) + ((color >>> 8) & 255) + (color & 255) + 1)
                * (256 - (color >>> 24));
    }

    private static boolean eq2(int a, int b, int c) { return a == b && a == c; }
    private static boolean eq3(int a, int b, int c, int d) { return a == b && a == c && a == d; }
    private static boolean eq4(int a, int b, int c, int d, int e) {
        return a == b && a == c && a == d && a == e;
    }
    private static boolean any3(int a, int b, int c, int d) { return a == b || a == c || a == d; }
    private static boolean none2(int a, int b, int c) { return a != b && a != c; }
    private static boolean none4(int a, int b, int c, int d, int e) {
        return a != b && a != c && a != d && a != e;
    }

    /**
     * Resolve ties at the centre of odd-sized cells. When both neighboring
     * quadrants agree, extend that line; otherwise keep the original pixel.
     * This is palette-preserving and invariant under 90-degree rotations.
     */
    public static int subpixelColor(int source, int topLeft, int topRight,
            int bottomLeft, int bottomRight, int factor, int x, int y) {
        int px = x * 2 + 1;
        int py = y * 2 + 1;
        if (px == factor && py == factor) {
            return topLeft == topRight && topLeft == bottomLeft
                    && topLeft == bottomRight ? topLeft : source;
        }
        if (px == factor) {
            int left = py < factor ? topLeft : bottomLeft;
            int right = py < factor ? topRight : bottomRight;
            return left == right ? left : source;
        }
        if (py == factor) {
            int top = px < factor ? topLeft : topRight;
            int bottom = px < factor ? bottomLeft : bottomRight;
            return top == bottom ? top : source;
        }
        return py < factor ? (px < factor ? topLeft : topRight)
                : (px < factor ? bottomLeft : bottomRight);
    }

    public static int[] scale2x(int[] src, int w, int h) {
        return scale(src, w, h, 2);
    }

    /**
     * Emits the reference MMPX quadrant colors directly at the requested
     * integer density. MMPX's native algorithm remains 2x; this preserves
     * its 2x decisions rather than attempting unverified repeated passes.
     */
    public static int[] scale(int[] src, int w, int h, int factor) {
        long outputPixels = (long) w * h * factor * factor;
        if (w < 1 || h < 1 || factor < 2 || factor > 10
                || src == null || (long) src.length < (long) w * h
                || outputPixels > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid source dimensions or scale");
        }
        int[] dst = new int[(int) outputPixels];
        int stride = w * factor;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int a = pixel(src,w,h,x-1,y-1), b = pixel(src,w,h,x,y-1),
                        c = pixel(src,w,h,x+1,y-1), d = pixel(src,w,h,x-1,y),
                        e = pixel(src,w,h,x,y), f = pixel(src,w,h,x+1,y),
                        g = pixel(src,w,h,x-1,y+1), h0 = pixel(src,w,h,x,y+1),
                        i = pixel(src,w,h,x+1,y+1);
                int j=e, k=e, l=e, m=e;
                if (((a^e)|(b^e)|(c^e)|(d^e)|(f^e)|(g^e)|(h0^e)|(i^e)) != 0) {
                    int p = pixel(src,w,h,x,y-2), q = pixel(src,w,h,x-2,y),
                            r = pixel(src,w,h,x+2,y), s = pixel(src,w,h,x,y+2);
                    int bl=luma(b), dl=luma(d), el=luma(e), fl=luma(f), hl=luma(h0);

                    // 1:1 diagonal rules
                    if (d==b && d!=h0 && d!=f && (el>=dl || e==a) && any3(e,a,c,g)
                            && (el<dl || a!=d || e!=p || e!=q)) j=d;
                    if (b==f && b!=d && b!=h0 && (el>=bl || e==c) && any3(e,a,c,i)
                            && (el<bl || c!=b || e!=p || e!=r)) k=b;
                    if (h0==d && h0!=f && h0!=b && (el>=hl || e==g) && any3(e,a,g,i)
                            && (el<hl || g!=h0 || e!=s || e!=q)) l=h0;
                    if (f==h0 && f!=b && f!=d && (el>=fl || e==i) && any3(e,c,g,i)
                            && (el<fl || i!=h0 || e!=r || e!=s)) m=f;

                    // Corners, intersections and thin lines
                    if (e!=f && eq4(e,c,i,d,q) && eq2(f,b,h0)
                            && f!=pixel(src,w,h,x+3,y)) {k=f;m=f;}
                    if (e!=d && eq4(e,a,g,f,r) && eq2(d,b,h0)
                            && d!=pixel(src,w,h,x-3,y)) {j=d;l=d;}
                    if (e!=h0 && eq4(e,g,i,b,p) && eq2(h0,d,f)
                            && h0!=pixel(src,w,h,x,y+3)) {l=h0;m=h0;}
                    if (e!=b && eq4(e,a,c,h0,s) && eq2(b,d,f)
                            && b!=pixel(src,w,h,x,y-3)) {j=b;k=b;}
                    if (bl<el && eq4(e,g,h0,i,s) && none4(e,a,d,c,f)) {j=b;k=b;}
                    if (hl<el && eq4(e,a,b,c,p) && none4(e,d,g,i,f)) {l=h0;m=h0;}
                    if (fl<el && eq4(e,a,d,g,q) && none4(e,b,c,i,h0)) {k=f;m=f;}
                    if (dl<el && eq4(e,c,f,i,r) && none4(e,b,a,g,h0)) {j=d;l=d;}

                    // 2:1 slope rules
                    if (h0!=b) {
                        if (h0!=a && h0!=e && h0!=c) {
                            if (eq3(h0,g,f,r) && none2(h0,d,pixel(src,w,h,x+2,y-1))) l=m;
                            if (eq3(h0,i,d,q) && none2(h0,f,pixel(src,w,h,x-2,y-1))) m=l;
                        }
                        if (b!=i && b!=g && b!=e) {
                            if (eq3(b,a,f,r) && none2(b,d,pixel(src,w,h,x+2,y+1))) j=k;
                            if (eq3(b,c,d,q) && none2(b,f,pixel(src,w,h,x-2,y+1))) k=j;
                        }
                    }
                    if (f!=d) {
                        if (d!=i && d!=e && d!=c) {
                            if (eq3(d,a,h0,s) && none2(d,b,pixel(src,w,h,x+1,y+2))) j=l;
                            if (eq3(d,g,b,p) && none2(d,h0,pixel(src,w,h,x+1,y-2))) l=j;
                        }
                        if (f!=e && f!=a && f!=g) {
                            if (eq3(f,c,h0,s) && none2(f,b,pixel(src,w,h,x-1,y+2))) k=m;
                            if (eq3(f,i,b,p) && none2(f,h0,pixel(src,w,h,x-1,y-2))) m=k;
                        }
                    }
                }
                int at = (y * factor) * stride + x * factor;
                for (int oy = 0; oy < factor; oy++) {
                    int offset = at + oy * stride;
                    for (int ox = 0; ox < factor; ox++) {
                        dst[offset + ox] = subpixelColor(e, j, k, l, m, factor, ox, oy);
                    }
                }
            }
        }
        return dst;
    }
}
