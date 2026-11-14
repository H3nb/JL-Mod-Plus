// SPDX-License-Identifier: Apache-2.0
package javax.microedition.lcdui;

import io.github.h3nb.jlmodplus.graphics.MmpxScaler;
import io.github.h3nb.jlmodplus.graphics.XbrFreeScaler;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

public class BitmapUpscalingScalersTest {
    @Test public void mmpxSolidColorAndPaletteAndTransparency() {
        int transparent=0x00000000, red=0xffff0000, blue=0xff0000ff;
        int[] solid=new int[9];
        java.util.Arrays.fill(solid,red);
        int[] scaled=MmpxScaler.scale2x(solid,3,3);
        assertEquals(36,scaled.length);
        for(int pixel:scaled)assertEquals(red,pixel);

        int[] source={transparent,red,transparent,red,blue,red,transparent,red,transparent};
        for(int color:MmpxScaler.scale2x(source,3,3)) {
            assertTrue(color==red||color==blue||color==transparent);
        }
    }

    @Test public void mmpxEdgePixelsAndDegenerateInputs() {
        assertArrayEquals(new int[]{0x80445566,0x80445566,0x80445566,0x80445566},
                MmpxScaler.scale2x(new int[]{0x80445566},1,1));
        try {
            MmpxScaler.scale2x(new int[0],0,3);
            fail("Expected invalid size rejection");
        } catch(IllegalArgumentException expected) { }
    }

    @Test public void xbrSolidColorRemainsUniformAndPreservesAlpha() {
        int red=0x80ff2200;
        int[] source=new int[16];
        java.util.Arrays.fill(source,red);
        int[] enlarged=XbrFreeScaler.scale(source, 4, 4, 4);
        assertEquals(256,enlarged.length);
        for(int pixel:enlarged)assertEquals(red,pixel);
        int[] opaque={0xff000000,0xffffffff,0xffffffff,0xff000000};
        for(int pixel:XbrFreeScaler.scale(opaque, 2, 2, 4)) {
            assertEquals(255,pixel>>>24);
        }
    }

    @Test public void xbrPremultipliedAlphaAvoidsTransparentColorFringes() {
        int[] source = new int[25];
        for (int y = 0; y < 5; y++) for (int x = 0; x < 5; x++) {
            source[y * 5 + x] = x == 2 ? 0xff0000ff : 0x00ff0000;
        }
        for (int pixel : XbrFreeScaler.scale(source, 5, 5, 4)) {
            if ((pixel >>> 24) != 0) {
                assertEquals(0x000000ff, pixel & 0x00ffffff);
            }
        }
        assertThrows(IllegalArgumentException.class,
                () -> XbrFreeScaler.scale(new int[4], 3, 3, 4));
    }
    @Test public void mmpxDirectIntegerScalesPreserveReferencePaletteAndSource() {
        int[] source = {
            0xffff0000, 0xff00ff00, 0xff0000ff,
            0xff00ff00, 0x00000000, 0xff00ff00,
            0xff0000ff, 0xff00ff00, 0xffff0000
        };
        int[] unchanged = source.clone();
        for (int factor = 2; factor <= 10; factor++) {
            int[] output = MmpxScaler.scale(source, 3, 3, factor);
            assertEquals(9 * factor * factor, output.length);
            for (int color : output) {
                assertTrue(color == 0xffff0000 || color == 0xff00ff00
                        || color == 0xff0000ff || color == 0x00000000);
            }
            // The four outermost corners of each magnified source cell
            // must agree with the original MMPX 2x reference decisions.
            int[] native2x = MmpxScaler.scale2x(source, 3, 3);
            int stride = 3 * factor;
            for (int sy = 0; sy < 3; sy++) for (int sx = 0; sx < 3; sx++) {
                int left = sx * factor, top = sy * factor;
                int two = sy * 2 * 6 + sx * 2;
                assertEquals(native2x[two], output[top * stride + left]);
                assertEquals(native2x[two + 1], output[top * stride + left + factor - 1]);
                assertEquals(native2x[two + 6],
                        output[(top + factor - 1) * stride + left]);
                assertEquals(native2x[two + 7],
                        output[(top + factor - 1) * stride + left + factor - 1]);
            }
        }
        assertArrayEquals(unchanged, source);
    }

    @Test public void mmpxOddScaleTiesKeepStraightLinesAndRotationSymmetry() {
        int black=0xff000000, red=0xffff0000, green=0xff00ff00;
        for(int factor=2;factor<=10;factor++) {
            int mid=factor/2;
            if((factor&1)!=0) {
                assertEquals(red,MmpxScaler.subpixelColor(
                        black,red,red,green,green,factor,mid,0));
                assertEquals(green,MmpxScaler.subpixelColor(
                        black,red,red,green,green,factor,mid,factor-1));
                assertEquals(black,MmpxScaler.subpixelColor(
                        black,red,red,green,green,factor,mid,mid));
                assertEquals(red,MmpxScaler.subpixelColor(
                        black,red,red,red,red,factor,mid,mid));
            }
            int tl=0xffff0000,tr=0xff00ff00,bl=0xff0000ff,br=0xffffffff;
            for(int y=0;y<factor;y++) for(int x=0;x<factor;x++) {
                int before=MmpxScaler.subpixelColor(black,tl,tr,bl,br,factor,x,y);
                int rotated=MmpxScaler.subpixelColor(
                        black,bl,tl,br,tr,factor,factor-1-y,x);
                assertEquals(before, rotated, "MMPX cell rotation at "+factor+"x");
            }
        }
    }

    @Test public void xbrDirectIntegerScalesStayOpaqueAndRespectSource() {
        int[] source = {
            0xff000000, 0xff000000, 0xffffffff,
            0xff000000, 0xffffffff, 0xffffffff,
            0xffffffff, 0xffffffff, 0xff000000
        };
        int[] unchanged = source.clone();
        for (int factor = 2; factor <= 10; factor++) {
            int[] output = XbrFreeScaler.scale(source, 3, 3, factor);
            assertEquals(9 * factor * factor, output.length);
            for (int color : output) assertEquals(255, color >>> 24);
        }
        assertArrayEquals(unchanged, source);
    }

    @Test public void xbrCornerChoicesAreRotationConsistent() {
        int black=0xff080808, white=0xfffafafa, blue=0xff2060ff;
        int[] source={
            black,black,white,
            black,blue,white,
            white,white,blue
        };
        int[] rotated=new int[9];
        for(int y=0;y<3;y++) for(int x=0;x<3;x++) {
            rotated[x*3+(2-y)]=source[y*3+x];
        }
        for(int factor: new int[]{2,3,4,5,10}) {
            int side=3*factor;
            int[] original=XbrFreeScaler.scale(source,3,3,factor);
            int[] turned=XbrFreeScaler.scale(rotated,3,3,factor);
            for(int y=0;y<side;y++) for(int x=0;x<side;x++) {
                int a=original[y*side+x];
                int b=turned[x*side+(side-1-y)];
                for(int shift: new int[]{0,8,16,24}) {
                    assertTrue(Math.abs(((a>>>shift)&255)-((b>>>shift)&255))<=2,
                            "Rotation asymmetry at "+factor+"x x="+x+" y="+y);
                }
            }
        }
    }

    @Test public void xbrProducesScaleIndependentSmoothDiagonal() {
        int black=0xff080808, white=0xfffafafa;
        int[] input=new int[25];
        for(int y=0;y<5;y++) for(int x=0;x<5;x++) {
            input[y*5+x] = x>=y ? white : black;
        }
        for(int factor: new int[]{2,3,5,10}) {
            int[] pixels=XbrFreeScaler.scale(input,5,5,factor);
            boolean hasReconstructedEdge=false;
            for(int pixel:pixels) {
                assertEquals(0xff,pixel>>>24);
                if(pixel!=white && pixel!=black) hasReconstructedEdge=true;
            }
            assertTrue(hasReconstructedEdge, "Missing edge interpolation at "+factor+"x");
        }
    }


    @Test public void mmpxTwoXMatchesIndependentCReferenceFixtures() throws Exception {
        // Golden ARGB bytes generated by the MIT MMPX C rule set, independently
        // of this Java implementation, with the intended clamped edge sampling.
        int[] palette = {
                0x00000000, 0x00ff0000, 0x80808080, 0xff000000,
                0xffffffff, 0xffff0000, 0xff00ff00, 0xff0000ff
        };
        int[] source = new int[13 * 11];
        int state = 0x230;
        for (int i = 0; i < source.length; i++) {
            state ^= state << 13;
            state ^= state >>> 17;
            state ^= state << 5;
            source[i] = palette[state & 7];
        }
        assertEquals("d100841378938dce635476aa46ef843565d8148a214226b50817671fc89be290",
                sha256Argb(MmpxScaler.scale2x(source, 13, 11)));

        int[] junction = new int[7 * 7];
        for (int y = 0; y < 7; y++) for (int x = 0; x < 7; x++) {
            junction[y * 7 + x] = x == y || x + y == 6 ? 0xffffaa00
                    : x == 3 || y == 3 ? 0xff000000
                    : x > y ? 0xffffffff : 0x00000000;
        }
        assertEquals("a1a7bb3e665c194d54c72bfda84edd4edae3a54303527545f209df2a8d63865f",
                sha256Argb(MmpxScaler.scale2x(junction, 7, 7)));
    }

    @Test public void xbrCornersBlendWithoutChangingOpaqueAlpha() {
        int[] source = {
                0xff000000, 0xff000000, 0xffffffff,
                0xff000000, 0xffffffff, 0xffffffff,
                0xffffffff, 0xffffffff, 0xffffffff
        };
        int[] output = XbrFreeScaler.scale(source, 3, 3, 4);
        int blended = 0;
        for (int pixel : output) {
            assertEquals(255, pixel >>> 24);
            if (pixel != 0xff000000 && pixel != 0xffffffff) blended++;
        }
        assertTrue(blended > 0, "A diagonal should blend at its corners");
    }


    @Test public void xbrUsesReferenceWeightedEdgeDirection() {
        String[] pattern = {
                "00011",
                "01111",
                "01100",
                "00011",
                "01111"
        };
        int[] source = new int[25];
        for (int y = 0; y < 5; y++) {
            for (int x = 0; x < 5; x++) {
                source[y * 5 + x] = pattern[y].charAt(x) == '1'
                        ? 0xffffffff : 0xff000000;
            }
        }
        int[] enlarged = XbrFreeScaler.scale(source, 5, 5, 4);
        assertEquals(0xffffffff, enlarged[8 * 20 + 8]);
        assertEquals(0xff000000, enlarged[11 * 20 + 11]);
    }

    @Test public void xbrPreservesIsolatedOnePixelDetail() {
        int[] source = new int[25];
        java.util.Arrays.fill(source, 0xfff0f5fa);
        source[12] = 0xff0b1220;
        for (int factor : new int[]{2, 3, 4, 10}) {
            int[] output = XbrFreeScaler.scale(source, 5, 5, factor);
            int center = 2 * factor + factor / 2;
            assertEquals(0xff0b1220, output[center * 5 * factor + center]);
            assertEquals(0xfff0f5fa, output[0]);
        }
    }

    @Test public void xbrStreamingMatchesFullOutputAndMirrors() {
        int width = 6, height = 5;
        int[] colors = {0xff000000, 0xffffffff, 0xffff0033, 0xff22bbaa,
                0x804466ff, 0x00ff0000};
        int[] source = new int[width * height];
        java.util.Random random = new java.util.Random(245);
        for (int i = 0; i < source.length; i++) {
            source[i] = colors[random.nextInt(colors.length)];
        }
        int[] mirroredSource = new int[source.length];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            mirroredSource[y * width + width - 1 - x] = source[y * width + x];
        }
        for (int factor : new int[]{2, 3, 5, 10}) {
            int scaledWidth = width * factor;
            int scaledHeight = height * factor;
            int[] full = XbrFreeScaler.scale(source, width, height, factor);
            int[] streamed = new int[full.length];
            int[] nextRow = {0};
            XbrFreeScaler.scaleRows(source, width, height, factor, (start, row, count) -> {
                assertEquals(nextRow[0], start);
                System.arraycopy(row, 0, streamed, start * scaledWidth, count * scaledWidth);
                nextRow[0] += count;
            });
            assertEquals(scaledHeight, nextRow[0]);
            assertArrayEquals(full, streamed);
            int[] mirrored = XbrFreeScaler.scale(mirroredSource, width, height, factor);
            for (int y = 0; y < scaledHeight; y++) for (int x = 0; x < scaledWidth; x++) {
                assertEquals(full[y * scaledWidth + x],
                        mirrored[y * scaledWidth + scaledWidth - 1 - x]);
            }
        }
    }

    private static String sha256Argb(int[] pixels) throws Exception {
        java.security.MessageDigest hash = java.security.MessageDigest.getInstance("SHA-256");
        for (int color : pixels) {
            hash.update((byte) (color >>> 24));
            hash.update((byte) (color >>> 16));
            hash.update((byte) (color >>> 8));
            hash.update((byte) color);
        }
        return java.util.HexFormat.of().formatHex(hash.digest());
    }

    @Test public void directScalersRejectUnsupportedScaleAndArrayBounds() {
        assertThrows(IllegalArgumentException.class, () -> MmpxScaler.scale(new int[4], 2, 2, 1));
        assertThrows(IllegalArgumentException.class, () -> MmpxScaler.scale(new int[4], 2, 2, 11));
        assertThrows(IllegalArgumentException.class, () -> XbrFreeScaler.scale(new int[4], 2, 2, 0));
        assertThrows(IllegalArgumentException.class, () -> XbrFreeScaler.scale(new int[3], 2, 2, 5));
    }

    @Test public void uniformCellsDoNotChangeAcrossEnhancementScales() {
        for (int color : new int[]{0xff112233, 0x804488cc, 0x00000000}) {
            int[] pixels = new int[25];
            java.util.Arrays.fill(pixels, color);
            for (int factor : new int[]{2, 3, 4, 10}) {
                for (int output : MmpxScaler.scale(pixels, 5, 5, factor)) {
                    assertEquals(color, output);
                }
                for (int output : XbrFreeScaler.scale(pixels, 5, 5, factor)) {
                    assertEquals(color, output);
                }
            }
        }
    }
}
