/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.h3nb.jlmodplus.installer

import android.content.res.Resources
import io.github.h3nb.jlmodplus.R

/** Android presentation only; raw diagnostic prose is deliberately not an input. */
internal fun bulkInstallReviewReasonText(resources: Resources, reason: BulkInstallReviewReason): String = when (reason) {
    is BulkInstallReviewReason.AmbiguousInstalledMatch -> resources.getQuantityString(
        R.plurals.bulk_install_reason_ambiguous_match, reason.matchCount, reason.matchCount,
    )
    BulkInstallReviewReason.JadJarMismatch -> resources.getString(R.string.bulk_install_reason_jad_jar_mismatch)
    BulkInstallReviewReason.ConflictingJads -> resources.getString(R.string.bulk_install_reason_conflicting_jads)
    BulkInstallReviewReason.RemoteJarUnsupported -> resources.getString(R.string.bulk_install_reason_remote_jar)
    BulkInstallReviewReason.JadMissingJarUrl -> resources.getString(R.string.bulk_install_reason_missing_jar_url)
    is BulkInstallReviewReason.UnsupportedJarUriScheme -> resources.getString(
        R.string.bulk_install_reason_unsupported_uri_scheme, reason.scheme,
    )
    BulkInstallReviewReason.JadMissingParentDirectory -> resources.getString(R.string.bulk_install_reason_missing_parent)
    is BulkInstallReviewReason.ReferencedJarMissing -> resources.getString(
        R.string.bulk_install_reason_jar_not_found, reason.reference,
    )
    BulkInstallReviewReason.DuplicateBatchSource -> resources.getString(R.string.bulk_install_reason_duplicate_source)
    BulkInstallReviewReason.SameVersionConflict -> resources.getString(R.string.bulk_install_reason_version_conflict)
    BulkInstallReviewReason.OlderBatchCandidate -> resources.getString(R.string.bulk_install_reason_older_candidate)
    BulkInstallReviewReason.SourceError -> resources.getString(R.string.bulk_install_reason_source_error)
    BulkInstallReviewReason.RetainedReinstallSourceMissing -> resources.getString(R.string.bulk_install_reinstall_source_missing)
}
