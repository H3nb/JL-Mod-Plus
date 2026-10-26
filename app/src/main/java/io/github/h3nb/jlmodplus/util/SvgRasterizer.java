package io.github.h3nb.jlmodplus.util;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;

import com.caverock.androidsvg.SVG;
import com.caverock.androidsvg.SVGParseException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Bounded static SVG input for app-owned artwork; never used on the drawing path. */
public final class SvgRasterizer {
    public static final int MAX_SOURCE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_RENDER_SIDE = 4096;

    private SvgRasterizer() {}

    public static boolean isSvg(File file) {
        return file.getName().toLowerCase(Locale.ROOT).endsWith(".svg");
    }

    public static Document read(File file) throws IOException {
        if (!file.isFile() || file.length() < 1 || file.length() > MAX_SOURCE_BYTES) {
            throw new IOException("Invalid or oversized SVG");
        }
        try (FileInputStream input = new FileInputStream(file)) {
            return read(input);
        }
    }

    public static Document read(InputStream input) throws IOException {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > MAX_SOURCE_BYTES) {
                    throw new IOException("SVG exceeds 8 MB");
                }
                bytes.write(buffer, 0, count);
            }
            byte[] content = bytes.toByteArray();
            if (content.length == 0) throw new IOException("Empty SVG");
            // Do not accept document declarations or entities from untrusted user artwork.
            String markup = new String(content, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            if (markup.contains("<!doctype") || markup.contains("<!entity")
                    || markup.contains("<?xml-stylesheet")) {
                throw new IOException("SVG document declarations are not supported");
            }

            try {
                SVG.setInternalEntitiesEnabled(false);
                SVG.registerExternalFileResolver(null);
                SVG svg = SVG.getFromInputStream(new ByteArrayInputStream(content));
                RectF viewBox = svg.getDocumentViewBox();
                float width = svg.getDocumentWidth();
                float height = svg.getDocumentHeight();
                if (!(width > 0f) && viewBox != null) width = viewBox.width();
                if (!(height > 0f) && viewBox != null) height = viewBox.height();
                if (Float.isNaN(width) || Float.isInfinite(width)
                        || Float.isNaN(height) || Float.isInfinite(height)
                        || width <= 0f || height <= 0f) {
                    throw new IOException("SVG needs finite positive dimensions or viewBox");
                }
                double ratio = (double) width / height;
                if (Double.isNaN(ratio) || Double.isInfinite(ratio)
                        || ratio <= 0d || ratio < 0.0001d || ratio > 10000d) {
                    throw new IOException("Invalid SVG aspect ratio");
                }
                return new Document(svg, (float) ratio, width, height, viewBox != null);
            } catch (SVGParseException | IllegalArgumentException failure) {
                throw new IOException("Invalid SVG image", failure);
            }
        } catch (OutOfMemoryError failure) {
            throw new IOException("SVG is too complex to load safely", failure);
        }
    }

    public static final class Document {
        private final SVG svg;
        private final float aspectRatio;
        private final float intrinsicWidth;
        private final float intrinsicHeight;
        private final boolean hasViewBox;

        private Document(SVG svg, float ratio, float width, float height, boolean hasViewBox) {
            this.svg = svg;
            this.aspectRatio = ratio;
            this.intrinsicWidth = width;
            this.intrinsicHeight = height;
            this.hasViewBox = hasViewBox;
        }

        public float aspectRatio() { return aspectRatio; }

        /** Render within a box while retaining the document's complete transparent canvas. */
        public Bitmap renderWithin(int maxWidth, int maxHeight) {
            if (maxWidth < 1 || maxHeight < 1 || maxWidth > MAX_RENDER_SIDE
                    || maxHeight > MAX_RENDER_SIDE) {
                throw new IllegalArgumentException("Invalid SVG raster bounds");
            }
            double scale = Math.min(maxWidth / (double) intrinsicWidth,
                    maxHeight / (double) intrinsicHeight);
            int width = Math.max(1, Math.min(maxWidth, (int) Math.round(intrinsicWidth * scale)));
            int height = Math.max(1, Math.min(maxHeight, (int) Math.round(intrinsicHeight * scale)));
            Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            try {
                Canvas canvas = new Canvas(result);
                if (hasViewBox) {
                    svg.renderToCanvas(canvas, new RectF(0, 0, width, height));
                } else {
                    // Without a viewBox, AndroidSVG uses document-unit coordinates.
                    canvas.scale(width / intrinsicWidth, height / intrinsicHeight);
                    svg.renderToCanvas(canvas, new RectF(0, 0, intrinsicWidth, intrinsicHeight));
                }
                return result;
            } catch (RuntimeException | OutOfMemoryError failure) {
                result.recycle();
                throw failure;
            }
        }
    }
}
