/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.woesss.j2me.installer

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.TypedValue
import android.view.WindowManager
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.DialogFragment
import androidx.preference.PreferenceManager
import ru.playsoftware.j2meloader.R
import ru.playsoftware.j2meloader.filepicker.FilePickerContract
import ru.playsoftware.j2meloader.filepicker.FilteredFilePickerActivity
import ru.playsoftware.j2meloader.ui.JLModPlusTheme
import ru.playsoftware.j2meloader.util.Constants.PREF_LAST_PATH

class BulkInstallEntryDialog : DialogFragment() {
    private val filesLauncher: ActivityResultLauncher<Unit> = registerForActivityResult(
        object : ActivityResultContract<Unit, List<Uri>>() {
            override fun createIntent(context: Context, input: Unit): Intent = pickerIntent(
                context,
                FilePickerContract.MODE_FILE,
                allowMultiple = true,
            )

            override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
                if (resultCode != Activity.RESULT_OK || intent == null) return emptyList()
                val fromPaths = intent.getStringArrayListExtra(FilePickerContract.EXTRA_PATHS)
                    .orEmpty()
                    .map(Uri::parse)
                if (fromPaths.isNotEmpty()) return fromPaths
                val clip = intent.clipData
                if (clip != null) {
                    return buildList {
                        repeat(clip.itemCount) { index ->
                            clip.getItemAt(index).uri?.let(::add)
                        }
                    }
                }
                return listOfNotNull(intent.data)
            }
        },
    ) { uris ->
        if (uris.isEmpty() || !isAdded) return@registerForActivityResult
        rememberPath(uris.first())
        val manager = parentFragmentManager
        dismissAllowingStateLoss()
        BulkInstallerDialog.newFiles(uris).show(manager, TAG_BULK)
    }

    private val folderLauncher: ActivityResultLauncher<Unit> = registerForActivityResult(
        object : ActivityResultContract<Unit, Uri?>() {
            override fun createIntent(context: Context, input: Unit): Intent = pickerIntent(
                context,
                FilePickerContract.MODE_DIR,
                allowMultiple = false,
            )

            override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
                if (resultCode == Activity.RESULT_OK) intent?.data else null
        },
    ) { uri ->
        if (uri == null || !isAdded) return@registerForActivityResult
        rememberPath(uri)
        val manager = parentFragmentManager
        dismissAllowingStateLoss()
        BulkInstallerDialog.newFolder(uri).show(manager, TAG_BULK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCancelable = true
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val compose = ComposeView(requireContext()).apply {
            setContent {
                JLModPlusTheme {
                    EntryContent(
                        onFiles = { filesLauncher.launch(Unit) },
                        onFolder = { folderLauncher.launch(Unit) },
                        onClose = { dismissAllowingStateLoss() },
                    )
                }
            }
        }
        return Dialog(requireContext(), theme).apply {
            setContentView(compose)
            setCanceledOnTouchOutside(true)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
    }

    override fun onStart() {
        super.onStart()
        val window = dialog?.window ?: return
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val metrics = resources.displayMetrics
        val margin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 32f, metrics).toInt()
        val maxWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 480f, metrics).toInt()
        window.setLayout(
            minOf(maxWidth, metrics.widthPixels - margin).coerceAtLeast(1),
            WindowManager.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun pickerIntent(context: Context, mode: Int, allowMultiple: Boolean): Intent =
        Intent(context, FilteredFilePickerActivity::class.java).apply {
            putExtra(FilePickerContract.EXTRA_ALLOW_MULTIPLE, allowMultiple)
            putExtra(FilePickerContract.EXTRA_SINGLE_CLICK, false)
            putExtra(FilePickerContract.EXTRA_ALLOW_CREATE_DIR, false)
            putExtra(FilePickerContract.EXTRA_MODE, mode)
            putExtra(FilePickerContract.EXTRA_START_PATH, startPath(context))
        }

    private fun startPath(context: Context): String? {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val remembered = preferences.getString(PREF_LAST_PATH, null)
        if (!remembered.isNullOrBlank()) return remembered
        return Environment.getExternalStorageDirectory().takeIf { it.canRead() }?.absolutePath
    }

    private fun rememberPath(uri: Uri) {
        val path = uri.path ?: return
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .edit()
            .putString(PREF_LAST_PATH, path)
            .apply()
    }

    companion object {
        const val TAG = "BulkInstallEntryDialog"
        private const val TAG_BULK = "BulkInstallerDialog"
    }
}

@Composable
private fun EntryContent(onFiles: () -> Unit, onFolder: () -> Unit, onClose: () -> Unit) {
    Surface(
        shape = androidx.compose.material3.MaterialTheme.shapes.extraLarge,
        tonalElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.bulk_install_title),
                style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
            )
            Button(onClick = onFiles, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.bulk_install_select_files))
            }
            Button(onClick = onFolder, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.bulk_install_scan_folder))
            }
            OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.bulk_install_close))
            }
        }
    }
}
