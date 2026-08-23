/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.librarydb;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.File;
import java.io.FileOutputStream;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class LibraryIconFileSourceTest {
	@Test
	public void prepareAcceptsPickerFileUri() throws Exception {
		Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		File source = new File(context.getCacheDir(), "picker-icon-source.png");
		Bitmap bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
		try {
			try (FileOutputStream output = new FileOutputStream(source)) {
				assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
			}
			File prepared = LibraryIconOverride.prepare(context, Uri.fromFile(source));
			try {
				assertTrue(prepared.isFile());
				assertTrue(prepared.length() > 0L);
			} finally {
				prepared.delete();
			}
		} finally {
			bitmap.recycle();
			source.delete();
		}
	}
}
