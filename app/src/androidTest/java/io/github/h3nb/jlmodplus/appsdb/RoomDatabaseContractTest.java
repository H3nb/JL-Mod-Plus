/*
 * Copyright 2026 H3NB
 *
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

package io.github.h3nb.jlmodplus.appsdb;

import android.content.Context;

import androidx.room.Room;
import androidx.sqlite.db.SimpleSQLiteQuery;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import io.github.h3nb.jlmodplus.applist.AppItem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Exercises the version-1 Room contract against a synthetic database file.
 * This deliberately uses the production DAO and RxJava2 return types so that
 * an upgrade cannot silently change persistence or asynchronous operations.
 */
public class RoomDatabaseContractTest {

	@Test
	public void versionOneDatabaseSupportsCrudFilteringAndReopen() {
		Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		File databaseFile = new File(context.getDatabasePath("room-contract-test.db").getParentFile(),
				"room-contract-test.db");
		if (databaseFile.exists()) {
			assertTrue(databaseFile.delete());
		}

		AppDatabase database = null;
		try {
			database = open(context, databaseFile);
			assertEquals(1, database.getOpenHelper().getReadableDatabase().getVersion());

			AppItemDao dao = database.appItemDao();
			AppItem alpha = new AppItem("alpha", "Alpha", "Vendor", "1.0");
			AppItem beta = new AppItem("beta", "Beta", "Vendor", "2.0");
			dao.insert(Arrays.asList(alpha, beta)).blockingAwait();

			List<AppItem> filtered = dao.getAllSingle(new SimpleSQLiteQuery(
					"SELECT * FROM apps WHERE title LIKE ? ORDER BY title ASC",
					new Object[]{"%Alpha%"})).blockingGet();
			assertEquals(1, filtered.size());
			assertEquals("alpha", filtered.get(0).getPath());

			AppItem stored = dao.get("Alpha", "Vendor");
			assertNotNull(stored);
			stored.setTitle("Alpha Updated");
			dao.update(stored).blockingAwait();
			assertNotNull(dao.get("Alpha Updated", "Vendor"));

			dao.delete(stored).blockingAwait();
			assertNull(dao.get("Alpha Updated", "Vendor"));
			dao.deleteAll().blockingAwait();
			assertTrue(dao.getAllSingle(new SimpleSQLiteQuery(
					"SELECT * FROM apps ORDER BY title ASC")).blockingGet().isEmpty());
		} finally {
			if (database != null) {
				database.close();
			}
		}

		try {
			AppDatabase reopened = open(context, databaseFile);
			try {
				assertEquals(1, reopened.getOpenHelper().getReadableDatabase().getVersion());
				assertTrue(reopened.appItemDao().getAllSingle(new SimpleSQLiteQuery(
						"SELECT * FROM apps")).blockingGet().isEmpty());
			} finally {
				reopened.close();
			}
		} finally {
			if (databaseFile.exists()) {
				assertTrue(databaseFile.delete());
			}
		}
	}

	@Test
	public void configurableDatabasePathsRemainIndependent() {
		Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		File firstFile = new File(context.getDatabasePath("room-contract-first.db").getParentFile(),
				"room-contract-first.db");
		File secondFile = new File(context.getDatabasePath("room-contract-second.db").getParentFile(),
				"room-contract-second.db");
		deleteIfPresent(firstFile);
		deleteIfPresent(secondFile);

		AppDatabase first = null;
		AppDatabase second = null;
		try {
			first = open(context, firstFile);
			first.appItemDao().insert(new AppItem("first", "First", "Vendor", "1"))
					.blockingAwait();
			second = open(context, secondFile);
			assertTrue(second.appItemDao().getAllSingle(new SimpleSQLiteQuery(
					"SELECT * FROM apps")).blockingGet().isEmpty());
			assertNotNull(first.appItemDao().get("First", "Vendor"));
		} finally {
			if (second != null) {
				second.close();
			}
			if (first != null) {
				first.close();
			}
			deleteIfPresent(firstFile);
			deleteIfPresent(secondFile);
		}
	}

	private static AppDatabase open(Context context, File file) {
		return Room.databaseBuilder(context, AppDatabase.class, file.getAbsolutePath()).build();
	}

	private static void deleteIfPresent(File file) {
		if (file.exists()) {
			assertTrue(file.delete());
		}
	}
}
