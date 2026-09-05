/* Licensed under the Apache License, Version 2.0.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0. */
package ru.woesss.j2me.installer;

import java.io.IOException;

/** A batch stops for environment/commit failures, independently of diagnostic wording. */
public final class InstallerFailure extends IOException {
    public InstallerFailure(String message) { super(message); }
    public InstallerFailure(String message, Throwable cause) { super(message, cause); }

    static String details(Throwable error) {
        StringBuilder result = new StringBuilder();
        for (int depth = 0; error != null && depth < 4; depth++, error = error.getCause()) {
            if (depth > 0) result.append('\n');
            result.append(error.toString().replaceAll("(?i)https?://\\S+", "[download URL]"));
        }
        return result.substring(0, Math.min(result.length(), 2048));
    }
}
