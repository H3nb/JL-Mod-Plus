/*
 * Copyright 2023-2024 Yury Kharchenko
 * Modified for JL-Mod Plus.
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

package io.github.h3nb.jlmodplus.mmapi.synth;

import java.util.List;

import javax.microedition.shell.MicroLoader;

import io.github.h3nb.jlmodplus.mmapi.Plugin;
import io.github.h3nb.jlmodplus.mmapi.synth.eas.LibEAS;

public class SynthPluginFactory {
	public static void loadPlugins(List<Plugin> plugins) {
		plugins.add(new SynthPlugin(new LibEAS(MicroLoader.getSoundBank())));
	}
}
