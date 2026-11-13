/* SPDX-License-Identifier: Apache-2.0 */
package io.github.h3nb.jlmodplus.librarydb

import io.github.h3nb.jlmodplus.config.Config
import java.io.File
import java.util.Collections

/** File-presence snapshot for a Room row, resolved away from the main thread. */
data class LibraryIconFiles(
    val row: LibraryAppRow,
    val mainIconPresent: Boolean,
    val portraitIconPresent: Boolean,
    val customIconPresent: Boolean,
)

internal fun resolveLibraryIconFiles(
    workdir: File,
    apps: List<LibraryAppRow>,
    previous: Map<Long, LibraryIconFiles> = emptyMap(),
    checkActive: () -> Unit = {},
): Map<Long, LibraryIconFiles> {
    val resolved = HashMap<Long, LibraryIconFiles>(apps.size)
    for ((index, app) in apps.withIndex()) {
        if (index % 64 == 0) checkActive()
        val prior = previous[app.id]
        resolved[app.id] = if (prior?.row == app) prior else {
            val appDirectory = File(File(workdir, "converted"), app.storageKey)
            LibraryIconFiles(
                row = app,
                mainIconPresent = File(appDirectory.absolutePath + Config.MIDLET_ICON_FILE).isFile,
                portraitIconPresent = LibraryIconOverride
                    .portraitOverrideFile(workdir, app.storageKey).isFile,
                customIconPresent = LibraryIconOverride
                    .persistedOverrideFile(workdir, app.storageKey).isFile,
            )
        }
    }
    checkActive()
    return Collections.unmodifiableMap(resolved)
}
