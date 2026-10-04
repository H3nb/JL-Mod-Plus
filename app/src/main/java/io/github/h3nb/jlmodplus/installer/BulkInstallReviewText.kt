/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.h3nb.jlmodplus.installer

import android.content.res.Resources
import androidx.annotation.StringRes
import io.github.h3nb.jlmodplus.R

@StringRes
internal fun bulkInstallReviewReasonResource(reason: BulkInstallReviewReason): Int = when (reason) {
    is BulkInstallReviewReason.AmbiguousInstalledMatch -> R.string.bulk_install_reason_ambiguous_match
    BulkInstallReviewReason.JadJarMismatch -> R.string.bulk_install_reason_jad_jar_mismatch
    BulkInstallReviewReason.ConflictingJads -> R.string.bulk_install_reason_conflicting_jads
    BulkInstallReviewReason.RemoteJarUnsupported -> R.string.bulk_install_reason_remote_jar
    BulkInstallReviewReason.JadMissingJarUrl -> R.string.bulk_install_reason_missing_jar_url
    is BulkInstallReviewReason.UnsupportedJarUriScheme -> R.string.bulk_install_reason_unsupported_uri_scheme
    BulkInstallReviewReason.JadMissingParentDirectory -> R.string.bulk_install_reason_missing_parent
    is BulkInstallReviewReason.ReferencedJarMissing -> R.string.bulk_install_reason_jar_not_found
    BulkInstallReviewReason.DuplicateBatchSource -> R.string.bulk_install_reason_duplicate_source
    BulkInstallReviewReason.SameVersionConflict -> R.string.bulk_install_reason_version_conflict
    BulkInstallReviewReason.OlderBatchCandidate -> R.string.bulk_install_reason_older_candidate
    BulkInstallReviewReason.RetainedReinstallSourceMissing -> R.string.bulk_install_reinstall_source_missing
}

/** Android presentation only; raw diagnostic prose is deliberately not an input. */
internal fun bulkInstallReviewReasonText(resources: Resources, reason: BulkInstallReviewReason): String {
    val resource = bulkInstallReviewReasonResource(reason)
    return when (reason) {
        is BulkInstallReviewReason.AmbiguousInstalledMatch -> resources.getString(resource, reason.matchCount)
        is BulkInstallReviewReason.UnsupportedJarUriScheme -> resources.getString(resource, reason.scheme)
        is BulkInstallReviewReason.ReferencedJarMissing -> resources.getString(resource, reason.reference)
        else -> resources.getString(resource)
    }
}
