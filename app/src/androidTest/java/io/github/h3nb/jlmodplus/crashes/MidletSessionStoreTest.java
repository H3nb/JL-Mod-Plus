/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.h3nb.jlmodplus.crashes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class MidletSessionStoreTest {
    @Test
    public void startedRuntimePersistsOnlyRoutingIdentityAndGeneration() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try {
            MidletSessionStore.clear(context);
            MidletSessionStore.markStarted(
                    context,
                    "/data/jlmod/converted/demo",
                    "Demo MIDlet",
                    "com.example.DemoMidlet",
                    73L,
                    "runtime-generation-a");

            MidletSessionStore.State state = MidletSessionStore.read(context);

            assertNotNull(state);
            assertEquals("runtime-generation-a", state.getGeneration());
            assertEquals("/data/jlmod/converted/demo", state.getAppPath());
            assertEquals("Demo MIDlet", state.getAppName());
            assertEquals("com.example.DemoMidlet", state.getMainClass());
            assertEquals(73L, state.getAppId());
            assertTrue(state.isRuntimeSelected());
        } finally {
            MidletSessionStore.clear(context);
        }
    }

    @Test
    public void olderRuntimeCannotClearNewerGeneration() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try {
            MidletSessionStore.clear(context);
            MidletSessionStore.markStarted(
                    context, "/data/jlmod/converted/old", "Old", "example.Old", 1L, "old-generation");
            MidletSessionStore.markStarted(
                    context, "/data/jlmod/converted/new", "New", "example.New", 2L, "new-generation");

            MidletSessionStore.clear(context, "old-generation");

            MidletSessionStore.State state = MidletSessionStore.read(context);
            assertNotNull(state);
            assertEquals("new-generation", state.getGeneration());
            assertEquals("/data/jlmod/converted/new", state.getAppPath());

            MidletSessionStore.clear(context, "new-generation");
            assertNull(MidletSessionStore.read(context));
        } finally {
            MidletSessionStore.clear(context);
        }
    }

    @Test
    public void foregroundSelectionIsGenerationFenced() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try {
            MidletSessionStore.clear(context);
            MidletSessionStore.markStarted(
                    context, "/data/jlmod/converted/demo", "Demo", "example.Demo", 3L,
                    "current-generation");

            MidletSessionStore.setRuntimeSelected(context, "stale-generation", false);
            assertTrue(MidletSessionStore.read(context).isRuntimeSelected());

            MidletSessionStore.setRuntimeSelected(context, "current-generation", false);
            assertFalse(MidletSessionStore.read(context).isRuntimeSelected());

            MidletSessionStore.setRuntimeSelected(context, "current-generation", true);
            assertTrue(MidletSessionStore.read(context).isRuntimeSelected());
        } finally {
            MidletSessionStore.clear(context);
        }
    }

    @Test
    public void generationlessCompatibilityRecordIsExplicitlyNonRoutable() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try {
            MidletSessionStore.clear(context);
            MidletSessionStore.markStarted(
                    context, "/data/jlmod/converted/legacy", "Legacy", "example.Legacy", 9L);

            MidletSessionStore.State state = MidletSessionStore.read(context);

            assertNotNull(state);
            assertNull(state.getGeneration());
            assertEquals(9L, state.getAppId());
        } finally {
            MidletSessionStore.clear(context);
        }
    }
}
