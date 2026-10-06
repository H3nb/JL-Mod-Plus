/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.h3nb.jlmodplus.installer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import io.github.h3nb.jlmodplus.jar.Descriptor;
import io.github.h3nb.jlmodplus.util.ConverterException;

public class MidletConversionPolicyTest {
    @Test
    public void nonEntryMalformedClassDoesNotBlockInstallation() throws Exception {
        Descriptor descriptor = descriptor(
                "MIDlet-1: Game, /icon.png, rpg.x\n");
        MidletConversionPolicy.requireRunnableEntryClasses(
                Collections.singleton("rpg.z"), descriptor);
    }

    @Test
    public void skippedDeclaredMidletEntryBlocksInstallation() throws Exception {
        Descriptor descriptor = descriptor(
                "MIDlet-1: Game, /icon.png, rpg.x\n");
        try {
            MidletConversionPolicy.requireRunnableEntryClasses(
                    Collections.singleton("rpg.x"), descriptor);
            fail("A declared MIDlet entry that is missing from the converted payload must be fatal");
        } catch (ConverterException expected) {
            assertEquals(
                    "Declared MIDlet entry class could not be converted: rpg.x",
                    expected.getMessage());
        }
    }

    @Test
    public void anySkippedEntryInMultiMidletSuiteBlocksInstallation() throws Exception {
        Descriptor descriptor = descriptor(
                "MIDlet-1: Game, /icon.png, game.Main\n"
                        + "MIDlet-2: Tools, /tools.png, tools.Editor\n");
        Set<String> skipped = new HashSet<>();
        skipped.add("tools.Editor");
        skipped.add("unused.Credit");
        try {
            MidletConversionPolicy.requireRunnableEntryClasses(skipped, descriptor);
            fail("The runtime must not advertise a declared MIDlet entry that was skipped");
        } catch (ConverterException expected) {
            assertEquals(
                    "Declared MIDlet entry class could not be converted: tools.Editor",
                    expected.getMessage());
        }
    }

    @Test
    public void archiveEntryNameMapsToDescriptorClassName() {
        assertEquals("rpg.z", MidletConversionPolicy.classNameFromEntry("./rpg/z.class"));
        assertEquals("rpg.z", MidletConversionPolicy.classNameFromEntry("rpg\\z.CLASS"));
    }

    private static Descriptor descriptor(String midletEntries) throws Exception {
        return new Descriptor(
                "MIDlet-Name: Demo\n"
                        + "MIDlet-Vendor: Vendor\n"
                        + "MIDlet-Version: 1.0\n"
                        + midletEntries,
                false);
    }
}
