// SPDX-License-Identifier: Apache-2.0
package javax.microedition.shell;

import android.app.Activity;
import android.os.Bundle;

import androidx.core.os.ConfigurationCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Debug-only secondary-process probe for the Java ME device-locale boundary. */
public final class MidletLocaleProbeActivity extends Activity {
    public static final String EXTRA_MARKER_PATH = "jlmod.localeProbe.marker";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String markerPath = getIntent().getStringExtra(EXTRA_MARKER_PATH);
        if (markerPath == null || markerPath.isEmpty()) {
            finish();
            return;
        }

        Locale hostLocale = ConfigurationCompat.getLocales(
                getResources().getConfiguration()).get(0);
        String guestLocale = MidletLocaleResolver.resolve(this);
        MidletSystem.setProperty("microedition.locale", guestLocale);
        String systemProperty = System.getProperty("microedition.locale");
        String midletProperty = MidletSystem.getProperty("microedition.locale");
        try (FileOutputStream output = new FileOutputStream(new File(markerPath), false)) {
            String evidence = "host-locale="
                    + (hostLocale == null ? "" : hostLocale.toLanguageTag())
                    + "\nlocale=" + (guestLocale == null ? "" : guestLocale)
                    + "\nsystem-property=" + (systemProperty == null ? "" : systemProperty)
                    + "\nmidlet-property=" + (midletProperty == null ? "" : midletProperty) + "\n";
            output.write(evidence.getBytes(StandardCharsets.UTF_8));
            output.flush();
        } catch (IOException ignored) {
            // The instrumentation test owns timeout/error reporting for the marker.
        } finally {
            finish();
        }
    }
}
