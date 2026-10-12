/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.h3nb.jlmodplus.jar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class MidletDescriptionLocalizationTest {
    @Test
    public void selectsExactThenLanguageThenDefault() {
        Map<String, String> values = Map.of(
                "en", "English",
                "en-US", "American English",
                "id", "Indonesia");
        assertEquals("American English", MidletDescriptionLocalization.resolve(values, "en-US", "Default"));
        assertEquals("English", MidletDescriptionLocalization.resolve(values, "en-GB", "Default"));
        assertEquals("Indonesia", MidletDescriptionLocalization.resolve(values, "id-ID", "Default"));
        assertEquals("Custom default", MidletDescriptionLocalization.resolve(values, "fr-FR", "Custom default"));
    }

    @Test
    public void dropsVariantThenCountry() {
        Map<String, String> values = Map.of("zh-CN", "Chinese (China)", "zh", "Chinese");
        assertEquals("Chinese (China)", MidletDescriptionLocalization.resolve(values, "zh-CN-Hans", "Default"));
        assertEquals("Chinese", MidletDescriptionLocalization.resolve(values, "zh-TW-Hant", "Default"));
    }

    @Test
    public void complexVariantFallsBackDirectlyToCountry() {
        Map<String, String> values = Map.of(
                "en-US-POSIX", "Intermediate variant",
                "en-US", "US English",
                "en", "English");
        assertEquals("US English", MidletDescriptionLocalization.resolve(
                values, "en-US-POSIX-EXT", "Default"));
        assertEquals("Intermediate variant", MidletDescriptionLocalization.resolve(
                values, "en-US-POSIX", "Default"));
    }

    @Test
    public void extractsOnlyLocalizedDescriptionAttributes() {
        Map<String, String> attributes = new HashMap<>();
        attributes.put("MIDlet-Description", "Default");
        attributes.put("MIDlet-Description-id", "Terjemahan Indonesia");
        attributes.put("MIDlet-Description-en-US", "US English");
        attributes.put("MIDlet-Description-en-us", "Invalid region");
        attributes.put("MIDlet-Description-xx-01", "Invalid region");
        attributes.put("MIDlet-Description-fr", "   ");
        attributes.put("MIDlet-Name-id", "Localized title");
        Map<String, String> values = MidletDescriptionLocalization.extract(attributes);
        assertEquals(2, values.size());
        assertEquals("Terjemahan Indonesia", values.get("id"));
        assertEquals("US English", values.get("en-US"));
        assertFalse(values.containsKey("en-us"));
    }

    @Test
    public void invalidGuestLocaleUsesDefaultWithoutGuessing() {
        Map<String, String> values = Map.of("en", "English");
        assertEquals("Edited source", MidletDescriptionLocalization.resolve(values, "en_us", "Edited source"));
        assertEquals("Edited source", MidletDescriptionLocalization.resolve(values, null, "Edited source"));
        assertTrue(MidletDescriptionLocalization.validLocale("id-ID"));
        assertFalse(MidletDescriptionLocalization.validLocale("id_ID"));
    }

    @Test
    public void localizedEntryTakesPrecedenceOverEditedSource() {
        Map<String, String> values = Map.of("id", "Game petualangan");
        assertEquals("Game petualangan", MidletDescriptionLocalization.resolve(values, "id-ID", "My favorite game"));
        assertEquals("My favorite game", MidletDescriptionLocalization.resolve(values, "de-DE", "My favorite game"));
    }
}
