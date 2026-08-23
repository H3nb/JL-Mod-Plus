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
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package ru.playsoftware.j2meloader.crashes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class MidletSessionStoreTest {
    @Test
    public void markerSurvivesPendingAndSelectedClassUpdates() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try {
            MidletSessionStore.clear(context);
            MidletSessionStore.markPending(context, "/data/jlmod/converted/demo", "Demo MIDlet");

            MidletSessionStore.State pending = MidletSessionStore.read(context);
            assertNotNull(pending);
            assertEquals("/data/jlmod/converted/demo", pending.getAppPath());
            assertEquals("Demo MIDlet", pending.getAppName());
            assertNull(pending.getMainClass());

            MidletSessionStore.markStarted(
                    context,
                    pending.getAppPath(),
                    pending.getAppName(),
                    "com.example.DemoMidlet");
            MidletSessionStore.State started = MidletSessionStore.read(context);
            assertNotNull(started);
            assertEquals("com.example.DemoMidlet", started.getMainClass());
        } finally {
            MidletSessionStore.clear(context);
        }
        assertNull(MidletSessionStore.read(context));
    }
}
