/*
 * Copyright 2018 Nikita Shakarun
 * Modified in 2026 for parent-owned Memory Editor ABI routing.
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

package javax.microedition.shell;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class CoreClassLoader extends ClassLoader {
	public static final Pattern INCLUDE = Pattern.compile("java\\..+|com\\..+|javax\\..+|mmpp\\..+|org.xml.sax.+");
	public static final Pattern EXCLUDE = initExcludePattern();
	private static final String TIMING_BRIDGE = "javax.microedition.shell.GuestTimingBridge";
	private static final String MEMORY_PROBE = "javax.microedition.shell.MemoryProbe";
	private static final String CUSTOM_TIMER = "javax.microedition.shell.custom.Timer";
	private static final String CUSTOM_TIMER_TASK = "javax.microedition.shell.custom.TimerTask";

	private static Pattern initExcludePattern() {
		String prop = MidletSystem.getProperty("emulator.classpath.exclude");
		if (prop == null) {
			return null;
		}
		String[] list = prop.split("[:;]");
		List<String> parts = new ArrayList<>(list.length);
		for (String value : list) {
			String s = value.trim();
			if (s.isEmpty()) {
				continue;
			}
			parts.add(s.replace(".", "\\.") + ".*");
		}
		if (parts.isEmpty()) {
			return null;
		}
		return Pattern.compile(TextUtils.join("|", parts));
	}

	public CoreClassLoader(ClassLoader parent) {
		super(parent);
	}

	@Override
	protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
		// Transformed guest bytecode links these names directly. They must always resolve through
		// the parent-owned emulator implementation, even when a legacy classpath exclusion matches
		// javax.microedition.shell or the guest archive contains a shadow class with the same name.
		if (isTimingAbiClass(name) || isMemoryProbeAbiClass(name)) {
			return super.loadClass(name, resolve);
		}
		if (EXCLUDE != null && EXCLUDE.matcher(name).matches()) {
			throw new ClassNotFoundException();
		}
		if (INCLUDE.matcher(name).matches()) {
			return super.loadClass(name, resolve);
		}
		throw new ClassNotFoundException();
	}

	static boolean isTimingAbiClass(String name) {
		return TIMING_BRIDGE.equals(name)
				|| CUSTOM_TIMER.equals(name)
				|| name.startsWith(CUSTOM_TIMER + "$")
				|| CUSTOM_TIMER_TASK.equals(name)
				|| name.startsWith(CUSTOM_TIMER_TASK + "$");
	}

	static boolean isMemoryProbeAbiClass(String name) {
		return MEMORY_PROBE.equals(name) || name.startsWith(MEMORY_PROBE + "$");
	}
}
