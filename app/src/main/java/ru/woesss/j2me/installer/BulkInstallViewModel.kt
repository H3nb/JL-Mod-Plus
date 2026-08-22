/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.woesss.j2me.installer

import android.content.Context
import android.net.Uri
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
import ru.playsoftware.j2meloader.librarydb.LibraryAppBundleImporter
import ru.playsoftware.j2meloader.librarydb.LibraryViewModel
import ru.playsoftware.j2meloader.librarydb.LibraryUniversalBundleStager
import ru.woesss.j2me.jar.Descriptor

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
    private var bundleImport: LibraryUniversalBundleStager.PreparedBundle? = null

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
                        Uri.parse(value).path?.let(::File)
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
                mutableState.value = State.Review(
                    if (warning == null) planned else planned.copy(warnings = planned.warnings + warning),
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
            when (current.status) {
                BulkInstallStatus.AmbiguousInstalledMatch -> BulkInstallAction.InstallSeparateCopy
                BulkInstallStatus.JadJarMismatch -> {
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
            if (item.status == BulkInstallStatus.New || item.status == BulkInstallStatus.Update) {
                item.copy(action = BulkInstallAction.Install, selected = true)
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
        val selected = review.plan.items.filter { it.selected && it.action != BulkInstallAction.Skip }
        if (selected.isEmpty()) return
        cancelRequested.set(false)
        mutableState.value = State.Running(
            plan = review.plan,
            completed = 0,
            total = selected.size,
            currentName = selected.first().name,
            results = emptyList(),
            cancelRequested = false,
        )
        viewModelScope.launch(Dispatchers.IO) {
            val results = ArrayList<BulkInstallResult>()
            var fatalError: String? = null
            selected.forEachIndexed { index, item ->
                if (cancelRequested.get() || fatalError != null) return@forEachIndexed
                mutableState.value = State.Running(
                    plan = review.plan,
                    completed = index,
                    total = selected.size,
                    currentName = item.name,
                    results = results.toList(),
                    cancelRequested = cancelRequested.get(),
                )
                try {
                    results += executeItem(review.plan, item, library)
                } catch (error: FatalBatchException) {
                    val detail = boundedMessage(error.cause ?: error)
                    results += BulkInstallResult(item.id, item.name, BulkInstallResultKind.Failed, detail)
                    fatalError = detail
                } catch (error: Throwable) {
                    results += BulkInstallResult(
                        item.id,
                        item.name,
                        BulkInstallResultKind.Failed,
                        boundedMessage(error),
                    )
                }
                mutableState.value = State.Running(
                    plan = review.plan,
                    completed = index + 1,
                    total = selected.size,
                    currentName = item.name,
                    results = results.toList(),
                    cancelRequested = cancelRequested.get(),
                )
            }
            mutableState.value = State.Finished(
                plan = review.plan,
                results = results,
                cancelled = cancelRequested.get(),
                fatalError = fatalError,
            )
        }
    }

    fun cancel() {
        cancelRequested.set(true)
        val running = mutableState.value as? State.Running ?: return
        mutableState.value = running.copy(cancelRequested = true)
    }

    private fun executeItem(
        plan: BulkInstallPlan,
        item: BulkInstallItem,
        library: LibraryViewModel,
    ): BulkInstallResult {
        if (!library.isReadyGeneration(plan.generation, plan.workdir)) {
            throw FatalBatchException(IllegalStateException("Library generation changed before batch item execution"))
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
            val source = copiedByOriginalPath[requestedSource.canonicalPath]
                ?: return failed(item, "Selected installer source is no longer available")
            val resolvedJar = if (item.action == BulkInstallAction.InstallJarOnly) {
                null
            } else {
                item.unit.jarFile?.let { copiedByOriginalPath[it.canonicalPath] }
            }
            val activeInstaller = AppInstaller(source, resolvedJar, library, scratch)
            installer = activeInstaller
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
            val currentStatus = if (candidates.size > 1) {
                BulkInstallStatus.AmbiguousInstalledMatch
            } else {
                mapInstallerStatus(currentCode)
            }

            if (currentStatus == BulkInstallStatus.AlreadyInstalled) {
                return BulkInstallResult(
                    item.id,
                    item.name,
                    BulkInstallResultKind.Skipped,
                    "Already installed after revalidation",
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
            restoreBundlePayloadIfPresent(plan, item, activeInstaller, descriptor, library)
            val kind = when {
                item.action == BulkInstallAction.InstallSeparateCopy -> BulkInstallResultKind.Installed
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
            }
        }
    }

    private fun restoreBundlePayloadIfPresent(
        plan: BulkInstallPlan,
        item: BulkInstallItem,
        installer: AppInstaller,
        descriptor: Descriptor,
        library: LibraryViewModel,
    ) {
        val bundle = bundleImport ?: return
        val staged = bundle.apps.firstOrNull {
            it.prepared.jarFile.canonicalFile.path == item.unit.primaryFile.canonicalFile.path
        } ?: throw IllegalStateException("Universal app-bundle payload is unavailable")
        val installedId = installer.installedId
        if (installedId < 0L) throw IllegalStateException("Installed app identity is unavailable")
        val app = library.getApp(plan.generation, plan.workdir, installedId)
            ?: throw IllegalStateException("Installed app is not visible after bundle import")
        LibraryAppBundleImporter.validateSourceIdentity(
            staged.prepared,
            descriptor.name,
            descriptor.vendor,
        )
        LibraryAppBundleImporter.restore(
            prepared = staged.prepared,
            emulatorDir = plan.workdir,
            storageKey = app.storageKey,
        )
    }

    private fun authorized(
        item: BulkInstallItem,
        currentStatus: BulkInstallStatus,
        installedMatches: Int,
    ): Boolean = when (item.action) {
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
        else -> BulkInstallStatus.SourceError
    }

    private fun isFatalEnvironmentError(error: Throwable): Boolean {
        var cursor: Throwable? = error
        while (cursor != null) {
            val message = cursor.message.orEmpty().lowercase()
            if (message.contains("library generation changed") ||
                message.contains("library is not ready") ||
                message.contains("no space left on device") ||
                message.contains("enospc") ||
                message.contains("committed install did not become visible") ||
                message.contains("staging directory")
            ) {
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
