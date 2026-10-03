// SPDX-License-Identifier: Apache-2.0
package javax.microedition.shell;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import javax.microedition.util.ContextHolder;

/** Requests recreation of the real runtime host, without ending its guest session. */
public final class AudioRuntimeRecreationActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        MicroActivity host = ContextHolder.getActivity();
        finish();
        if (host != null) new Handler(Looper.getMainLooper()).post(host::recreate);
    }
}
