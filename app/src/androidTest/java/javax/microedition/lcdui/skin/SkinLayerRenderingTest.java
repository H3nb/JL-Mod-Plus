/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package javax.microedition.lcdui.skin;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import io.github.h3nb.jlmodplus.config.ProfileModel;
import io.github.h3nb.jlmodplus.util.SvgRasterizer;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import javax.microedition.lcdui.graphics.CanvasWrapper;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class SkinLayerRenderingTest {
    private static final int FRAME = Color.rgb(27, 68, 102);
    private static final int GAME = Color.RED;

    private static Bitmap frame(boolean rounded, boolean transparentOutside) {
        Bitmap image = Bitmap.createBitmap(240, 320, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(image);
        Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
        frame.setColor(FRAME);
        if (transparentOutside) {
            canvas.drawRoundRect(10, 10, 230, 310, 15, 15, frame);
        } else {
            canvas.drawColor(FRAME);
        }
        Paint opening = new Paint(Paint.ANTI_ALIAS_FLAG);
        opening.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        if (rounded) {
            canvas.drawRoundRect(60, 70, 180, 250, 24, 24, opening);
        } else {
            canvas.drawRect(60, 70, 180, 250, opening);
        }
        return image;
    }

    private static Bitmap composite(SkinLayer skin) {
        Bitmap result = Bitmap.createBitmap(240, 320, Bitmap.Config.ARGB_8888);
        result.eraseColor(Color.BLACK);
        RectF viewport = new RectF(0, 0, 240, 320);
        skin.resize(viewport, 0, 0, 240, 320);
        android.graphics.Canvas surface = new android.graphics.Canvas(result);
        Paint gamePaint = new Paint();
        gamePaint.setColor(GAME);
        surface.drawRect(viewport, gamePaint);
        CanvasWrapper graphics = new CanvasWrapper(false);
        graphics.bind(surface);
        skin.paint(graphics);
        return result;
    }

    @Test public void roundedOpeningMasksCornersAndKeepsFullViewportBounds() {
        Bitmap image = frame(true, true);
        try {
            SkinLayer skin = new SkinLayer(image, new ProfileModel());
            RectF viewport = new RectF();
            skin.resize(viewport, 0, 0, 240, 320);
            assertTrue(skin.hasDisplayFrame());
            assertEquals(60f, viewport.left, 2.5f);
            assertEquals(70f, viewport.top, 2.5f);
            assertEquals(180f, viewport.right, 2.5f);
            assertEquals(250f, viewport.bottom, 2.5f);
            Bitmap result = composite(skin);
            try {
                assertEquals(GAME, result.getPixel(120, 160));
                assertEquals("Rounded inner corner must cover the game", FRAME,
                        result.getPixel(62, 72));
                assertEquals("Outer transparency must not become the game viewport",
                        Color.BLACK, result.getPixel(0, 0));
            } finally { result.recycle(); }
        } finally { image.recycle(); }
    }

    @Test public void rectangularOpeningKeepsLegacyExclusion() {
        Bitmap image = frame(false, false);
        try {
            SkinLayer skin = new SkinLayer(image, new ProfileModel());
            RectF viewport = new RectF();
            skin.resize(viewport, 0, 0, 240, 320);
            assertEquals(60f, viewport.left, 1f);
            assertEquals(70f, viewport.top, 1f);
            Bitmap result = composite(skin);
            try {
                assertEquals(GAME, result.getPixel(61, 71));
                assertEquals(FRAME, result.getPixel(40, 40));
            } finally { result.recycle(); }
        } finally { image.recycle(); }
    }

    @Test public void opaqueSkinRetainsPaddingBasedScreenExclusion() {
        Bitmap image = Bitmap.createBitmap(240, 320, Bitmap.Config.ARGB_8888);
        image.eraseColor(FRAME);
        try {
            ProfileModel settings = new ProfileModel();
            settings.screenPadding = 20;
            SkinLayer skin = new SkinLayer(image, settings);
            assertFalse(skin.hasDisplayFrame());
            Bitmap result = composite(skin);
            try {
                assertEquals(GAME, result.getPixel(120, 160));
                assertEquals(FRAME, result.getPixel(10, 10));
            } finally { result.recycle(); }
        } finally { image.recycle(); }
    }

    @Test public void rasterizedSvgRoundedOpeningUsesSameComposition() throws Exception {
        String source = "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 240 320'>"
                + "<path fill='#1b4466' fill-rule='evenodd' d='"
                + "M0 0H240V320H0Z "
                + "M84 70H156Q180 70 180 94V226Q180 250 156 250"
                + "H84Q60 250 60 226V94Q60 70 84 70Z'/></svg>";
        Bitmap image = SvgRasterizer.read(new ByteArrayInputStream(
                source.getBytes(StandardCharsets.UTF_8))).renderWithin(240, 320);
        try {
            SkinLayer skin = new SkinLayer(image, new ProfileModel());
            assertTrue(skin.hasDisplayFrame());
            Bitmap result = composite(skin);
            try {
                assertEquals(GAME, result.getPixel(120, 160));
                assertEquals(FRAME, result.getPixel(62, 72));
            } finally { result.recycle(); }
        } finally { image.recycle(); }
    }
}
