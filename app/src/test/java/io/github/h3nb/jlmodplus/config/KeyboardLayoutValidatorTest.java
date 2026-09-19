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

package io.github.h3nb.jlmodplus.config;

import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class KeyboardLayoutValidatorTest {
	@Test
	public void acceptsGroupedDirectionalLayoutTypes() throws IOException {
		File file = File.createTempFile("virtual-keyboard-layout-", ".bin");
		try {
			try (DataOutputStream output = new DataOutputStream(new FileOutputStream(file))) {
				output.writeInt(0x564B4C00);
				output.writeInt(3);
				output.writeInt(3);
				output.writeInt(1);
				output.writeByte(7);
				output.writeInt(3);
				output.writeInt(1);
				output.writeByte(8);
				output.writeInt(-1);
				output.writeInt(0);
			}

			assertNull(KeyboardLayoutValidator.validate(file));
		} finally {
			//noinspection ResultOfMethodCallIgnored
			file.delete();
		}
	}
}
