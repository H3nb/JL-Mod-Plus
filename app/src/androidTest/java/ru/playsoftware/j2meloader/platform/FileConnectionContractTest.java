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

package ru.playsoftware.j2meloader.platform;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Enumeration;

import javax.microedition.io.Connector;
import javax.microedition.io.file.FileConnection;
import javax.microedition.io.file.FileSystemRegistry;

@RunWith(AndroidJUnit4.class)
public class FileConnectionContractTest {
	@Test
	public void listRootsReturnsMountedJsr75RootsWithTrailingSeparators() {
		Enumeration roots = FileSystemRegistry.listRoots();
		assertNotNull(roots);
		while (roots.hasMoreElements()) {
			Object root = roots.nextElement();
			assertTrue("JSR-75 root must be a directory name", root instanceof String);
			assertTrue("JSR-75 root must end with '/'", ((String) root).endsWith("/"));
		}
	}

	@Test
	public void mountedRootConnectionReportsCapacityWithoutChangingGuestStorageContract()
			throws Exception {
		Enumeration roots = FileSystemRegistry.listRoots();
		assertTrue("JSR-75 must expose at least one mounted root", roots.hasMoreElements());
		String root = (String) roots.nextElement();
		FileConnection connection = (FileConnection) Connector.open(
				"file://localhost/" + root, Connector.READ);
		try {
			assertTrue("Mounted JSR-75 root must remain open", connection.isOpen());
			assertTrue("Mounted JSR-75 root must be a directory", connection.isDirectory());
			assertTrue("JSR-75 total capacity must be non-negative", connection.totalSize() >= 0);
			assertTrue("JSR-75 available capacity must be non-negative",
					connection.availableSize() >= 0);
		} finally {
			connection.close();
		}
	}
}
