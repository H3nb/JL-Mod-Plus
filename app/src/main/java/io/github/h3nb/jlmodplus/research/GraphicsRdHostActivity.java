// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.research;

import android.app.Activity;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import javax.microedition.lcdui.graphics.GlesView;

/**
 * Disposable R&D host for the real GlesView surface in the target process.
 * Remove this Activity and manifest entry before any production integration.
 */
public final class GraphicsRdHostActivity extends Activity {
    private FrameLayout root;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        root = new FrameLayout(this);
        setContentView(root);
    }

    public void install(GlesView view, int width, int height) {
        root.removeAllViews();
        root.addView(view, new FrameLayout.LayoutParams(width, height));
    }

    public void resize(GlesView view, int width, int height) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        params.width = width;
        params.height = height;
        view.setLayoutParams(params);
    }
}
