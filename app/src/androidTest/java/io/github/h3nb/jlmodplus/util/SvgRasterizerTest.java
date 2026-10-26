package io.github.h3nb.jlmodplus.util;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.Color;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class SvgRasterizerTest {
    private static ByteArrayInputStream svg(String source) {
        return new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8));
    }

    @Test public void viewBoxScalesWithoutCroppingOrLosingTransparency() throws Exception {
        SvgRasterizer.Document document = SvgRasterizer.read(svg(
                "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 20 10'>"
                + "<rect width='10' height='10' fill='#ff0000'/></svg>"));
        Bitmap result = document.renderWithin(100, 100);
        try {
            assertEquals(100, result.getWidth());
            assertEquals(50, result.getHeight());
            assertEquals(Color.RED, result.getPixel(20, 25));
            assertEquals(Color.TRANSPARENT, result.getPixel(80, 25));
        } finally {
            result.recycle();
        }
    }

    @Test public void explicitDimensionsAndViewBoxScaleAllCornersInSmallPreviews()
            throws Exception {
        // Real-world SVG exporters may set both a viewBox and fixed root dimensions.
        // Without a normalized viewport AndroidSVG renders just the top-left crop.
        for (boolean explicitSize : new boolean[]{false, true}) {
            for (String accent : new String[]{"#fadd6a", "#5ef7ff"}) {
                String attributes = explicitSize ? " width='256' height='256'" : "";
                SvgRasterizer.Document document = SvgRasterizer.read(svg(
                        "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 256 256'"
                        + attributes + ">"
                        + "<rect width='256' height='256' fill='#172545'/>"
                        + "<rect x='128' y='128' width='128' height='128' fill='"
                        + accent + "'/></svg>"));
                for (int size : new int[]{24, 64}) {
                    Bitmap result = document.renderWithin(size, size);
                    try {
                        assertEquals(size, result.getWidth());
                        assertEquals(size, result.getHeight());
                        assertEquals("Top-left should remain intact",
                                Color.rgb(0x17, 0x25, 0x45),
                                result.getPixel(size / 4, size / 4));
                        assertEquals("Bottom-right must not be cropped",
                                Color.parseColor(accent),
                                result.getPixel(size * 3 / 4, size * 3 / 4));
                    } finally {
                        result.recycle();
                    }
                }
            }
        }
    }

    @Test public void dimensionsWithoutViewBoxCanBeScaled() throws Exception {
        SvgRasterizer.Document document = SvgRasterizer.read(svg(
                "<svg xmlns='http://www.w3.org/2000/svg' width='40' height='20'>"
                + "<rect width='40' height='20' fill='blue'/></svg>"));
        Bitmap result = document.renderWithin(120, 120);
        try {
            assertEquals(120, result.getWidth());
            assertEquals(60, result.getHeight());
            assertEquals(Color.BLUE, result.getPixel(60, 30));
        } finally {
            result.recycle();
        }
    }

    @Test public void documentDeclarationsAndMissingGeometryFailClosed() throws Exception {
        try {
            SvgRasterizer.read(svg("<!DOCTYPE svg [<!ENTITY x 'bad'>]>"
                    + "<svg xmlns='http://www.w3.org/2000/svg' width='8' height='8'/>"));
            fail("DTD must be rejected");
        } catch (IOException expected) { }
        try {
            SvgRasterizer.read(svg("<svg xmlns='http://www.w3.org/2000/svg'/>"));
            fail("Unknown canvas dimensions must be rejected");
        } catch (IOException expected) { }
    }
}
