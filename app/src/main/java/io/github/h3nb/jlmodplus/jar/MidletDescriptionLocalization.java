/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.h3nb.jlmodplus.jar;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** MIDP 3.0 localized description attributes, also accepted for older MIDP suites. */
public final class MidletDescriptionLocalization {
    private static final String PREFIX = Descriptor.MIDLET_DESCRIPTION + "-";

    private MidletDescriptionLocalization() {}

    /** Extracts only valid locale-suffixed description attributes; never edits the descriptor. */
    public static Map<String, String> extract(Map<String, String> attributes) {
        Map<String, String> found = new TreeMap<>();
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            String name = entry.getKey();
            if (!name.startsWith(PREFIX)) continue;
            String locale = name.substring(PREFIX.length());
            String value = entry.getValue();
            if (validLocale(locale) && value != null && !value.isBlank()) {
                found.put(locale, value);
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(found));
    }

    /**
     * Resolves against the MIDlet's effective microedition.locale. The fallback is the
     * unqualified source description (or its user edit), never an override of a translation.
     */
    @Nullable
    public static String resolve(Map<String, String> translations,
                                 @Nullable String guestLocale,
                                 @Nullable String defaultDescription) {
        if (guestLocale == null || !validLocale(guestLocale)) return defaultDescription;
        String candidate = guestLocale;
        while (true) {
            String value = translations.get(candidate);
            if (value != null && !value.isBlank()) return value;
            int separator = candidate.lastIndexOf('-');
            if (separator < 0) break;
            candidate = candidate.substring(0, separator);
        }
        return defaultDescription;
    }

    /** MIDP locale: two-letter lower-case language, optional upper-case region and variant. */
    static boolean validLocale(String locale) {
        String[] fields = locale.split("-", -1);
        if (fields.length == 0 || fields[0].length() != 2
                || !lowerAscii(fields[0])) return false;
        if (fields.length > 1 && (fields[1].length() != 2 || !upperAscii(fields[1]))) return false;
        for (int i = 2; i < fields.length; i++) {
            if (fields[i].isEmpty()) return false;
            for (int j = 0; j < fields[i].length(); j++) {
                char ch = fields[i].charAt(j);
                if (!((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')
                        || (ch >= '0' && ch <= '9'))) return false;
            }
        }
        return true;
    }

    private static boolean lowerAscii(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch < 'a' || ch > 'z') return false;
        }
        return true;
    }

    private static boolean upperAscii(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch < 'A' || ch > 'Z') return false;
        }
        return true;
    }
}
