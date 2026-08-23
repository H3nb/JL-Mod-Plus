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

package ru.playsoftware.j2meloader.filepicker;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.activity.result.contract.ActivityResultContract;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Activity-result bridge for the app-owned filesystem picker. */
public final class FilePickerResultContract
        extends ActivityResultContract<Intent, List<Uri>> {
    @NonNull
    @Override
    public Intent createIntent(@NonNull Context context, @NonNull Intent input) {
        return input;
    }

    @NonNull
    @Override
    public List<Uri> parseResult(int resultCode, @Nullable Intent intent) {
        if (resultCode != Activity.RESULT_OK || intent == null) {
            return List.of();
        }

        Set<Uri> uris = new LinkedHashSet<>();
        ArrayList<String> paths = intent.getStringArrayListExtra(
                FilePickerContract.EXTRA_PATHS);
        if (paths != null) {
            for (String path : paths) {
                if (path != null && !path.isBlank()) {
                    addNormalized(uris, Uri.parse(path));
                }
            }
        }
        if (intent.getClipData() != null) {
            for (int i = 0; i < intent.getClipData().getItemCount(); i++) {
                addNormalized(uris, intent.getClipData().getItemAt(i).getUri());
            }
        }
        addNormalized(uris, intent.getData());
        return new ArrayList<>(uris);
    }

    private static void addNormalized(Set<Uri> uris, @Nullable Uri uri) {
        if (uri == null) return;
        if (uri.getScheme() == null && uri.getPath() != null) {
            uris.add(Uri.fromFile(new File(uri.getPath())));
        } else {
            uris.add(uri);
        }
    }
}
