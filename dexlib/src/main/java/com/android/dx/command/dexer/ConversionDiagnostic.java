/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.android.dx.command.dexer;

/** Structured, bounded evidence for a class-level conversion anomaly. */
public final class ConversionDiagnostic {
    private static final int MAX_ENTRY_LENGTH = 512;
    private static final int MAX_DETAIL_LENGTH = 1024;

    public enum Phase {
        SOURCE_VALIDATION,
        SOURCE_IDENTITY,
        TRANSFORM,
        DEX_PARSE,
        DEX_TRANSLATION,
        DEX_WRITE,
    }

    public enum Kind {
        UNREADABLE_SOURCE_CLASS,
        UNSUPPORTED_SOURCE_CLASS,
        CLASS_NAME_MISMATCH,
        TRANSFORM_FAILURE,
        PARSE_FAILURE,
        TRANSLATION_FAILURE,
        OUTPUT_FAILURE,
    }

    public enum Action {
        SKIPPED,
        ABORTED,
    }

    private final String entry;
    private final Phase phase;
    private final Kind kind;
    private final Action action;
    private final String technicalDetail;

    public ConversionDiagnostic(String entry, Phase phase, Kind kind, Action action,
            String technicalDetail) {
        this.entry = sanitize(entry, MAX_ENTRY_LENGTH);
        this.phase = phase;
        this.kind = kind;
        this.action = action;
        this.technicalDetail = sanitize(technicalDetail, MAX_DETAIL_LENGTH);
    }

    public String getEntry() {
        return entry;
    }

    public Phase getPhase() {
        return phase;
    }

    public Kind getKind() {
        return kind;
    }

    public Action getAction() {
        return action;
    }

    public String getTechnicalDetail() {
        return technicalDetail;
    }

    private static String sanitize(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        StringBuilder result = new StringBuilder(Math.min(value.length(), maxLength));
        for (int i = 0; i < value.length() && result.length() < maxLength; i++) {
            char c = value.charAt(i);
            if (c == '\n') result.append("\\n");
            else if (c == '\r') result.append("\\r");
            else if (c == '\t') result.append("\\t");
            else if (Character.isISOControl(c)) result.append('?');
            else result.append(c);
        }
        return result.toString();
    }
}
