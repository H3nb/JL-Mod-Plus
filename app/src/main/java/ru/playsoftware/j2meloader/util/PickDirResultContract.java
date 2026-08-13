/*
 *  Copyright 2021 Yury Kharchenko
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package ru.playsoftware.j2meloader.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;

import androidx.activity.result.contract.ActivityResultContract;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import ru.playsoftware.j2meloader.config.Config;

public class PickDirResultContract extends ActivityResultContract<String, Uri> {
	@NonNull
	@Override
	public Intent createIntent(@NonNull Context context, String input) {
		Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
				.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
						| Intent.FLAG_GRANT_WRITE_URI_PERMISSION
						| Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
						| Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
		String startPath = input == null ? Config.getEmulatorDir() : input;
		Uri initialUri = FileUtils.getTreeUriForPath(startPath);
		if (initialUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri);
		}
		return i;
	}

	@Override
	public Uri parseResult(int resultCode, @Nullable Intent intent) {
		if (resultCode == Activity.RESULT_OK && intent != null) {
			return intent.getData();
		}
		return null;
	}
}
