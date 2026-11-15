/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.github.h3nb.jlmodplus.installer

import android.content.Context
import android.net.Uri
import com.android.dx.command.dexer.ConversionResult
import io.github.h3nb.jlmodplus.librarydb.LibraryAppBundleImporter
import java.io.File
import java.util.function.Consumer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Java-facing entry points keep asynchronous work independent of a disposable UI subscription. */
internal object InstallerAsync {
    fun interface Task {
        suspend fun execute(): Int
    }

    @JvmStatic fun loadInfo(installer: AppInstaller): Task = Task { installer.loadInfoAwait() }
    @JvmStatic fun useJarOnly(installer: AppInstaller): Task = Task { installer.useJarOnlyAwait() }
    @JvmStatic fun selectedJar(installer: AppInstaller, uri: Uri): Task =
        Task { installer.loadSelectedExternalJarAwait(uri) }
    @JvmStatic fun install(installer: AppInstaller): Task = Task { installer.installAwait() }

    @JvmStatic
    fun run(task: Task, completed: Consumer<Int>, failed: Consumer<Throwable>): Job =
        launchWorker {
            val result = try {
                task.execute()
            } catch (error: Exception) {
                failed.accept(error)
                return@launchWorker
            }
            completed.accept(result)
        }

    @JvmStatic
    fun prepareBundle(
        context: Context,
        uri: Uri,
        completed: Consumer<LibraryAppBundleImporter.PreparedImport>,
        failed: Consumer<Throwable>,
    ): Job = launchWorker {
        val prepared = try {
            withContext(Dispatchers.IO) {
                LibraryAppBundleImporter.prepare(context.applicationContext, uri)
            }
        } catch (error: Exception) {
            failed.accept(error)
            return@launchWorker
        }
        completed.accept(prepared)
    }

    @JvmStatic
    fun cleanupBundle(prepared: LibraryAppBundleImporter.PreparedImport): Job = launchWorker {
        withContext(Dispatchers.IO) { LibraryAppBundleImporter.cleanup(prepared) }
    }

    @JvmStatic
    fun reconvert(
        appDir: File,
        cancellation: InstallerExecutionCoordinator.Cancellation,
        completed: Consumer<ConversionResult>,
        failed: Consumer<Throwable>,
    ): Job = launchWorker {
        val result = try {
            withContext(Dispatchers.IO) {
                AppReconverter.reconvert(appDir, cancellation).also {
                    check(!AppReconverter.needsReconversion(appDir)) {
                        "Reconversion completed without a compatible marker"
                    }
                }
            }
        } catch (error: Exception) {
            failed.accept(error)
            return@launchWorker
        }
        completed.accept(result)
    }

    private fun launchWorker(block: suspend () -> Unit): Job =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch { block() }
}
