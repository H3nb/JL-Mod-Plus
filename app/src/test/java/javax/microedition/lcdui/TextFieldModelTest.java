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

package javax.microedition.lcdui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** JVM characterization of the TextBox and TextField text-buffer semantics. */
public class TextFieldModelTest {
	@Test
	public void constructorStoresInitialState() {
		TextBox box = new TextBox("Title", "abc", 10, TextField.ANY);
		assertEquals("abc", box.getString());
		assertEquals(3, box.size());
		assertEquals(10, box.getMaxSize());
		assertEquals(TextField.ANY, box.getConstraints());
	}

	@Test
	public void nullTextBecomesEmptyString() {
		TextBox box = new TextBox("Title", null, 10, TextField.ANY);
		assertEquals("", box.getString());
		assertEquals(0, box.size());
	}

	@Test
	public void textLongerThanMaxSizeIsRejected() {
		try {
			new TextBox("Title", "abcdefghijk", 10, TextField.ANY);
			assertTrue("oversized constructor text must be rejected", false);
		} catch (IllegalArgumentException expected) {
			// contract
		}
		TextField field = new TextField(null, "ab", 10, TextField.ANY);
		try {
			field.setString("abcdefghijk");
			assertTrue("oversized setString must be rejected", false);
		} catch (IllegalArgumentException expected) {
			// contract
		}
		try {
			new TextField(null, "abcdefghijk", 10, TextField.ANY);
			assertTrue("oversized constructor text must be rejected", false);
		} catch (IllegalArgumentException expected) {
			// contract
		}
	}

	@Test
	public void maxSizeMustBePositive() {
		try {
			new TextBox("Title", "", 0, TextField.ANY);
			assertTrue("zero max size must be rejected", false);
		} catch (IllegalArgumentException expected) {
			// contract
		}
		try {
			new TextField("Label", "", -1, TextField.ANY);
			assertTrue("negative max size must be rejected", false);
		} catch (IllegalArgumentException expected) {
			// contract
		}
	}

	@Test
	public void setMaxSizeRoundTrip() {
		TextBox box = new TextBox("Title", "", 5, TextField.ANY);
		assertEquals(12, box.setMaxSize(12));
		assertEquals(12, box.getMaxSize());
	}

	@Test
	public void insertAndDeleteMaintainBuffer() {
		TextBox box = new TextBox("Title", "ac", 10, TextField.ANY);
		box.insert("b", 1);
		assertEquals("abc", box.getString());
		box.delete(1, 1);
		assertEquals("ac", box.getString());
	}

	@Test
	public void insertBeyondBoundsThrows() {
		TextBox box = new TextBox("Title", "abc", 10, TextField.ANY);
		try {
			box.insert("x", 5);
			assertTrue("insert with position past the end must throw", false);
		} catch (IndexOutOfBoundsException expected) {
			// string builder contract
		}
	}

	@Test
	public void deleteClampsToBufferEnd() {
		TextBox box = new TextBox("Title", "abc", 10, TextField.ANY);
		box.delete(1, 50);
		assertEquals("a", box.getString());
	}

	@Test
	public void charsRoundTrip() {
		TextBox box = new TextBox("Title", "abc", 10, TextField.ANY);
		char[] data = new char[3];
		assertEquals(3, box.getChars(data));
		assertEquals("abc", new String(data));
		box.setChars(new char[] {'x', 'y'}, 0, 2);
		assertEquals("xy", box.getString());
	}

	@Test
	public void caretPositionIsZeroWithoutView() {
		TextBox box = new TextBox("Title", "abc", 10, TextField.ANY);
		assertEquals(0, box.getCaretPosition());
	}

	@Test
	public void constraintsRoundTrip() {
		TextField field = new TextField("Label", "", 10, TextField.ANY);
		field.setConstraints(TextField.PASSWORD | TextField.NUMERIC);
		assertEquals(TextField.PASSWORD | TextField.NUMERIC, field.getConstraints());
	}

	@Test
	public void nullReplaceWithEmptyString() {
		TextBox box = new TextBox("Title", "abc", 10, TextField.ANY);
		box.setString(null);
		assertEquals("", box.getString());
	}

	@Test
	public void textFieldIsAnItem() {
		TextField field = new TextField("Label", "value", 10, TextField.ANY);
		assertEquals("Label", field.getLabel());
		assertEquals("value", field.getString());
	}
}