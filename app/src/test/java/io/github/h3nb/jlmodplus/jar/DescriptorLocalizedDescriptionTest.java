/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.h3nb.jlmodplus.jar;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.IOException;
import java.util.Map;

public class DescriptorLocalizedDescriptionTest {
    @Test
    public void foldedLocalizedDescriptionWithColonRemainsSingleAttribute() throws IOException {
        Descriptor descriptor = new Descriptor(
                "MIDlet-Name: Sample\r\n" +
                "MIDlet-Vendor: Example\r\n" +
                "MIDlet-Version: 1.0\r\n" +
                "MIDlet-Description: Default description\r\n" +
                "MIDlet-Description-id: Jelajahi dunia yang\r\n" +
                "  luas: penuh misteri dan\r\n" +
                "  petualangan bersama teman.\r\n",
                false);
        Map<String, String> descriptions = MidletDescriptionLocalization.extract(descriptor.getAttrs());
        assertEquals("Jelajahi dunia yang luas: penuh misteri dan petualangan bersama teman.",
                descriptions.get("id"));
        assertEquals("Default description", descriptor.getAttrs().get(Descriptor.MIDLET_DESCRIPTION));
    }

    @Test
    public void descriptorMergePreservesLocalizedJadOverride() throws IOException {
        Descriptor manifest = new Descriptor(
                "MIDlet-Name: Sample\nMIDlet-Vendor: Example\nMIDlet-Version: 1.0\n" +
                "MIDlet-Description-id: Manifest text\nMIDlet-Description-en: English\n", false);
        Descriptor jad = new Descriptor(
                "MIDlet-Name: Sample\nMIDlet-Vendor: Example\nMIDlet-Version: 1.0\n" +
                "MIDlet-Jar-URL: sample.jar\nMIDlet-Jar-Size: 100\n" +
                "MIDlet-Description-id: JAD text\n", true);
        manifest.merge(jad);
        Map<String, String> descriptions = MidletDescriptionLocalization.extract(manifest.getAttrs());
        assertEquals("JAD text", descriptions.get("id"));
        assertEquals("English", descriptions.get("en"));
    }
}
