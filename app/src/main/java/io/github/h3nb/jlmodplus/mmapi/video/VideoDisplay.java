// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.mmapi.video;

import android.content.Context;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.VideoItem;
import javax.microedition.lcdui.ViewHandler;
import javax.microedition.media.MediaException;
import javax.microedition.media.control.VideoControl;

/** Guest geometry, separate video producer Surface, and main-thread View lifetime. */
public final class VideoDisplay implements VideoControl {
    public interface SurfaceOwner {
        // Owner queues the change on the codec worker; destruction is acknowledged
        // there before releasing the Surface and any obsolete SurfaceTexture.
        void changeSurface(Surface surface, SurfaceTexture obsolete, long revision);
    }

    private final SurfaceOwner owner;
    private final int sourceWidth, sourceHeight;
    private int mode = -1, x, y, width, height;
    private boolean visible, fullscreen;
    private volatile boolean closed;
    private Canvas canvas;
    private VideoItem item;
    private FrameLayout view; // main thread only
    private TextureView texture;
    private long revision;
    private Runnable sizeListener;
    private int reportedWidth, reportedHeight;

    public synchronized void setSizeListener(Runnable listener) {
        sizeListener = listener;
    }

    private void reportSizeChange() {
        int[] size = displaySize();
        if (size[0] == reportedWidth && size[1] == reportedHeight) return;
        reportedWidth = size[0];
        reportedHeight = size[1];
        if (sizeListener != null) ViewHandler.postEvent(sizeListener);
    }

    public VideoDisplay(int width, int height, SurfaceOwner owner) {
        sourceWidth = width;
        sourceHeight = height;
        this.width = width;
        this.height = height;
        this.owner = owner;
        reportedWidth = width;
        reportedHeight = height;
    }

    private void initialized() {
        if (closed || mode < 0) throw new IllegalStateException("Video display is not initialized");
    }

    @Override
    public synchronized Object initDisplayMode(int mode, Object arg) {
        if (closed || this.mode >= 0)
            throw new IllegalStateException("Video display already initialized or closed");
        if (mode == USE_DIRECT_VIDEO) {
            if (!(arg instanceof Canvas))
                throw new IllegalArgumentException("Direct video requires an LCDUI Canvas");
            this.mode = mode;
            canvas = (Canvas) arg;
            canvas.addVideoDisplay(this);
            return null;
        }
        if (mode != USE_GUI_PRIMITIVE
                || (arg != null && !"javax.microedition.lcdui.Item".equals(arg)))
            throw new IllegalArgumentException("GUI video requires an LCDUI Item");
        this.mode = mode;
        visible = true;
        item = new VideoItem(this);
        return item;
    }

    @Override
    public synchronized void setDisplayLocation(int x, int y) {
        initialized();
        if (mode == USE_DIRECT_VIDEO) {
            this.x = x;
            this.y = y;
            refresh();
        }
    }

    @Override
    public synchronized void setDisplaySize(int width, int height) {
        initialized();
        if (width <= 0 || height <= 0)
            throw new IllegalArgumentException("Video size must be positive");
        this.width = width;
        this.height = height;
        refresh();
        reportSizeChange();
    }

    @Override
    public synchronized void setDisplayFullScreen(boolean value) {
        initialized();
        fullscreen = value;
        refresh();
        reportSizeChange();
    }

    @Override
    public synchronized void setVisible(boolean value) {
        initialized();
        visible = value;
        refresh();
    }

    @Override
    public int getSourceWidth() {
        return sourceWidth;
    }

    @Override
    public int getSourceHeight() {
        return sourceHeight;
    }

    @Override
    public synchronized int getDisplayX() {
        initialized();
        return fullscreen ? 0 : x;
    }

    @Override
    public synchronized int getDisplayY() {
        initialized();
        return fullscreen ? 0 : y;
    }

    @Override
    public synchronized int getDisplayWidth() {
        initialized();
        return displaySize()[0];
    }

    @Override
    public synchronized int getDisplayHeight() {
        initialized();
        return displaySize()[1];
    }

    private int[] displaySize() {
        if (!fullscreen) return new int[] {width, height};
        int w = canvas != null ? canvas.getWidth() : item.getVideoViewportWidth();
        int h = canvas != null ? canvas.getHeight() : item.getVideoViewportHeight();
        double scale =
                Math.min(
                        (double) Math.max(1, w) / sourceWidth,
                        (double) Math.max(1, h) / sourceHeight);
        return new int[] {
            Math.max(1, (int) (sourceWidth * scale)), Math.max(1, (int) (sourceHeight * scale))
        };
    }

    @Override
    public synchronized byte[] getSnapshot(String type) throws MediaException {
        initialized();
        throw new MediaException("Video snapshots are unsupported");
    }

    private void refresh() {
        ViewHandler.postEvent(
                () -> {
                    if (closed) return;
                    if (canvas != null) canvas.updateVideoDisplays();
                    else if (item != null) item.updateVideoView();
                });
    }

    private FrameLayout createView(Context context) {
        FrameLayout root = new FrameLayout(context);
        root.setClipChildren(true);
        root.setClipToPadding(true);
        TextureView next = new TextureView(context);
        next.setOpaque(true);
        next.setFocusable(false);
        next.setClickable(false);
        next.setSurfaceTextureListener(
                new TextureView.SurfaceTextureListener() {
                    private long surfaceVersion;

                    public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) {
                        if (closed || texture != next) {
                            s.release();
                            return;
                        }
                        surfaceVersion = ++revision;
                        owner.changeSurface(new Surface(s), null, surfaceVersion);
                    }

                    public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) {}

                    public boolean onSurfaceTextureDestroyed(SurfaceTexture s) {
                        // Detach the producer that actually acquired this texture.
                        // A newer producer's revision fences a delayed destruction.
                        owner.changeSurface(null, s, surfaceVersion);
                        return false;
                    }

                    public void onSurfaceTextureUpdated(SurfaceTexture s) {}
                });
        texture = next;
        view = root;
        root.addView(next);
        return root;
    }

    /** Main-thread Canvas hook. Root clips the video to the guest LCD region. */
    public synchronized void attachCanvas(
            FrameLayout parent, RectF viewport, int guestWidth, int guestHeight, boolean shown) {
        if (closed) return;
        if (view == null || view.getParent() != parent) {
            detachView();
            parent.addView(createView(parent.getContext()));
        }
        FrameLayout.LayoutParams rootParams =
                new FrameLayout.LayoutParams(
                        Math.max(1, Math.round(viewport.width())),
                        Math.max(1, Math.round(viewport.height())));
        rootParams.leftMargin = Math.round(viewport.left);
        rootParams.topMargin = Math.round(viewport.top);
        view.setLayoutParams(rootParams);
        int[] size = displaySize();
        reportSizeChange();
        float sx = viewport.width() / Math.max(1, guestWidth),
                sy = viewport.height() / Math.max(1, guestHeight);
        FrameLayout.LayoutParams params =
                new FrameLayout.LayoutParams(
                        Math.max(1, Math.round(size[0] * sx)),
                        Math.max(1, Math.round(size[1] * sy)));
        params.leftMargin = Math.round((fullscreen ? 0 : x) * sx);
        params.topMargin = Math.round((fullscreen ? 0 : y) * sy);
        texture.setLayoutParams(params);
        view.setVisibility(visible && shown ? View.VISIBLE : View.INVISIBLE);
    }

    /** Main-thread Item hook; the real Item remains the guest-facing primitive. */
    public synchronized View itemView(Context context, float scale) {
        if (view == null) createView(context);
        int[] size = displaySize();
        reportSizeChange();
        int w = Math.max(1, Math.round(size[0] * scale)),
                h = Math.max(1, Math.round(size[1] * scale));
        view.setMinimumWidth(w);
        view.setMinimumHeight(h);
        view.setLayoutParams(new android.widget.LinearLayout.LayoutParams(w, h));
        texture.setLayoutParams(new FrameLayout.LayoutParams(w, h));
        view.setVisibility(!closed && visible ? View.VISIBLE : View.INVISIBLE);
        return view;
    }

    public synchronized boolean hasView() {
        return view != null && !closed;
    }

    public boolean isClosed() {
        return closed;
    }

    public synchronized void detachView() {
        if (view != null && view.getParent() instanceof ViewGroup)
            ((ViewGroup) view.getParent()).removeView(view);
        view = null;
        texture = null;
    }

    public synchronized void close() {
        if (closed) return;
        closed = true;
        ViewHandler.postEvent(
                () -> {
                    if (canvas != null) canvas.removeVideoDisplay(this);
                    detachView();
                });
    }
}
