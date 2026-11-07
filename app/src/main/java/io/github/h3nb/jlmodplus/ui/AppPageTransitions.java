/* SPDX-License-Identifier: Apache-2.0 */
package io.github.h3nb.jlmodplus.ui;

import android.app.Activity;
import android.os.Build;

import io.github.h3nb.jlmodplus.R;

/**
 * Keeps the commit animation for app-owned Activity pages aligned with Compose page motion.
 * On older Android versions, the scoped AppTheme.NavigationPage window style owns transitions.
 */
public final class AppPageTransitions {
    private AppPageTransitions() {}

    public static void install(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            activity.overrideActivityTransition(
                    Activity.OVERRIDE_TRANSITION_OPEN, R.anim.app_page_enter, 0);
            activity.overrideActivityTransition(
                    Activity.OVERRIDE_TRANSITION_CLOSE, 0, R.anim.app_page_exit);
        }
    }
}
