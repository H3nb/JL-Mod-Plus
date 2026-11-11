// SPDX-License-Identifier: Apache-2.0
package javax.microedition.lcdui;

import io.github.h3nb.jlmodplus.graphics.MmpxScaler;
import io.github.h3nb.jlmodplus.graphics.XbrzFreescaleScaler;

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
        int[] enlarged=XbrzFreescaleScaler.scale(source, 4, 4, 4);
        assertEquals(256,enlarged.length);
        for(int pixel:enlarged)assertEquals(red,pixel);
        int[] opaque={0xff000000,0xffffffff,0xffffffff,0xff000000};
        for(int pixel:XbrzFreescaleScaler.scale(opaque, 2, 2, 4)) {
            assertEquals(255,pixel>>>24);
        }
    }

    @Test public void xbrPremultipliedAlphaAvoidsTransparentColorFringes() {
        assertEquals(0x800000ff, XbrzFreescaleScaler.blend(0x00ff0000, 0xff0000ff, 0.5f));
        assertEquals(0xff808080, XbrzFreescaleScaler.blend(0xff000000, 0xffffffff, 0.5f));
        try{
            XbrzFreescaleScaler.scale(new int[4], 3, 3, 4);
            fail("Expected invalid size rejection");
        }catch(IllegalArgumentException expected){}
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

    @Test public void edgeAwareDirectIntegerScalesStayOpaqueAndRespectSource() {
        int[] source = {
            0xff000000, 0xff000000, 0xffffffff,
            0xff000000, 0xffffffff, 0xffffffff,
            0xffffffff, 0xffffffff, 0xff000000
        };
        int[] unchanged = source.clone();
        for (int factor = 2; factor <= 10; factor++) {
            int[] output = XbrzFreescaleScaler.scale(source, 3, 3, factor);
            assertEquals(9 * factor * factor, output.length);
            for (int color : output) assertEquals(255, color >>> 24);
        }
        assertArrayEquals(unchanged, source);
    }

    @Test public void edgeAwareCornerChoicesAreRotationConsistent() {
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
            int[] original=XbrzFreescaleScaler.scale(source,3,3,factor);
            int[] turned=XbrzFreescaleScaler.scale(rotated,3,3,factor);
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

    @Test public void edgeAwareProducesScaleIndependentSmoothDiagonal() {
        int black=0xff080808, white=0xfffafafa;
        int[] input=new int[25];
        for(int y=0;y<5;y++) for(int x=0;x<5;x++) {
            input[y*5+x] = x>=y ? white : black;
        }
        for(int factor: new int[]{2,3,5,10}) {
            int[] pixels=XbrzFreescaleScaler.scale(input,5,5,factor);
            boolean hasReconstructedEdge=false;
            for(int pixel:pixels) {
                assertEquals(0xff,pixel>>>24);
                if(pixel!=white && pixel!=black) hasReconstructedEdge=true;
            }
            assertTrue(hasReconstructedEdge, "Missing edge interpolation at "+factor+"x");
        }
    }

    @Test public void directScalersRejectUnsupportedScaleAndArrayBounds() {
        assertThrows(IllegalArgumentException.class, () -> MmpxScaler.scale(new int[4], 2, 2, 1));
        assertThrows(IllegalArgumentException.class, () -> MmpxScaler.scale(new int[4], 2, 2, 11));
        assertThrows(IllegalArgumentException.class, () -> XbrzFreescaleScaler.scale(new int[4], 2, 2, 0));
        assertThrows(IllegalArgumentException.class, () -> XbrzFreescaleScaler.scale(new int[3], 2, 2, 5));
    }

    @Test public void uniformCellsDoNotChangeAcrossEnhancementScales() {
        for (int color : new int[]{0xff112233, 0x804488cc, 0x00000000}) {
            int[] pixels = new int[25];
            java.util.Arrays.fill(pixels, color);
            for (int factor : new int[]{2, 3, 4, 10}) {
                for (int output : MmpxScaler.scale(pixels, 5, 5, factor)) {
                    assertEquals(color, output);
                }
                for (int output : XbrzFreescaleScaler.scale(pixels, 5, 5, factor)) {
                    assertEquals(color, output);
                }
            }
        }
    }
}
