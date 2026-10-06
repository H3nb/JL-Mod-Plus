// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.installer;

import android.content.Context;

import com.android.dx.command.dexer.ConversionDiagnostic;
import com.android.dx.command.dexer.ConversionResult;

import io.github.h3nb.jlmodplus.BuildConfig;
import io.github.h3nb.jlmodplus.R;
import javax.microedition.shell.transform.MidletTransformMetadata;

/** Resolves structured conversion evidence into localized installer copy and shareable diagnostics. */
public final class ConversionWarningFormatter {
    private ConversionWarningFormatter() {
    }

    public static Presentation forResult(Context context, ConversionResult result) {
        if (context == null || result == null || !result.isSuccess() || !result.hasWarnings()) {
            return null;
        }
        String summary = context.getResources().getQuantityString(
                R.plurals.installer_conversion_warning_summary,
                result.getClassesSkipped(),
                result.getClassesSkipped());

        StringBuilder details = new StringBuilder();
        for (ConversionDiagnostic diagnostic : result.getDiagnostics()) {
            if (diagnostic.getAction() != ConversionDiagnostic.Action.SKIPPED) {
                continue;
            }
            if (details.length() > 0) {
                details.append("\n\n");
            }
            details.append(context.getString(
                    R.string.installer_conversion_skipped_entry,
                    diagnostic.getEntry()));
        }
        if (result.getDiagnosticsOmitted() > 0) {
            if (details.length() > 0) {
                details.append("\n\n");
            }
            details.append(context.getResources().getQuantityString(
                    R.plurals.installer_conversion_omitted_details,
                    result.getDiagnosticsOmitted(),
                    result.getDiagnosticsOmitted()));
        }
        return new Presentation(summary, details.toString(), technicalReport(result));
    }

    public static String warningSummary(Context context, ConversionResult result) {
        Presentation presentation = forResult(context, result);
        return presentation == null ? null : presentation.getSummary();
    }

    public static String technicalReport(ConversionResult result) {
        return technicalReport(result, true);
    }

    static String technicalReport(ConversionResult result, boolean includeBuild) {
        if (result == null) {
            return "";
        }
        StringBuilder report = new StringBuilder();
        if (includeBuild) {
            report.append("Build: ").append(BuildConfig.VERSION_NAME).append('\n');
        }
        report.append("Conversion result: ");
        if (!result.isSuccess()) {
            report.append("FAILED");
        } else if (result.hasWarnings()) {
            report.append("SUCCESS_WITH_WARNINGS");
        } else {
            report.append("SUCCESS");
        }
        report.append('\n');
        report.append("Classes discovered: ").append(result.getClassesDiscovered()).append('\n');
        report.append("Classes converted: ").append(result.getClassesConverted()).append('\n');
        report.append("Classes skipped: ").append(result.getClassesSkipped()).append('\n');
        report.append("Diagnostics omitted: ").append(result.getDiagnosticsOmitted()).append('\n');
        report.append("Skipped class names omitted: ")
                .append(result.getSkippedClassEntriesOmitted()).append('\n');
        report.append("Transform version: ")
                .append(MidletTransformMetadata.TRANSFORM_VERSION).append('\n');
        report.append("Source JAR modified: no");

        int index = 0;
        for (ConversionDiagnostic diagnostic : result.getDiagnostics()) {
            report.append("\n\n[").append(++index).append("]\n");
            if (diagnostic.getEntry() != null) {
                report.append("Entry: ").append(diagnostic.getEntry()).append('\n');
            }
            report.append("Phase: ").append(diagnostic.getPhase()).append('\n');
            report.append("Kind: ").append(diagnostic.getKind()).append('\n');
            report.append("Action: ").append(diagnostic.getAction());
            if (diagnostic.getTechnicalDetail() != null
                    && !diagnostic.getTechnicalDetail().isEmpty()) {
                report.append('\n').append("Detail: ").append(diagnostic.getTechnicalDetail());
            }
        }
        return report.toString();
    }

    public static final class Presentation {
        private final String summary;
        private final String details;
        private final String copyDetails;

        private Presentation(String summary, String details, String copyDetails) {
            this.summary = summary;
            this.details = details;
            this.copyDetails = copyDetails;
        }

        public String getSummary() {
            return summary;
        }

        public String getDetails() {
            return details;
        }

        public String getCopyDetails() {
            return copyDetails;
        }
    }
}
