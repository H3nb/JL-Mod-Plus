/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.woesss.j2me.installer

import android.content.Context
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.reactivex.Single
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.playsoftware.j2meloader.R
import ru.playsoftware.j2meloader.librarydb.LibraryFileOperations
import ru.playsoftware.j2meloader.librarydb.LibraryViewModel
import ru.playsoftware.j2meloader.librarydb.LibraryUniversalBundleStager

class BulkInstallViewModel : ViewModel() {
    sealed interface State {
        data object Idle : State
        data class Planning(val sourceLabel: String? = null) : State
        data class Review(val plan: BulkInstallPlan) : State
        data class Running(
            val plan: BulkInstallPlan,
            val completed: Int,
            val total: Int,
            val currentName: String,
            val results: List<BulkInstallResult>,
            val cancelRequested: Boolean,
            val stageLabel: Int? = null,
        ) : State
        data class Finished(
            val plan: BulkInstallPlan,
            val results: List<BulkInstallResult>,
            val cancelled: Boolean,
            val fatalError: String? = null,
        ) : State
        data class Error(val message: String) : State
    }

    private val mutableState = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = mutableState.asStateFlow()
    private val cancelRequested = AtomicBoolean()
    private var planningJob: Job? = null
    private var executionJob: Job? = null
    private val closeRequested = AtomicBoolean()
    private var bundleImport: LibraryUniversalBundleStager.PreparedBundle? = null
    private var previousResults: List<BulkInstallResult> = emptyList()

    fun reviewUnfinished(library: LibraryViewModel) {
        val finished = mutableState.value as? State.Finished ?: return
        if (!library.isReadyGeneration(finished.plan.generation, finished.plan.workdir)) {
            mutableState.value = State.Error("Library changed. Close this batch and select the sources again.")
            return
        }
        val unfinished = finished.results.filter {
            it.kind == BulkInstallResultKind.Failed || it.kind == BulkInstallResultKind.NotProcessed ||
                it.kind == BulkInstallResultKind.PartiallyInstalled
        }.associateBy { it.itemId }
        previousResults = finished.results.filter { it.itemId !in unfinished }
        mutableState.value = State.Review(finished.plan.copy(items = finished.plan.items.mapNotNull { item ->
            val result = unfinished[item.id] ?: return@mapNotNull null
            item.copy(selected = true,
                restoreAppId = result.installedAppId,
                restoreStorageKey = result.installedStorageKey)
        }))
    }

    fun planExplicit(
        uriStrings: List<String>,
        library: LibraryViewModel,
        omittedSourcesWarning: String? = null,
    ) {
        if (mutableState.value !is State.Idle) return
        mutableState.value = State.Planning()
        planningJob = viewModelScope.launch {
            try {
                val job = coroutineContext[Job]
                val plan = withContext(Dispatchers.IO) {
                    val files = uriStrings.mapNotNull { value ->
                        value.toUri().path?.let(::File)
                    }
                    BulkInstallPlanner.planExplicit(files, library) { job?.isActive == true }
                }
                val boundedPlan = if (omittedSourcesWarning != null) {
                    plan.copy(
                        warnings = plan.warnings + omittedSourcesWarning,
                    )
                } else {
                    plan
                }
                mutableState.value = State.Review(boundedPlan)
            } catch (_: CancellationException) {
                if (mutableState.value is State.Planning) mutableState.value = State.Idle
            } catch (error: Throwable) {
                mutableState.value = State.Error(boundedMessage(error))
            } finally {
                planningJob = null
            }
        }
    }

    /**
     * Builds an exact-identity reinstall plan for Library selection mode.
     *
     * Reinstalling by feeding retained JARs back through the normal source planner is ambiguous
     * when two installed rows share the same title/vendor. Keep the selected Room id and storage
     * key at the installer boundary so the batch can use AppInstaller's generation-bound reinstall
     * constructor for the precise row the user checked.
     */
    fun planReinstall(
        context: Context,
        appIds: List<Long>,
        library: LibraryViewModel,
        executeImmediately: Boolean = false,
    ) {
        if (mutableState.value !is State.Idle) return
        mutableState.value = State.Planning()
        planningJob = viewModelScope.launch {
            try {
                val job = coroutineContext[Job]
                val plan = withContext(Dispatchers.IO) {
                    check(job?.isActive != false) { "Reinstall planning cancelled" }
                    val generation = requireNotNull(library.readyGeneration()) {
                        "Library is not READY for reinstall"
                    }
                    val requested = appIds.distinct().sorted()
                    val rows = library.getApps(requested.toSet()).associateBy { it.id }
                    val missing = requested.filterNot(rows::containsKey)
                    val items = requested.mapNotNull { appId ->
                        check(job?.isActive != false) { "Reinstall planning cancelled" }
                        val app = rows[appId] ?: return@mapNotNull null
                        val retained = LibraryFileOperations.retainedJar(
                            generation.emulatorDir,
                            app.storageKey,
                        ).canonicalFile
                        val available = retained.isFile && retained.length() > 0L
                        val sourceFingerprint = if (available) {
                            runCatching { BulkInstallPlanner.fingerprint(listOf(retained)) }
                                .getOrDefault("")
                        } else {
                            ""
                        }
                        val sourceUnit = BulkSourceUnit(
                            id = "reinstall-$appId",
                            origin = BulkSourceOrigin.ExplicitSelection,
                            kind = BulkSourceKind.JarOnly,
                            primaryFile = retained,
                            sourceFiles = listOf(retained),
                            jarFile = retained,
                            reinstallAppId = app.id,
                            reinstallStorageKey = app.storageKey,
                        )
                        BulkInstallItem(
                            id = sourceUnit.id,
                            unit = sourceUnit,
                            name = app.title,
                            vendor = app.vendor,
                            version = app.version,
                            installedVersion = app.version,
                            groupKey = "reinstall\u0000${app.id}",
                            sourceFingerprint = sourceFingerprint,
                            status = if (available) {
                                BulkInstallStatus.ReinstallOrVariant
                            } else {
                                BulkInstallStatus.SourceError
                            },
                            preflightStatus = if (available) {
                                BulkInstallStatus.ReinstallOrVariant
                            } else {
                                BulkInstallStatus.SourceError
                            },
                            action = if (available) {
                                BulkInstallAction.Reinstall
                            } else {
                                BulkInstallAction.Skip
                            },
                            selected = available,
                            detail = if (available) null else {
                                context.getString(R.string.bulk_install_reinstall_source_missing)
                            },
                        )
                    }
                    val warnings = if (missing.isEmpty()) emptyList() else listOf(
                        context.resources.getQuantityString(
                            R.plurals.bulk_install_reinstall_missing_apps,
                            missing.size,
                            missing.size,
                        ),
                    )
                    BulkInstallPlan(
                        generation = generation.generation,
                        workdir = generation.emulatorDir.canonicalFile,
                        items = items,
                        warnings = warnings,
                    )
                }
                if (executeImmediately) {
                    // Reinstall targets are already installed, generation-bound rows. There is
                    // no source conflict or install-choice review to present; move straight to
                    // the existing progress/execution path after the exact plan is built.
                    executePlan(library, plan)
                } else {
                    mutableState.value = State.Review(plan)
                }
            } catch (_: CancellationException) {
                if (mutableState.value is State.Planning) mutableState.value = State.Idle
            } catch (error: Throwable) {
                mutableState.value = State.Error(boundedMessage(error))
            } finally {
                planningJob = null
            }
        }
    }

    fun planUniversalBundle(
        context: Context,
        uriString: String,
        library: LibraryViewModel,
        warning: String? = null,
    ) {
        if (mutableState.value !is State.Idle) return
        mutableState.value = State.Planning(uriString)
        planningJob = viewModelScope.launch {
            var staged: LibraryUniversalBundleStager.PreparedBundle? = null
            try {
                val job = coroutineContext[Job]
                val planned = withContext(Dispatchers.IO) {
                    staged = LibraryUniversalBundleStager.prepare(
                        context.applicationContext,
                        uriString.toUri(),
                    )
                    val sourceFiles = requireNotNull(staged).apps.map { it.prepared.jarFile }
                    BulkInstallPlanner.planExplicit(sourceFiles, library) { job?.isActive == true }
                }
                val prepared = requireNotNull(staged)
                val knownJars = prepared.apps.associateBy {
                    it.prepared.jarFile.canonicalFile.path
                }
                if (planned.items.any { it.unit.primaryFile.canonicalFile.path !in knownJars }) {
                    throw IllegalStateException("Universal app-bundle staging lost an app payload")
                }
                bundleImport = prepared
                staged = null
                val bundlePlan = planned.copy(
                    items = planned.items.map { item ->
                        item.copy(bundlePayloadAvailable = true)
                    },
                )
                mutableState.value = State.Review(
                    if (warning == null) bundlePlan
                    else bundlePlan.copy(warnings = bundlePlan.warnings + warning),
                )
            } catch (_: CancellationException) {
                if (mutableState.value is State.Planning) mutableState.value = State.Idle
            } catch (error: Throwable) {
                mutableState.value = State.Error(boundedMessage(error))
            } finally {
                LibraryUniversalBundleStager.cleanup(staged)
                planningJob = null
            }
        }
    }

    fun cancelPlanning() {
        planningJob?.cancel()
        planningJob = null
        if (mutableState.value is State.Planning) mutableState.value = State.Idle
    }

    fun toggle(itemId: String) {
        val review = mutableState.value as? State.Review ?: return
        val current = review.plan.items.firstOrNull { it.id == itemId } ?: return
        if (!current.installable) return
        val selecting = !current.selected
        val chosenAction = if (!selecting) {
            BulkInstallAction.Skip
        } else {
            when {
                current.unit.reinstallAppId != null -> BulkInstallAction.Reinstall
                current.status == BulkInstallStatus.AmbiguousInstalledMatch ->
                    BulkInstallAction.InstallSeparateCopy
                current.status == BulkInstallStatus.JadJarMismatch -> {
                    if (current.unit.jarFile == null) return
                    BulkInstallAction.InstallJarOnly
                }
                else -> BulkInstallAction.Install
            }
        }
        val items = review.plan.items.map { item ->
            when {
                item.id == itemId -> item.copy(action = chosenAction, selected = selecting)
                selecting && current.groupKey.isNotBlank() && item.groupKey == current.groupKey && item.selected ->
                    item.copy(action = BulkInstallAction.Skip, selected = false)
                else -> item
            }
        }
        mutableState.value = State.Review(review.plan.copy(items = items))
    }

    fun selectRecommended() {
        val review = mutableState.value as? State.Review ?: return
        val items = review.plan.items.map { item ->
            if ((item.unit.reinstallAppId != null && item.status == BulkInstallStatus.ReinstallOrVariant) ||
                item.status == BulkInstallStatus.New || item.status == BulkInstallStatus.Update) {
                item.copy(
                    action = if (item.unit.reinstallAppId != null) {
                        BulkInstallAction.Reinstall
                    } else {
                        BulkInstallAction.Install
                    },
                    selected = true,
                )
            } else {
                item.copy(action = BulkInstallAction.Skip, selected = false)
            }
        }
        mutableState.value = State.Review(review.plan.copy(items = items))
    }

    fun clearSelection() {
        val review = mutableState.value as? State.Review ?: return
        mutableState.value = State.Review(
            review.plan.copy(
                items = review.plan.items.map { it.copy(action = BulkInstallAction.Skip, selected = false) },
            ),
        )
    }

    fun execute(library: LibraryViewModel) {
        val review = mutableState.value as? State.Review ?: return
        executePlan(library, review.plan)
    }

    /** Starts a prepared plan without exposing the optional review surface to the user. */
    private fun executePlan(library: LibraryViewModel, plan: BulkInstallPlan) {
        if (executionJob != null) return
        val selected = plan.items.filter { it.selected && it.action != BulkInstallAction.Skip }
        if (selected.isEmpty()) {
            val failures = plan.items.mapNotNull { item ->
                item.detail?.let { detail ->
                    BulkInstallResult(item.id, item.name, BulkInstallResultKind.Failed, detail)
                }
            }
            mutableState.value = State.Finished(plan, failures, cancelled = false)
            return
        }
        closeRequested.set(false)
        cancelRequested.set(false)
        mutableState.value = State.Running(
            plan = plan,
            completed = 0,
            total = selected.size,
            currentName = selected.first().name,
            results = emptyList(),
            cancelRequested = false,
        )
        executionJob = viewModelScope.launch(Dispatchers.IO) {
            val results = ArrayList(previousResults)
            var fatalError: String? = null
            try {
                selected.forEachIndexed { index, item ->
                    if (cancelRequested.get() || fatalError != null) {
                        results += BulkInstallResult(item.id, item.name, BulkInstallResultKind.NotProcessed)
                        return@forEachIndexed
                    }
                    mutableState.value = State.Running(
                        plan = plan,
                        completed = index,
                        total = selected.size,
                        currentName = item.name,
                        results = results.toList(),
                        cancelRequested = cancelRequested.get(),
                    )
                    try {
                        results += executeItem(plan, item, library)
                    } catch (error: FatalBatchException) {
                        val detail = boundedMessage(error.cause ?: error)
                        results += BulkInstallResult(item.id, item.name, BulkInstallResultKind.Failed, detail)
                        fatalError = detail
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        results += BulkInstallResult(
                            item.id,
                            item.name,
                            BulkInstallResultKind.Failed,
                            boundedMessage(error),
                        )
                    }
                    mutableState.value = State.Running(
                        plan = plan,
                        completed = index + 1,
                        total = selected.size,
                        currentName = item.name,
                        results = results.toList(),
                        cancelRequested = cancelRequested.get(),
                    )
                }
                mutableState.value = State.Finished(
                    plan = plan,
                    results = results,
                    cancelled = cancelRequested.get(),
                    fatalError = fatalError,
                )
            } finally {
                executionJob = null
                if (closeRequested.get()) {
                    cleanupBundleImport()
                }
            }
        }
    }

    fun cancel() {
        cancelRequested.set(true)
        val running = mutableState.value as? State.Running ?: return
        mutableState.value = running.copy(cancelRequested = true)
    }

    private suspend fun executeItem(
        plan: BulkInstallPlan,
        item: BulkInstallItem,
        library: LibraryViewModel,
    ): BulkInstallResult {
        if (!library.isReadyGeneration(plan.generation, plan.workdir)) {
            throw FatalBatchException(IllegalStateException("Library generation changed before batch item execution"))
        }
        if (item.restoreAppId != null) {
            return try {
                check(bundleImport != null) { "Bundle restore sources are no longer available" }
                restoreBundlePayload(plan, item, item.restoreAppId, item.restoreStorageKey, library)
                BulkInstallResult(item.id, item.name, BulkInstallResultKind.Reinstalled)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                BulkInstallResult(item.id, item.name, BulkInstallResultKind.PartiallyInstalled,
                    boundedMessage(error), item.restoreAppId, item.restoreStorageKey)
            }
        }
        val requestedSource = when (item.action) {
            BulkInstallAction.InstallJarOnly -> item.unit.jarFile
                ?: return BulkInstallResult(
                    item.id,
                    item.name,
                    BulkInstallResultKind.Failed,
                    "JAR-only fallback is no longer available",
                )
            else -> item.unit.primaryFile
        }
        val scratch = InstallerScratch()
        var installer: AppInstaller? = null
        try {
            val copiedSources = item.unit.sourceFiles.mapIndexed { index, source ->
                val extension = source.extension.lowercase(Locale.ROOT).ifBlank { "bin" }
                scratch.copy(source, "source-$index.$extension")
            }
            if (BulkInstallPlanner.fingerprint(copiedSources) != item.sourceFingerprint) {
                return BulkInstallResult(
                    item.id,
                    item.name,
                    BulkInstallResultKind.Failed,
                    "Source changed after review; select the files again",
                )
            }
            val copiedByOriginalPath = item.unit.sourceFiles
                .zip(copiedSources)
                .associate { (original, copied) -> original.canonicalPath to copied }
            val activeInstaller = if (item.unit.reinstallAppId != null) {
                val storageKey = item.unit.reinstallStorageKey
                    ?: return failed(item, "Reinstall target identity is incomplete")
                val current = library.getApp(plan.generation, plan.workdir, item.unit.reinstallAppId)
                    ?: return failed(item, "Reinstall target is no longer installed")
                if (current.storageKey != storageKey) {
                    return failed(item, "Reinstall target changed after review")
                }
                AppInstaller(
                    item.unit.reinstallAppId,
                    plan.generation,
                    plan.workdir,
                    storageKey,
                    library,
                )
            } else {
                val source = copiedByOriginalPath[requestedSource.canonicalPath]
                    ?: return failed(item, "Selected installer source is no longer available")
                val resolvedJar = if (item.action == BulkInstallAction.InstallJarOnly) {
                    null
                } else {
                    item.unit.jarFile?.let { copiedByOriginalPath[it.canonicalPath] }
                }
                AppInstaller(source, resolvedJar, library, scratch)
            }
            installer = activeInstaller
            activeInstaller.setProgress { stage ->
                val running = mutableState.value as? State.Running
                if (running != null) mutableState.value = running.copy(stageLabel = when (stage) {
                    AppInstaller.Stage.READING -> R.string.loading_info
                    AppInstaller.Stage.DOWNLOADING -> R.string.installer_stage_download
                    AppInstaller.Stage.WAITING -> R.string.installer_stage_wait
                    AppInstaller.Stage.CONVERTING -> R.string.converting_wait
                    AppInstaller.Stage.SAVING -> R.string.installer_stage_save
                })
            }
            val currentCode = Single.create<Int>(activeInstaller::loadInfo).blockingGet()
            if (activeInstaller.expectedGeneration != plan.generation || activeInstaller.expectedWorkdir?.canonicalFile != plan.workdir) {
                throw FatalBatchException(IllegalStateException("Library generation changed during batch revalidation"))
            }
            val descriptor = activeInstaller.newDescriptor
                ?: return failed(item, "Installer returned no descriptor during revalidation")
            val candidates = library.findBySourceIdentity(
                plan.generation,
                plan.workdir,
                descriptor.name,
                descriptor.vendor,
            )
            val currentStatus = if (item.unit.reinstallAppId != null) {
                BulkInstallStatus.ReinstallOrVariant
            } else if (candidates.size > 1) {
                BulkInstallStatus.AmbiguousInstalledMatch
            } else {
                mapInstallerStatus(currentCode)
            }

            if (currentStatus == BulkInstallStatus.AlreadyInstalled) {
                if (!item.bundlePayloadAvailable || bundleImport == null) {
                    return BulkInstallResult(
                        item.id,
                        item.name,
                        BulkInstallResultKind.Skipped,
                        "Already installed after revalidation",
                    )
                }
                try {
                    restoreBundlePayloadIfPresent(plan, item, activeInstaller, library)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    return BulkInstallResult(item.id, item.name, BulkInstallResultKind.PartiallyInstalled,
                        boundedMessage(error), activeInstaller.installedId,
                        activeInstaller.installedPath?.let { File(it).name })
                }
                return BulkInstallResult(
                    item.id,
                    item.name,
                    BulkInstallResultKind.Reinstalled,
                    "Restored app data and settings",
                )
            }
            if (!authorized(item, currentStatus, candidates.size)) {
                return failed(
                    item,
                    "Library state changed after review (${item.preflightStatus} -> $currentStatus); review again",
                )
            }

            val installCode = Single.create<Int>(activeInstaller::install).blockingGet()
            if (installCode != AppInstaller.STATUS_SUCCESS) {
                return failed(item, "Installer stopped with status $installCode")
            }
            try {
                restoreBundlePayloadIfPresent(plan, item, activeInstaller, library)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                return BulkInstallResult(item.id, item.name, BulkInstallResultKind.PartiallyInstalled,
                    boundedMessage(error), activeInstaller.installedId,
                    File(activeInstaller.installedPath).name)
            }
            val kind = when {
                item.action == BulkInstallAction.InstallSeparateCopy -> BulkInstallResultKind.Installed
                item.action == BulkInstallAction.Reinstall -> BulkInstallResultKind.Reinstalled
                item.preflightStatus == BulkInstallStatus.New -> BulkInstallResultKind.Installed
                item.preflightStatus == BulkInstallStatus.Update -> BulkInstallResultKind.Updated
                else -> BulkInstallResultKind.Reinstalled
            }
            return BulkInstallResult(item.id, item.name, kind)
        } catch (error: Throwable) {
            if (isFatalEnvironmentError(error)) throw FatalBatchException(error)
            throw error
        } finally {
            if (installer == null) {
                scratch.clear()
            } else {
                installer.clearCache()
                installer.deleteTemp()
                // Exact-identity reinstall uses AppInstaller's own scratch directory, but the
                // source fingerprint copy above still belongs to this batch scratch instance.
                scratch.clear()
            }
        }
    }

    private suspend fun restoreBundlePayloadIfPresent(
        plan: BulkInstallPlan,
        item: BulkInstallItem,
        installer: AppInstaller,
        library: LibraryViewModel,
    ) {
        restoreBundlePayload(plan, item, installer.installedId,
            installer.installedPath?.let { File(it).name }, library)
    }

    private suspend fun restoreBundlePayload(
        plan: BulkInstallPlan,
        item: BulkInstallItem,
        installedId: Long,
        storageKey: String?,
        library: LibraryViewModel,
    ) {
        val bundle = bundleImport ?: return
        val staged = bundle.apps.firstOrNull {
            it.prepared.jarFile.canonicalFile.path == item.unit.primaryFile.canonicalFile.path
        } ?: throw IllegalStateException("Universal app-bundle payload is unavailable")
        if (installedId < 0L) throw IllegalStateException("Installed app identity is unavailable")
        val app = library.getApp(plan.generation, plan.workdir, installedId)
            ?: throw IllegalStateException("Installed app is not visible after bundle import")
        check(app.storageKey == storageKey) { "Bundle restore target changed" }
        library.restoreImportedBundleAwait(
            expectedGeneration = plan.generation,
            expectedWorkdir = plan.workdir,
            appId = app.id,
            storageKey = app.storageKey,
            prepared = staged.prepared,
        )
    }

    private fun authorized(
        item: BulkInstallItem,
        currentStatus: BulkInstallStatus,
        installedMatches: Int,
    ): Boolean = when (item.action) {
        BulkInstallAction.Reinstall ->
            item.unit.reinstallAppId != null && currentStatus == BulkInstallStatus.ReinstallOrVariant
        BulkInstallAction.InstallSeparateCopy ->
            installedMatches > 1 && currentStatus == BulkInstallStatus.AmbiguousInstalledMatch

        BulkInstallAction.InstallJarOnly ->
            currentStatus == BulkInstallStatus.New || currentStatus == BulkInstallStatus.Update

        BulkInstallAction.Install -> currentStatus == item.preflightStatus
        BulkInstallAction.Skip -> false
    }

    private fun mapInstallerStatus(status: Int): BulkInstallStatus = when (status) {
        AppInstaller.STATUS_NEW -> BulkInstallStatus.New
        AppInstaller.STATUS_NEWER -> BulkInstallStatus.Update
        AppInstaller.STATUS_OLDER -> BulkInstallStatus.Downgrade
        AppInstaller.STATUS_SAME -> BulkInstallStatus.AlreadyInstalled
        AppInstaller.STATUS_EQUAL -> BulkInstallStatus.ReinstallOrVariant
        AppInstaller.STATUS_UNMATCHED -> BulkInstallStatus.JadJarMismatch
        AppInstaller.STATUS_AMBIGUOUS -> BulkInstallStatus.AmbiguousInstalledMatch
        else -> BulkInstallStatus.SourceError
    }

    private fun isFatalEnvironmentError(error: Throwable): Boolean {
        var cursor: Throwable? = error
        while (cursor != null) {
            if (cursor is InstallerFailure ||
                (cursor is android.system.ErrnoException && cursor.errno == android.system.OsConstants.ENOSPC)) {
                return true
            }
            cursor = cursor.cause
        }
        return false
    }

    private fun failed(item: BulkInstallItem, detail: String) =
        BulkInstallResult(item.id, item.name, BulkInstallResultKind.Failed, detail)

    fun close() {
        planningJob?.cancel()
        planningJob = null
        cancelRequested.set(true)
        closeRequested.set(true)
        if (executionJob == null) {
            cleanupBundleImport()
        }
    }

    @Synchronized
    private fun cleanupBundleImport() {
        LibraryUniversalBundleStager.cleanup(bundleImport)
        bundleImport = null
    }

    override fun onCleared() {
        close()
        super.onCleared()
    }

    private class FatalBatchException(cause: Throwable) : RuntimeException(cause)

    private fun boundedMessage(error: Throwable): String {
        val detail = error.message?.trim().orEmpty()
        val text = if (detail.isBlank()) error.javaClass.simpleName else detail
        return text.take(512)
    }
}
