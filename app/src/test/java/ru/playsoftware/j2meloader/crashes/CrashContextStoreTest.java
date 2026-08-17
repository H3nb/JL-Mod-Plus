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

package ru.playsoftware.j2meloader.crashes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

public class CrashContextStoreTest {
	@Test
	public void contextRoundTripKeepsOnlyStructuredHighSignalFields() throws Exception {
		List<CrashContextStore.Breadcrumb> breadcrumbs = List.of(
				new CrashContextStore.Breadcrumb(1000, "library.apps", "open_config", "active"),
				new CrashContextStore.Breadcrumb(1200, "config.quick", "open", "active"),
				new CrashContextStore.Breadcrumb(1400, "config.graphics", "open", "entering")
		);
		CrashContextStore.Snapshot source = new CrashContextStore.Snapshot(
				"rabc123-9z",
				"main",
				"5d609c102358286669b81468f1e0b61960703fa9",
				"emulatorDebug",
				"config.graphics",
				"config.quick",
				"open",
				"active",
				1500,
				breadcrumbs
		);

		ByteArrayOutputStream output = new ByteArrayOutputStream();
		CrashContextStore.write(source, output);
		CrashContextStore.Snapshot restored = CrashContextStore.read(
				new ByteArrayInputStream(output.toByteArray()));

		assertEquals(source.runId, restored.runId);
		assertEquals(source.buildCommit, restored.buildCommit);
		assertEquals(source.buildVariant, restored.buildVariant);
		assertEquals("config.graphics", restored.location);
		assertEquals("config.quick", restored.previousLocation);
		assertEquals("open", restored.action);
		assertEquals("active", restored.phase);
		assertEquals(3, restored.breadcrumbs.size());
		assertEquals("config.graphics", restored.breadcrumbs.get(2).location);
	}

	@Test
	public void decoderNeverRestoresMoreThanFourBreadcrumbs() throws Exception {
		ArrayList<CrashContextStore.Breadcrumb> breadcrumbs = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			breadcrumbs.add(new CrashContextStore.Breadcrumb(
					1000 + i, "screen." + i, "open", "active"));
		}
		CrashContextStore.Snapshot source = new CrashContextStore.Snapshot(
				"rabc123-9z", "main", "abcdef12", "emulatorDebug",
				"screen.current", "screen.previous", "open", "active", 2000, breadcrumbs);

		ByteArrayOutputStream output = new ByteArrayOutputStream();
		CrashContextStore.write(source, output);
		CrashContextStore.Snapshot restored = CrashContextStore.read(
				new ByteArrayInputStream(output.toByteArray()));

		assertEquals(CrashContextStore.MAX_BREADCRUMBS, restored.breadcrumbs.size());
	}

	@Test
	public void tokensAreBoundedAndCannotCarryFreeFormText() {
		assertEquals("config.graphics_tab",
				CrashContextStore.normalizeToken("Config.Graphics Tab", 48));
		assertNull(CrashContextStore.normalizeToken("   ", 48));
		assertTrue(CrashContextStore.normalizeToken("abcdefghijklmnopqrstuvwxyz", 8).length() <= 8);
	}

	@Test
	public void runIdsRejectPathsAndFreeFormValues() {
		assertTrue(CrashContextStore.isSafeRunId("rabc123-9z"));
		assertFalse(CrashContextStore.isSafeRunId("../rabc123"));
		assertFalse(CrashContextStore.isSafeRunId("run id"));
		assertFalse(CrashContextStore.isSafeRunId("r123456789012345678901"));
	}
}
