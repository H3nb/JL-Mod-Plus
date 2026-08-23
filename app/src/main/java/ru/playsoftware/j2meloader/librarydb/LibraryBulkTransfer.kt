/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.playsoftware.j2meloader.librarydb

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Android/FileProvider publication boundary for plain JAR archives and universal app bundles. */
internal object LibraryBulkTransfer {
    private const val PROVIDER_SUFFIX = ".diagnostic-files"
    private const val SHARE_DIR = "library-share-bulk"
    private const val EXPORT_DIR = "library-export-bulk"
    private const val MIME_ZIP = "application/zip"
    private val mutex = Mutex()

    suspend fun prepareJarArchive(
        context: Context,
        sources: List<LibraryJarArchiveExporter.JarSource>,
        displayTitle: String,
    ): LibraryShareManager.PreparedShare = mutex.withLock {
        publish(
            context = context,
            directoryName = SHARE_DIR,
            displayTitle = displayTitle,
            mimeType = MIME_ZIP,
        ) { target ->
            LibraryJarArchiveExporter.exportToZip(sources, target)
        }.let { published ->
            LibraryShareManager.PreparedShare(
                uri = published.uri,
                fileName = published.fileName,
                mimeType = MIME_ZIP,
            )
        }
    }

    suspend fun prepareUniversalBundle(
        context: Context,
        sources: List<LibraryUniversalBundleExporter.AppSource>,
        displayTitle: String,
        onProgress: ((LibraryAppBundleExporter.Progress) -> Unit)? = null,
    ): LibraryAppBundleExporter.PreparedExport = mutex.withLock {
        publish(
            context = context,
            directoryName = EXPORT_DIR,
            displayTitle = displayTitle,
            mimeType = MIME_ZIP,
        ) { target ->
            LibraryUniversalBundleExporter.exportToZip(sources, target, onProgress)
        }.let { published ->
            LibraryAppBundleExporter.PreparedExport(
                uri = published.uri,
                fileName = published.fileName,
                mimeType = MIME_ZIP,
            )
        }
    }

    private suspend fun publish(
        context: Context,
        directoryName: String,
        displayTitle: String,
        mimeType: String,
        write: (File) -> Unit,
    ): Published = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, directoryName)
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Unable to create bulk transfer cache directory")
        }
        val fileName = LibraryAppBundleExporter.safeFileName(displayTitle)
        val target = File(directory, fileName)
        val staging = File(directory, "$fileName.tmp")
        try {
            write(staging)
            if (target.exists() && !target.delete()) {
                throw IOException("Unable to replace previous bulk transfer archive")
            }
            if (!staging.renameTo(target)) {
                throw IOException("Unable to publish bulk transfer archive")
            }
            Published(
                uri = FileProvider.getUriForFile(
                    context,
                    context.packageName + PROVIDER_SUFFIX,
                    target,
                ),
                fileName = fileName,
                mimeType = mimeType,
            )
        } finally {
            if (staging.exists()) staging.delete()
        }
    }

    private data class Published(
        val uri: Uri,
        val fileName: String,
        val mimeType: String,
    )
}
