/* SPDX-License-Identifier: Apache-2.0 */
package javax.microedition.lcdui;
import static org.junit.Assert.*;
import org.junit.Test;

public class MidpTextModelTest {
    @Test public void rejectedMutationsAreAtomic() {
        MidpTextModel m = new MidpTextModel();
        m.setMaxSize(8);
        m.setConstraints(TextField.NUMERIC);
        m.setString("-2");
        assertThrows(IllegalArgumentException.class, () -> m.setMaxSize(1));
        assertEquals(8, m.getMaxSize());
        assertEquals("-2", m.getString());
        assertThrows(IllegalArgumentException.class, () -> m.setString("abc"));
        assertThrows(IllegalArgumentException.class, () -> m.insert("a", 1));
        assertEquals("-2", m.getString());
        assertThrows(StringIndexOutOfBoundsException.class, () -> m.delete(0, 9));
        assertEquals("-2", m.getString());
    }

    @Test public void capacityAndConstraintTransitions() {
        MidpTextModel m = new MidpTextModel();
        m.setMaxSize(7);
        m.setString("abcdefg");
        m.setMaxSize(4);
        assertEquals("abcd", m.getString());
        m.setConstraints(TextField.NUMERIC);
        assertEquals("", m.getString());
        m.setString("-4");
        m.setConstraints(TextField.ANY | TextField.PASSWORD);
        assertEquals("-4", m.getString());
        assertThrows(IllegalArgumentException.class, () -> m.setConstraints(99));
        assertEquals(TextField.ANY | TextField.PASSWORD, m.getConstraints());
    }

    @Test public void numericDecimalAndDraftSyntax() {
        MidpTextModel m = new MidpTextModel();
        m.setMaxSize(30);
        m.setConstraints(TextField.NUMERIC);
        m.setString("2147483647");
        assertThrows(IllegalArgumentException.class, () -> m.setString("2147483648"));
        assertTrue(MidpTextModel.incompleteDraft("-", TextField.NUMERIC));
        m.setConstraints(TextField.DECIMAL);
        for (String valid : new String[]{".5","-1.","123","-0.01"}) m.setString(valid);
        for (String invalid : new String[]{"-",".","-.","1e2","12a"}) {
            assertThrows(invalid, IllegalArgumentException.class, () -> m.setString(invalid));
        }
        assertEquals("-0.01", m.getString());
    }

    @Test public void insertAndCharacterArrays() {
        MidpTextModel m = new MidpTextModel();
        m.setMaxSize(12);
        m.setString("23");
        m.insert("1", -200);
        m.insert("4", 999);
        assertEquals("1234", m.getString());
        m.setChars(null, -42, Integer.MAX_VALUE);
        assertEquals("", m.getString());
        assertThrows(ArrayIndexOutOfBoundsException.class, () -> m.setChars(new char[]{'a'}, 0, 2));
        m.setChars(new char[]{'x','y','z'}, 1, 2);
        char[] result = new char[3];
        assertEquals(2, m.getChars(result));
        assertArrayEquals(new char[]{'y','z','\0'}, result);
        assertThrows(ArrayIndexOutOfBoundsException.class, () -> m.getChars(new char[1]));
        assertThrows(NullPointerException.class, () -> m.insert((String)null, 0));
    }

    @Test public void onlyAnyAcceptsLineBreaks() {
        MidpTextModel m = new MidpTextModel();
        m.setMaxSize(16);
        m.setString("a\nb");
        assertEquals("a\nb", m.getString());
        m.setConstraints(TextField.URL);
        assertEquals("", m.getString());
    }
}
