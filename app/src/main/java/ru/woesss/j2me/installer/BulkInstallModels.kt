/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.woesss.j2me.installer

import java.io.File

enum class BulkSourceOrigin {
    ExplicitSelection,
}

enum class BulkSourceKind {
    JarOnly,
    JadJarPair,
    Kjx,
}

enum class BulkInstallStatus {
    New,
    Update,
    Downgrade,
    AlreadyInstalled,
    ReinstallOrVariant,
    AmbiguousInstalledMatch,
    JadJarMismatch,
    Duplicate,
    OlderBatchCandidate,
    BatchConflict,
    RemoteSourceUnsupported,
    SourceError,
}

enum class BulkInstallAction {
    Install,
    Reinstall,
    Skip,
    InstallJarOnly,
    InstallSeparateCopy,
}

data class BulkSourceUnit(
    val id: String,
    val origin: BulkSourceOrigin,
    val kind: BulkSourceKind,
    val primaryFile: File,
    val sourceFiles: List<File>,
    val jadFile: File? = null,
    val jarFile: File? = null,
    /** Exact Library identity for a user-requested reinstall; avoids source-identity ambiguity. */
    val reinstallAppId: Long? = null,
    val reinstallStorageKey: String? = null,
    val discoveryStatus: BulkInstallStatus? = null,
    val discoveryDetail: String? = null,
)

data class BulkInstallItem(
    val id: String,
    val unit: BulkSourceUnit,
    val name: String,
    val vendor: String,
    val version: String,
    val installedVersion: String? = null,
    val descriptorAttributes: Map<String, String> = emptyMap(),
    val sourceFingerprint: String = "",
    val jarFingerprint: String? = null,
    val groupKey: String = "$name\u0000$vendor",
    val status: BulkInstallStatus,
    val preflightStatus: BulkInstallStatus = status,
    val action: BulkInstallAction,
    val selected: Boolean,
    val detail: String? = null,
    /** True when the source is a universal bundle carrying app-owned state for this item. */
    val bundlePayloadAvailable: Boolean = false,
) {
    val installable: Boolean
        get() = when (status) {
            BulkInstallStatus.New,
            BulkInstallStatus.Update,
            BulkInstallStatus.Downgrade,
            BulkInstallStatus.ReinstallOrVariant,
            BulkInstallStatus.AmbiguousInstalledMatch,
            BulkInstallStatus.JadJarMismatch,
            BulkInstallStatus.OlderBatchCandidate,
            BulkInstallStatus.BatchConflict,
            -> true

            BulkInstallStatus.AlreadyInstalled ->
                bundlePayloadAvailable ||
                    (action == BulkInstallAction.Reinstall && unit.reinstallAppId != null)

            else -> false
        }
}

data class BulkInstallPlan(
    val generation: Long,
    val workdir: File,
    val items: List<BulkInstallItem>,
    val warnings: List<String> = emptyList(),
) {
    val selectedCount: Int
        get() = items.count { it.selected && it.action != BulkInstallAction.Skip }
}

enum class BulkInstallResultKind {
    Installed,
    Updated,
    Reinstalled,
    Skipped,
    Failed,
}

data class BulkInstallResult(
    val itemId: String,
    val name: String,
    val kind: BulkInstallResultKind,
    val detail: String? = null,
)
