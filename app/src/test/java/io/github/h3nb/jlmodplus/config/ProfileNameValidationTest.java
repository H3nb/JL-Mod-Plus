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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ProfileNameValidationTest {
	@Test
	public void acceptsNamesThatAreSafeForProfileCollections() {
		assertTrue(Profile.isValidName("Comfortable layout"));
		assertTrue(Profile.isValidName("  日本語  "));
	}

	@Test
	public void rejectsEmptyTraversalAndControlNames() {
		assertFalse(Profile.isValidName(null));
		assertFalse(Profile.isValidName(""));
		assertFalse(Profile.isValidName("   "));
		assertFalse(Profile.isValidName("."));
		assertFalse(Profile.isValidName(".."));
		assertFalse(Profile.isValidName("nested/name"));
		assertFalse(Profile.isValidName("nested\\name"));
		assertFalse(Profile.isValidName("line\nname"));
	}
}
