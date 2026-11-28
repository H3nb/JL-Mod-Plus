// SPDX-License-Identifier: Apache-2.0
package javax.microedition.lcdui;

import java.lang.ref.WeakReference;

/**
 * One reusable idle scratch surface for serialized guest paints. A claimed surface
 * is owned by its paint until release; a resize can stage another without touching it.
 */
final class PaintBufferPool {
    private Scratch idle;

    synchronized void prepare(int width, int backingHeight, int scale) {
        if (idle == null || idle.image.getPixelScale() != scale
                || idle.image.getBitmap().getWidth() < (long) width * scale
                || idle.image.getBitmap().getHeight() < (long) backingHeight * scale) {
            idle = new Scratch(Image.createDisplayTarget(width, backingHeight, scale));
        }
    }

    synchronized Scratch acquire(int width, int backingHeight, int scale) {
        prepare(width, backingHeight, scale);
        Scratch scratch = idle;
        idle = null;
        return scratch;
    }

    synchronized void release(Scratch scratch) {
        // A resize may already have prepared the next backing surface.
        if (idle == null) idle = scratch;
    }

    static final class Scratch {
        final Image image;
        private WeakReference<Image> source;
        private long revision = -1;

        private Scratch(Image image) {
            this.image = image;
        }

        /** Caller holds the publication lock; do not carry pixels from another Canvas. */
        void restore(Image published, long contentRevision) {
            int width = published.getWidth();
            int height = published.getHeight();
            if (image.getWidth() != width || image.getHeight() != height) {
                image.setSize(width, height);
                revision = -1;
            }
            if (source == null || source.get() != published || revision != contentRevision) {
                image.getSingleGraphics().reset(0, 0, width, height);
                published.copyTo(image);
            }
            // Guest code may now modify the scratch, even if publication is abandoned.
            revision = -1;
        }

        void markPublished(Image published, long contentRevision) {
            if (source == null || source.get() != published) {
                source = new WeakReference<>(published);
            }
            revision = contentRevision;
        }
    }
}
