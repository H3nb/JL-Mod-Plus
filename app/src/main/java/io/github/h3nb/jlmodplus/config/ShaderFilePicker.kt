// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.config

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File
import java.io.IOException
import java.util.Locale
import io.github.h3nb.jlmodplus.filepicker.FilePickerContract
import io.github.h3nb.jlmodplus.filepicker.FilteredFilePickerActivity

/** A single-tap, read-only picker restricted to the current installation's shader tree. */
internal fun shaderPickerIntent(
    context: Context,
    root: File,
    extensions: List<String>,
    title: String,
): Intent = Intent(context, FilteredFilePickerActivity::class.java).apply {
    putExtra(FilePickerContract.EXTRA_MODE, FilePickerContract.MODE_FILE)
    putExtra(FilePickerContract.EXTRA_ALLOW_MULTIPLE, false)
    putExtra(FilePickerContract.EXTRA_SINGLE_CLICK, true)
    putExtra(FilePickerContract.EXTRA_ALLOW_CREATE_DIR, false)
    putExtra(FilePickerContract.EXTRA_ROOT_PATH, root.canonicalPath)
    putExtra(FilePickerContract.EXTRA_START_PATH, root.canonicalPath)
    putExtra(FilePickerContract.EXTRA_TITLE, title)
    putStringArrayListExtra(FilePickerContract.EXTRA_ALLOWED_EXTENSIONS, ArrayList(extensions))
}

/** Validate the result independently of the picker UI (including symlinks). */
internal fun relativeShaderPath(
    root: File?,
    uri: Uri?,
    extensions: List<String>,
): String? {
    if (root == null || uri == null || (uri.scheme != "file" && uri.scheme != null)) return null
    val path = uri.path ?: return null
    return try {
        val rootFile = root.canonicalFile
        val picked = File(path).canonicalFile
        if (!picked.isFile || !picked.path.startsWith(rootFile.path + File.separator)) return null
        val relative = picked.path.substring(rootFile.path.length + 1)
            .replace(File.separatorChar, '/')
        if (extensions.none { relative.lowercase(Locale.ROOT).endsWith(it) }) return null
        relative
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }
}
