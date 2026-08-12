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
}
