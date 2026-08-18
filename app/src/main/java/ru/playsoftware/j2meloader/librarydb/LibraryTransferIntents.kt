/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.librarydb

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import ru.playsoftware.j2meloader.R

/** Builds read-only Android shares for prepared Library transfer artifacts. */
object LibraryTransferIntents {
    @JvmStatic
    fun shareApp(
        context: Context,
        prepared: LibraryShareManager.PreparedShare,
        subject: String,
    ): Intent = chooser(
        context = context,
        uri = prepared.uri,
        mimeType = prepared.mimeType,
        fileName = prepared.fileName,
        subject = subject,
        chooserTitle = R.string.library_share_chooser_title,
    )

    @JvmStatic
    fun exportBundle(
        context: Context,
        prepared: LibraryAppBundleExporter.PreparedExport,
        subject: String,
    ): Intent = chooser(
        context = context,
        uri = prepared.uri,
        mimeType = prepared.mimeType,
        fileName = prepared.fileName,
        subject = subject,
        chooserTitle = R.string.library_export_chooser_title,
    )

    private fun chooser(
        context: Context,
        uri: Uri,
        mimeType: String,
        fileName: String,
        subject: String,
        @StringRes chooserTitle: Int,
    ): Intent {
        val send = Intent(Intent.ACTION_SEND)
            .setType(mimeType)
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newUri(context.contentResolver, fileName, uri)
        return Intent.createChooser(send, context.getString(chooserTitle))
    }
}
