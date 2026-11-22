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

package io.github.h3nb.jlmodplus.filepicker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import java.io.File

/** Converts raw picker paths into stable URIs for both Java and Kotlin callers. */
class FilePickerResultContract :
    ActivityResultContract<Intent, @JvmSuppressWildcards List<Uri>>() {
    override fun createIntent(context: Context, input: Intent): Intent = input

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        if (resultCode != Activity.RESULT_OK || intent == null) return emptyList()

        val uris = LinkedHashSet<Uri>()
        intent.getStringArrayListExtra(FilePickerContract.EXTRA_PATHS)?.forEach { path ->
            if (!path.isNullOrBlank()) addNormalized(uris, Uri.parse(path))
        }
        intent.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) {
                addNormalized(uris, clip.getItemAt(index).uri)
            }
        }
        addNormalized(uris, intent.data)
        return ArrayList(uris)
    }

    private fun addNormalized(uris: MutableSet<Uri>, uri: Uri?) {
        if (uri == null) return
        val path = uri.path
        uris.add(if (uri.scheme == null && path != null) Uri.fromFile(File(path)) else uri)
    }
}
