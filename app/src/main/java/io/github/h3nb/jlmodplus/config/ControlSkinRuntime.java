package io.github.h3nb.jlmodplus.config;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import androidx.annotation.Nullable;
import java.io.File;

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

    public static final class Dpad {
        public final Bitmap base, up, down, left, right;
        Dpad(Bitmap base, Bitmap up, Bitmap down, Bitmap left, Bitmap right) {
            this.base = base; this.up = up; this.down = down;
            this.left = left; this.right = right;
        }
    }

    public static final class Analog {
        @Nullable public final Bitmap base, thumb, thumbPressed;
        Analog(Bitmap base, Bitmap thumb, Bitmap thumbPressed) {
            this.base = base; this.thumb = thumb; this.thumbPressed = thumbPressed;
        }
    }

    private final Button[] buttons = new Button[ControlSkinStore.KEYS.length];
    @Nullable private Dpad dpad;
    @Nullable private Analog analog;
    private long usedBytes;

    private ControlSkinRuntime() {}

    @Nullable
    public Button get(int index) {
        return index >= 0 && index < buttons.length ? buttons[index] : null;
    }

    @Nullable public Dpad dpad() { return dpad; }
    @Nullable public Analog analog() { return analog; }

    /** Shared startup decode budget for keys and grouped controls. */
    @Nullable private Bitmap loadAsset(File folder, String stem) {
        File file = ControlSkinStore.asset(folder, stem);
        Bitmap bitmap = file == null ? null : decode(file);
        if (bitmap == null) return null;
        long bytes = bitmap.getByteCount();
        if (usedBytes + bytes > MAX_BITMAP_BYTES) {
            bitmap.recycle();
            return null;
        }
        usedBytes += bytes;
        return bitmap;
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
            Bitmap dpadBase = skin.loadAsset(set.folder, "dpad_base");
            if (dpadBase != null) {
                skin.dpad = new Dpad(dpadBase,
                        skin.loadAsset(set.folder, "dpad_up"),
                        skin.loadAsset(set.folder, "dpad_down"),
                        skin.loadAsset(set.folder, "dpad_left"),
                        skin.loadAsset(set.folder, "dpad_right"));
            }
            Bitmap analogBase = skin.loadAsset(set.folder, "analog_base");
            Bitmap analogThumb = skin.loadAsset(set.folder, "analog_thumb");
            if (analogBase != null || analogThumb != null) {
                skin.analog = new Analog(analogBase, analogThumb,
                        analogThumb == null ? null : skin.loadAsset(set.folder, "analog_thumb_pressed"));
            }
            for (int i = 0; i < ControlSkinStore.KEYS.length; i++) {
                String key = ControlSkinStore.KEYS[i];
                Bitmap normal = skin.loadAsset(set.folder, "key_" + key);
                if (normal == null) continue;
                Bitmap pressed = skin.loadAsset(set.folder, "key_" + key + "_pressed");
                skin.buttons[i] = new Button(normal, pressed,
                        "full".equals(ControlSkinStore.mode(set.folder, key)));
            }
        } catch (RuntimeException e) {
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
