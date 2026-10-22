/* SPDX-License-Identifier: Apache-2.0 */
package javax.microedition.midlet;

import java.net.URI;
import java.net.URLDecoder;
import java.io.UnsupportedEncodingException;
import java.util.Locale;

/** Conservative hint for presenting Java ME installation choices, never a content validator. */
final class PlatformPackageLink {
    static boolean isPackageLink(String url) {
        if (url == null || url.isEmpty()) return false;
        final URI uri;
        try { uri = URI.create(url); }
        catch (IllegalArgumentException invalid) { return false; }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http")
                || scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("file"))) return false;
        if (hasPackageExtension(uri.getPath())) return true;
        if (scheme.equalsIgnoreCase("file")) return false;
        String query = uri.getRawQuery();
        if (query == null) return false;
        for (String pair : query.split("&")) {
            int equal = pair.indexOf('=');
            if (equal < 0) continue;
            try {
                String value = URLDecoder.decode(pair.substring(equal + 1), "UTF-8");
                // Legacy download frontends may put a relative archive path in a redirect parameter.
                if (hasPackageExtension(value)) return true;
            } catch (IllegalArgumentException | UnsupportedEncodingException malformed) {
                // A malformed redirect hint must not change ordinary browser routing.
            }
        }
        return false;
    }

    private static boolean hasPackageExtension(String path) {
        if (path == null) return false;
        String normalized = path.toLowerCase(Locale.ROOT);
        return normalized.endsWith(".jar") || normalized.endsWith(".jad")
                || normalized.endsWith(".kjx");
    }

    private PlatformPackageLink() { }
}
