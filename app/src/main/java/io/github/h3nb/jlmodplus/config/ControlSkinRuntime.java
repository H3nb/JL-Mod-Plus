package io.github.h3nb.jlmodplus.config;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import io.github.h3nb.jlmodplus.util.SvgRasterizer;
import java.io.IOException;
import androidx.annotation.Nullable;
import java.io.File;
import java.util.Map;

/** Immutable bitmap snapshot; the entire skin shares one planned raster budget. */
public final class ControlSkinRuntime {
    private static final String TAG = "ControlSkinRuntime";
    private static final int MAX_SIDE = 512;
    private static final int MAX_SOURCE_SIDE = 2048;
    private static final long MAX_SOURCE_BYTES = 8L * 1024 * 1024;
    private static final long MAX_BITMAP_BYTES = 24L * 1024 * 1024;
    private static final long PLAN_BYTES = MAX_BITMAP_BYTES * 95 / 100;
    private static final int GROUPED = ControlSkinStore.GROUPED_ASSETS.length;
    private static final int SLOTS = GROUPED + ControlSkinStore.KEYS.length * 2;

    public static final class Button {
        public final Bitmap normal;
        @Nullable public final Bitmap pressed;
        public final boolean full;
        Button(Bitmap normal, Bitmap pressed, boolean full) {
            this.normal = normal; this.pressed = pressed; this.full = full;
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

    private static final class Asset {
        final File file;
        @Nullable final SvgRasterizer.Document vector;
        final int sourceWidth, sourceHeight;
        final int targetWidth, targetHeight;

        Asset(File file, int width, int height, int maxSide) {
            this(file, width, height, maxSide, null);
        }
        Asset(File file, int width, int height, int maxSide, @Nullable SvgRasterizer.Document svg) {
            this.file = file;
            vector = svg;
            sourceWidth = width;
            sourceHeight = height;
            float scale = Math.min(1f, maxSide / (float) Math.max(width, height));
            targetWidth = Math.max(1, Math.round(width * scale));
            targetHeight = Math.max(1, Math.round(height * scale));
        }
        int width(float quality) { return Math.max(1, Math.round(targetWidth * quality)); }
        int height(float quality) { return Math.max(1, Math.round(targetHeight * quality)); }
    }

    private static final class BudgetExceeded extends RuntimeException {}

    private final Button[] buttons = new Button[ControlSkinStore.KEYS.length];
    @Nullable private Dpad dpad;
    @Nullable private Analog analog;

    private ControlSkinRuntime() {}

    @Nullable public Button get(int index) {
        return index >= 0 && index < buttons.length ? buttons[index] : null;
    }
    @Nullable public Dpad dpad() { return dpad; }
    @Nullable public Analog analog() { return analog; }

    /** Legacy/default sizing for callers without display geometry. */
    public static ControlSkinRuntime load(File configDir, String selectedId) {
        return load(configDir, selectedId, MAX_SIDE, MAX_SIDE);
    }

    /** Prepare all usable assets for the lifetime of one MIDlet session. */
    public static ControlSkinRuntime load(File configDir, String selectedId,
            int keySide, int movementSide) {
        if (selectedId == null || selectedId.isEmpty()) return new ControlSkinRuntime();
        File configs = configDir.getParentFile();
        File workdir = configs == null ? null : configs.getParentFile();
        if (workdir == null) return new ControlSkinRuntime();
        try {
            ControlSkinStore.SetInfo set = ControlSkinStore.findReadonly(workdir, selectedId);
            if (set == null) return new ControlSkinRuntime();
            Map<String, String> modes = ControlSkinStore.modes(set.folder);
            int keyTarget = clampSide(keySide);
            int movementTarget = clampSide(movementSide);
            int thumbTarget = Math.max(1, Math.round(movementTarget * 0.48f));
            Asset[] assets = new Asset[SLOTS];
            assets[0] = inspect(set.folder, "dpad_base", movementTarget);
            if (assets[0] != null) {
                for (int i = 1; i < 5; i++) {
                    assets[i] = inspect(set.folder, ControlSkinStore.GROUPED_ASSETS[i],
                            movementTarget);
                }
            }
            assets[5] = inspect(set.folder, "analog_base", movementTarget);
            assets[6] = inspect(set.folder, "analog_thumb", thumbTarget);
            if (assets[6] != null) {
                assets[7] = inspect(set.folder, "analog_thumb_pressed", thumbTarget);
            }
            for (int i = 0; i < ControlSkinStore.KEYS.length; i++) {
                int slot = GROUPED + i * 2;
                String stem = "key_" + ControlSkinStore.KEYS[i];
                assets[slot] = inspect(set.folder, stem, keyTarget);
                if (assets[slot] != null) {
                    assets[slot + 1] = inspect(set.folder, stem + "_pressed", keyTarget);
                }
            }

            float quality = plannedQuality(assets);
            for (int attempt = 0; attempt < 3; attempt++) {
                Bitmap[] decoded = new Bitmap[SLOTS];
                try {
                    long bytes = 0;
                    for (int i = 0; i < SLOTS; i++) {
                        if (assets[i] == null) continue;
                        // Do not decode visual layers whose required parent failed to load.
                        if (i >= 1 && i <= 4 && decoded[0] == null) continue;
                        if (i == 7 && decoded[6] == null) continue;
                        if (i >= GROUPED && (i - GROUPED) % 2 == 1
                                && decoded[i - 1] == null) continue;
                        Bitmap bitmap = decode(assets[i], quality);
                        if (bitmap == null) continue; // Invalid image, independent fallback.
                        decoded[i] = bitmap;
                        bytes += bitmap.getAllocationByteCount();
                        if (bytes > MAX_BITMAP_BYTES) throw new BudgetExceeded();
                    }
                    return publish(modes, decoded);
                } catch (BudgetExceeded | OutOfMemoryError failure) {
                    recycle(decoded);
                    quality *= 0.75f;
                    Log.w(TAG, "Retrying complete skin with lower raster quality", failure);
                } catch (RuntimeException failure) {
                    recycle(decoded);
                    Log.w(TAG, "Could not prepare selected control skin", failure);
                    return new ControlSkinRuntime();
                }
            }
            Log.w(TAG, "Skin allocation failed; falling back to built-in controls");
        } catch (RuntimeException | OutOfMemoryError failure) {
            Log.w(TAG, "Could not load selected control skin", failure);
        }
        return new ControlSkinRuntime();
    }

    private static int clampSide(int side) { return Math.max(1, Math.min(MAX_SIDE, side)); }

    @Nullable private static Asset inspect(File folder, String stem, int maxSide) {
        File file = ControlSkinStore.asset(folder, stem);
        if (file == null || file.length() > MAX_SOURCE_BYTES) return null;
        try {
            if (SvgRasterizer.isSvg(file)) {
                SvgRasterizer.Document vector = SvgRasterizer.read(file);
                float ratio = vector.aspectRatio();
                int width = ratio >= 1f ? 1024 : Math.max(1, Math.round(1024f * ratio));
                int height = ratio < 1f ? 1024 : Math.max(1, Math.round(1024f / ratio));
                return new Asset(file, width, height, maxSide, vector);
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth < 1 || bounds.outHeight < 1
                    || bounds.outWidth > MAX_SOURCE_SIDE || bounds.outHeight > MAX_SOURCE_SIDE) {
                return null;
            }
            return new Asset(file, bounds.outWidth, bounds.outHeight, maxSide);
        } catch (IOException | RuntimeException failure) {
            Log.w(TAG, "Invalid control skin image: " + file.getName(), failure);
            return null;
        }
    }

    /** One proportional factor for the whole set, independent of slot order. */
    private static float plannedQuality(Asset[] assets) {
        if (estimatedBytes(assets, 1f) <= PLAN_BYTES) return 1f;
        float low = 0f, high = 1f;
        for (int i = 0; i < 20; i++) {
            float mid = (low + high) / 2f;
            if (estimatedBytes(assets, mid) <= PLAN_BYTES) low = mid;
            else high = mid;
        }
        return low;
    }

    private static long estimatedBytes(Asset[] assets, float quality) {
        long bytes = 0;
        for (Asset asset : assets) {
            if (asset != null) bytes += 4L * asset.width(quality) * asset.height(quality);
        }
        return bytes;
    }

    @Nullable private static Bitmap decode(Asset asset, float quality) {
        if (asset.vector != null) {
            try {
                return asset.vector.renderWithin(asset.width(quality), asset.height(quality));
            } catch (RuntimeException failure) {
                Log.w(TAG, "Undecodable SVG control skin: " + asset.file.getName(), failure);
                return null;
            }
        }
        int requestedSide = Math.max(asset.width(quality), asset.height(quality));
        int originalSide = Math.max(asset.sourceWidth, asset.sourceHeight);
        int sample = 1;
        while (originalSide / sample > requestedSide * 2) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        int sampledSide = Math.max(1, (originalSide + sample - 1) / sample);
        if (requestedSide < sampledSide) {
            options.inScaled = true;
            options.inDensity = sampledSide;
            options.inTargetDensity = requestedSide;
        }
        try {
            return BitmapFactory.decodeFile(asset.file.getAbsolutePath(), options);
        } catch (OutOfMemoryError oom) {
            throw oom; // Retry the complete snapshot, never drop a late slot.
        } catch (RuntimeException failure) {
            Log.w(TAG, "Undecodable control skin image: " + asset.file.getName(), failure);
            return null;
        }
    }

    private static ControlSkinRuntime publish(Map<String, String> modes, Bitmap[] decoded) {
        ControlSkinRuntime skin = new ControlSkinRuntime();
        if (decoded[0] != null) {
            skin.dpad = new Dpad(decoded[0], decoded[1], decoded[2], decoded[3], decoded[4]);
        }
        if (decoded[5] != null || decoded[6] != null) {
            skin.analog = new Analog(decoded[5], decoded[6], decoded[7]);
        }
        for (int i = 0; i < ControlSkinStore.KEYS.length; i++) {
            int slot = GROUPED + i * 2;
            if (decoded[slot] == null) continue;
            skin.buttons[i] = new Button(decoded[slot], decoded[slot + 1],
                    "full".equals(modes.get(ControlSkinStore.KEYS[i])));
        }
        return skin;
    }

    private static void recycle(Bitmap[] bitmaps) {
        for (Bitmap bitmap : bitmaps) {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }
}
