// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.mmapi.synth;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.TextView;

import io.github.h3nb.jlmodplus.mmapi.RuntimeAudioCoordinator;

/** A foreground host for audio instrumentation; never packaged in release builds. */
public final class AudioQualificationActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        RuntimeAudioCoordinator.beginRuntime();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        TextView label = new TextView(this);
        label.setText("Audio qualification in progress");
        setContentView(label);
    }

    @Override public void onResume() {
        super.onResume();
        RuntimeAudioCoordinator.onHostForegroundChanged(true);
    }

    @Override public void onPause() {
        RuntimeAudioCoordinator.onHostForegroundChanged(false);
        super.onPause();
    }
}
