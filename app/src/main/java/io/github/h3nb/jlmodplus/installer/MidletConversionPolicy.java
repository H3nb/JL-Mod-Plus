// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.installer;

import com.android.dx.command.dexer.ConversionResult;

import java.util.HashSet;
import java.util.Set;

import io.github.h3nb.jlmodplus.jar.Descriptor;
import io.github.h3nb.jlmodplus.util.ConverterException;

/** Safety policy for recoverable class skips at the installer publication boundary. */
final class MidletConversionPolicy {
    private MidletConversionPolicy() {
    }

    static void requireRunnableEntryClasses(ConversionResult result, Descriptor descriptor)
            throws ConverterException {
        if (result == null || descriptor == null || result.getClassesSkipped() == 0) {
            return;
        }
        if (!result.hasCompleteSkippedClassEntries()) {
            throw new ConverterException(
                    "Skipped source class list is incomplete; MIDlet entry classes cannot be verified");
        }
        Set<String> skippedClasses = new HashSet<>();
        for (String entry : result.getSkippedClassEntries()) {
            String className = classNameFromEntry(entry);
            if (className != null) {
                skippedClasses.add(className);
            }
        }
        requireRunnableEntryClasses(skippedClasses, true, descriptor);
    }

    static void requireRunnableEntryClasses(Set<String> skippedClasses, Descriptor descriptor)
            throws ConverterException {
        requireRunnableEntryClasses(skippedClasses, true, descriptor);
    }

    static void requireRunnableEntryClasses(
            Set<String> skippedClasses, boolean skippedListComplete, Descriptor descriptor)
            throws ConverterException {
        if (!skippedListComplete) {
            throw new ConverterException(
                    "Skipped source class list is incomplete; MIDlet entry classes cannot be verified");
        }
        if (skippedClasses == null || skippedClasses.isEmpty() || descriptor == null) {
            return;
        }
        for (String midletClass : descriptor.getMidletClasses()) {
            if (skippedClasses.contains(midletClass)) {
                throw new ConverterException(
                        "Declared MIDlet entry class could not be converted: " + midletClass);
            }
        }
    }

    static String classNameFromEntry(String entry) {
        if (entry == null) {
            return null;
        }
        String normalized = entry.replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        if (normalized.length() <= 6
                || !normalized.regionMatches(true, normalized.length() - 6, ".class", 0, 6)) {
            return null;
        }
        return normalized.substring(0, normalized.length() - 6).replace('/', '.');
    }
}
