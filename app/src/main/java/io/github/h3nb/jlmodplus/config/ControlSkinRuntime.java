package io.github.h3nb.jlmodplus.config;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import androidx.annotation.Nullable;
import java.io.File;
import java.io.IOException;

/** Immutable, predecoded appearance for one MIDlet runtime; no I/O during drawing. */
public final class ControlSkinRuntime {
    private static final String TAG = "ControlSkinRuntime";
    private static final int MAX_SIDE = 512;
    private static final long MAX_BITMAP_BYTES = 24L * 1024 * 1024;

    public static final class Button {
        public final Bitmap normal;
        @Nullable public final Bitmap pressed;
        public final boolean full;
        Button(Bitmap normal, Bitmap pressed, boolean full) {
            this.normal = normal;
            this.pressed = pressed;
            this.full = full;
        }
    }

    private final Button[] buttons = new Button[ControlSkinStore.KEYS.length];

    private ControlSkinRuntime() {}

    @Nullable
    public Button get(int index) {
        return index >= 0 && index < buttons.length ? buttons[index] : null;
    }

    public static ControlSkinRuntime load(File configDir, String selectedId) {
        ControlSkinRuntime skin = new ControlSkinRuntime();
        if (selectedId == null || selectedId.isEmpty()) return skin;
        File configs = configDir.getParentFile();
        File workdir = configs == null ? null : configs.getParentFile();
        if (workdir == null) return skin;
        try {
            ControlSkinStore.SetInfo set = ControlSkinStore.findReadonly(workdir, selectedId);
            if (set == null) return skin;
            long used = 0;
            for (int i = 0; i < ControlSkinStore.KEYS.length; i++) {
                String key = ControlSkinStore.KEYS[i];
                File normalFile = ControlSkinStore.image(set.folder, key, false);
                if (normalFile == null) continue;
                Bitmap normal = decode(normalFile);
                if (normal == null) continue;
                long normalBytes = normal.getByteCount();
                if (used + normalBytes > MAX_BITMAP_BYTES) {
                    normal.recycle();
                    break;
                }
                used += normalBytes;
                File pressedFile = ControlSkinStore.image(set.folder, key, true);
                Bitmap pressed = pressedFile == null ? null : decode(pressedFile);
                if (pressed != null) {
                    long pressedBytes = pressed.getByteCount();
                    if (used + pressedBytes > MAX_BITMAP_BYTES) {
                        pressed.recycle();
                        pressed = null;
                    } else used += pressedBytes;
                }
                skin.buttons[i] = new Button(normal, pressed,
                        "full".equals(ControlSkinStore.mode(set.folder, key)));
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Could not load selected button skin; using default controls", e);
        }
        return skin;
    }

    @Nullable private static Bitmap decode(File file) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth < 1 || bounds.outHeight < 1
                    || bounds.outWidth > 2048 || bounds.outHeight > 2048) return null;
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (bitmap != null) bitmap.prepareToDraw();
            return bitmap;
        } catch (RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "Skipping invalid button skin image: " + file.getName(), e);
            return null;
        }
    }
}
