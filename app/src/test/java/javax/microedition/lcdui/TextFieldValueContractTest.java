/* SPDX-License-Identifier: Apache-2.0 */
package javax.microedition.lcdui;

import static org.junit.Assert.*;
import org.junit.Test;

public class TextFieldValueContractTest {
    @Test public void textMutationsRespectBoundsAndRemainAtomic() {
        TextFieldValue value = new TextFieldValue();
        value.setMaxSize(5);
        value.setString("abc");
        value.insert("X", -100);
        assertEquals("Xabc", value.getString());
        value.insert("!", 999);
        assertEquals("Xabc!", value.getString());
        assertThrows(IllegalArgumentException.class, () -> value.insert("overflow", 0));
        assertEquals("Xabc!", value.getString());
        assertThrows(StringIndexOutOfBoundsException.class, () -> value.delete(4, 2));
        value.delete(1, 3);
        assertEquals("X!", value.getString());
        assertThrows(NullPointerException.class, () -> value.insert((String) null, 1));
        assertEquals("X!", value.getString());
    }

    @Test public void numericConstraintsTruncateAtomically() {
        TextFieldValue value = new TextFieldValue();
        value.setMaxSize(8);
        value.setConstraints(TextField.NUMERIC);
        value.setString("-123");
        assertThrows(IllegalArgumentException.class, () -> value.setMaxSize(1));
        assertEquals(8, value.getMaxSize());
        assertEquals("-123", value.getString());
        assertEquals(2, value.setMaxSize(2));
        assertEquals("-1", value.getString());
        assertThrows(IllegalArgumentException.class, () -> value.setString("-"));
        assertThrows(IllegalArgumentException.class, () -> value.setString("2147483648"));
        assertEquals("-1", value.getString());
        value.setConstraints(TextField.DECIMAL);
        value.setString(".5");
        value.setString("1.");
        value.setConstraints(TextField.NUMERIC);
        assertEquals("", value.getString());
    }

    @Test public void charArrayRangesAndNullAreSpecified() {
        TextFieldValue value = new TextFieldValue();
        value.setMaxSize(10);
        value.setChars("abc".toCharArray(), 1, 2);
        assertEquals("bc", value.getString());
        assertThrows(ArrayIndexOutOfBoundsException.class,
                () -> value.setChars("a".toCharArray(), 0, 2));
        assertThrows(ArrayIndexOutOfBoundsException.class,
                () -> value.insert("a".toCharArray(), 1, 1, 0));
        assertThrows(ArrayIndexOutOfBoundsException.class, () -> value.getChars(new char[1]));
        assertThrows(NullPointerException.class, () -> value.getChars(null));
        value.setChars(null, -99, Integer.MAX_VALUE);
        assertEquals("", value.getString());
    }

    @Test public void constraintBitsAndNullTextAreHandled() {
        TextFieldValue value = new TextFieldValue();
        value.setMaxSize(12);
        value.setString("abcd");
        assertThrows(IllegalArgumentException.class, () -> value.setConstraints(6));
        assertThrows(IllegalArgumentException.class, () -> value.setConstraints(1 << 22));
        assertEquals(TextField.ANY, value.getConstraints());
        assertEquals("abcd", value.getString());
        value.setConstraints(TextField.NUMERIC | TextField.PASSWORD);
        assertEquals("", value.getString());
        value.setString(null);
        assertEquals("", value.getString());
        assertThrows(IllegalArgumentException.class, () -> value.setMaxSize(0));
        assertEquals(12, value.getMaxSize());
    }
}
