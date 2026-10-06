/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.android.dx.command.dexer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable summary for one DX/JL-Mod conversion invocation. */
public final class ConversionResult {
    private final int exitCode;
    private final int classesDiscovered;
    private final int classesConverted;
    private final int classesSkipped;
    private final List<ConversionDiagnostic> diagnostics;
    private final int diagnosticsOmitted;

    ConversionResult(int exitCode, int classesDiscovered, int classesConverted, int classesSkipped,
            List<ConversionDiagnostic> diagnostics, int diagnosticsOmitted) {
        this.exitCode = exitCode;
        this.classesDiscovered = classesDiscovered;
        this.classesConverted = classesConverted;
        this.classesSkipped = classesSkipped;
        this.diagnostics = Collections.unmodifiableList(
                new ArrayList<ConversionDiagnostic>(diagnostics));
        this.diagnosticsOmitted = diagnosticsOmitted;
    }

    public static ConversionResult noWorkSuccess() {
        return new ConversionResult(0, 0, 0, 0,
                Collections.<ConversionDiagnostic>emptyList(), 0);
    }

    public boolean isSuccess() {
        return exitCode == 0;
    }

    public int getExitCode() {
        return exitCode;
    }

    public int getClassesDiscovered() {
        return classesDiscovered;
    }

    public int getClassesConverted() {
        return classesConverted;
    }

    public int getClassesSkipped() {
        return classesSkipped;
    }

    public List<ConversionDiagnostic> getDiagnostics() {
        return diagnostics;
    }

    public int getDiagnosticsOmitted() {
        return diagnosticsOmitted;
    }

    public boolean hasWarnings() {
        return classesSkipped > 0;
    }
}
