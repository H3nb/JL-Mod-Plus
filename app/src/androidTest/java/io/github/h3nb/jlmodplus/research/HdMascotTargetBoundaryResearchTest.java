// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.research;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import io.github.h3nb.jlmodplus.micro3d.Render;
import java.lang.reflect.Field;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/**
 * R&D characterization: exercise real Mascot GLES2 pbuffer binding under a
 * hypothetical 2x physical target and inspect its current projection contract.
 * No game geometry, 2D/3D mixed draw ordering or native M3G is claimed here.
 */
@RunWith(AndroidJUnit4.class)
public final class HdMascotTargetBoundaryResearchTest {
    private static final String TAG = "GraphicsRD_Mascot_HD";

    private static Object field(Object value, String name) throws Exception {
        Field f = value.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(value);
    }

    private static int integer(Object value, String name) throws Exception {
        return (int) field(value, name);
    }

    private static float tx(Object env) throws Exception {
        return ((float[]) field(env, "projMatrix"))[12];
    }

    @Test public void physicalPbufferNeedsLogicalCenterTranslation() throws Exception {
        Render render = Render.getRender();
        Image normal = Image.createImage(16, 12, Color.BLACK);
        Image doubled = new Image(
                Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888), true);
        doubled.getBitmap().eraseColor(Color.BLACK);
        try {
            Graphics baseGraphics = normal.getGraphics();
            render.bind(baseGraphics);
            float normalTx;
            try {
                Object env = field(render, "env");
                assertEquals(16, integer(env, "width"));
                assertEquals(12, integer(env, "height"));
                render.setCenter(4, 3);
                render.setOrthographicWH(20, 12);
                normalTx = tx(env);
                assertEquals(-0.5f, normalTx, 0.00001f);
            } finally {
                render.release();
            }

            Graphics hdGraphics = doubled.getGraphics();
            doubled.getBounds().set(0, 0, 16, 12);
            hdGraphics.getCanvas().scale(2f, 2f);
            assertEquals(16, doubled.getWidth());
            assertEquals(32, doubled.getBitmap().getWidth());

            render.bind(hdGraphics);
            try {
                Object env = field(render, "env");
                assertEquals("Actual Mascot pbuffer derives from physical Android Canvas",
                        32, integer(env, "width"));
                assertEquals(24, integer(env, "height"));
                render.setCenter(4, 3);
                render.setOrthographicWH(20, 12);
                float wrongTx = tx(env);
                assertEquals(-0.75f, wrongTx, 0.00001f);
                assertNotEquals("Logical center alone shifts NDC under 2x target",
                        normalTx, wrongTx, 0.00001f);

                render.setCenter(8, 6);
                render.setOrthographicWH(20, 12);
                assertEquals("Physical center restores the normalized projection",
                        normalTx, tx(env), 0.00001f);
                Log.i(TAG, "Mascot real pbuffer 16x12->32x24; naive tx="
                        + wrongTx + ", mapped tx=" + tx(env)
                        + ", original tx=" + normalTx);
            } finally {
                render.release();
            }
        } finally {
            normal.getBitmap().recycle();
            doubled.getBitmap().recycle();
        }
    }
}
