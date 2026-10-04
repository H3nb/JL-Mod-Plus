// SPDX-License-Identifier: Apache-2.0
package javax.microedition.lcdui;

import android.view.View;

import io.github.h3nb.jlmodplus.mmapi.video.VideoDisplay;

import javax.microedition.util.ContextHolder;

/** MMAPI GUI primitive using the existing Form ownership, scrolling and menu. */
public final class VideoItem extends Item {
    private final VideoDisplay video;

    public VideoItem(VideoDisplay video) {
        this.video = video;
        setLayout(LAYOUT_SHRINK);
    }

    public int getVideoViewportWidth() {
        return getOwner() != null ? getOwner().getWidth() : Displayable.getVirtualWidth();
    }

    public int getVideoViewportHeight() {
        return getOwner() != null ? getOwner().getHeight() : Displayable.getVirtualHeight();
    }

    public void updateVideoView() {
        if (!video.hasView()) return;
        float scale =
                (float) ContextHolder.getDisplayWidth()
                        / Math.max(1, Displayable.getVirtualWidth());
        View view = video.itemView(ContextHolder.getActivity(), scale);
        width = video.getDisplayWidth();
        height = video.getDisplayHeight();
        view.requestLayout();
    }

    @Override
    View getItemContentView() {
        if (video.isClosed()) return new View(ContextHolder.getActivity());
        float scale =
                (float) ContextHolder.getDisplayWidth()
                        / Math.max(1, Displayable.getVirtualWidth());
        View view = video.itemView(ContextHolder.getActivity(), scale);
        view.setOnClickListener(v -> fireDefaultCommandAction());
        width = video.getDisplayWidth();
        height = video.getDisplayHeight();
        return view;
    }

    @Override
    void clearItemContentView() {
        video.detachView();
    }
}
