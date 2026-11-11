// SPDX-License-Identifier: Apache-2.0
package javax.microedition.lcdui;

import static org.junit.Assert.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import javax.microedition.lcdui.game.Sprite;
import io.github.h3nb.jlmodplus.graphics.ImageEnhancementPolicy;
import io.github.h3nb.jlmodplus.graphics.MmpxScaler;
import io.github.h3nb.jlmodplus.graphics.XbrzFreescaleScaler;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real Bitmap/Canvas 2D integration: independent of the presenter shader. */
@RunWith(AndroidJUnit4.class)
public class BitmapUpscalingGraphicsTest {
    private static final int RED=0xffff0000, BLUE=0xff0000ff;

    @Test public void enhancementCacheBudgetIsBoundedByHeapTier() {
        assertEquals(4 * 1024 * 1024, BitmapUpscaling.cacheBudgetBytes(32L << 20));
        assertEquals(8 * 1024 * 1024, BitmapUpscaling.cacheBudgetBytes(128L << 20));
        assertEquals(16 * 1024 * 1024, BitmapUpscaling.cacheBudgetBytes(512L << 20));
    }

    @Test public void enhancementNeverSamplesAdjacentAtlasTile() {
        int[] atlas={RED,RED,BLUE,BLUE,RED,RED,BLUE,BLUE};
        Image source=Image.createRGBImage(atlas,4,2,true);
        try {
            for(int factor: new int[]{2,3,5,10}) {
                for(int mode: new int[]{BitmapUpscaling.NEAREST,BitmapUpscaling.BILINEAR,
                        BitmapUpscaling.MMPX,BitmapUpscaling.XBRZ}) {
                    Graphics.configureBitmapUpscaling(mode);
                    Image target=Image.createDisplayTarget(8,6,factor);
                    try {
                        Graphics g=target.getGraphics();
                        g.drawRegion(source,0,0,2,2,Sprite.TRANS_NONE,
                                1,1,Graphics.LEFT|Graphics.TOP);
                        for(int y=factor;y<factor*3;y++) for(int x=factor;x<factor*3;x++) {
                            assertEquals("Atlas bleed in mode "+mode+" scale "+factor,
                                    RED,target.getBitmap().getPixel(x,y));
                        }
                        assertEquals(8,target.getWidth());
                        assertEquals(8*factor,target.getBitmap().getWidth());
                    } finally { target.getBitmap().recycle(); }
                }
            }
        } finally {
            Graphics.configureBitmapUpscaling(BitmapUpscaling.NEAREST);
        }
    }

    @Test public void enhancedOutputMatchesDirectSourcePixelScale() {
        int[] raw={0xff000000,0xffffffff,0xffff0000,0xff0000ff};
        Image source=Image.createRGBImage(raw,2,2,true);
        try {
            for(int factor: new int[]{2,3,4}) {
                for(int mode: new int[]{BitmapUpscaling.MMPX,BitmapUpscaling.XBRZ}) {
                    int[] expected=mode==BitmapUpscaling.MMPX
                            ?MmpxScaler.scale(raw,2,2,factor)
                            :XbrzFreescaleScaler.scale(raw,2,2,factor);
                    Graphics.configureBitmapUpscaling(mode);
                    Image target=Image.createDisplayTarget(2,2,factor);
                    try {
                        target.getGraphics().drawImage(source,0,0,Graphics.TOP|Graphics.LEFT);
                        int width=2*factor;
                        for(int y=0;y<width;y++) for(int x=0;x<width;x++) {
                            assertEquals("Scale "+factor+" mode "+mode+" x="+x+" y="+y,
                                    expected[y*width+x],target.getBitmap().getPixel(x,y));
                        }
                    } finally { target.getBitmap().recycle(); }
                }
            }
        } finally {
            Graphics.configureBitmapUpscaling(BitmapUpscaling.NEAREST);
        }
    }

    @Test public void bitmapModesLeaveMutableImageUnmodified() {
        Graphics.configureBitmapUpscaling(BitmapUpscaling.XBRZ);
        Image source=Image.createImage(2,2);
        Image target=Image.createDisplayTarget(6,6,4);
        try {
            Graphics original=source.getGraphics();
            original.setColor(0x00ff00);
            original.fillRect(0,0,2,2);
            target.getGraphics().drawImage(source,2,2,Graphics.LEFT|Graphics.TOP);
            assertEquals(0xff00ff00,source.getBitmap().getPixel(1,1));
            assertEquals(0xff00ff00,target.getBitmap().getPixel(9,9));
        } finally {
            Graphics.configureBitmapUpscaling(BitmapUpscaling.NEAREST);
            source.getBitmap().recycle();
            target.getBitmap().recycle();
        }
    }

    @Test public void scaledFontRenderingIsUnaffectedByChosenSampler() {
        Graphics.configureBitmapUpscaling(BitmapUpscaling.MMPX);
        Image target=Image.createDisplayTarget(40,24,4);
        try {
            Graphics g=target.getGraphics();
            int expectedWidth=g.getFont().stringWidth("Bitmap");
            g.setColor(0x000000);
            g.drawString("Bitmap",0,0,Graphics.TOP|Graphics.LEFT);
            assertEquals(expectedWidth,g.getFont().stringWidth("Bitmap"));
            assertEquals(40,target.getWidth());
            assertEquals(160,target.getBitmap().getWidth());
        } finally {
            Graphics.configureBitmapUpscaling(BitmapUpscaling.NEAREST);
            target.getBitmap().recycle();
        }
    }
}
