/* SPDX-License-Identifier: Apache-2.0 */
package javax.microedition.lcdui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/** A13/A14 contracts exercised with the Android-backed MIDP implementation. */
@RunWith(AndroidJUnit4.class)
public class MidpStageACompletionDeviceTest {
	@Test
	public void stringItemReturnsPreferredFontEvenWhenNotRendered() {
		StringItem item = new StringItem("label", "text");
		Font defaultFont = item.getFont();
		assertNotNull(defaultFont);
		assertEquals(Font.FACE_SYSTEM, defaultFont.getFace());

		Font preferred = Font.getFont(Font.FACE_MONOSPACE,
				Font.STYLE_BOLD, Font.SIZE_SMALL);
		item.setFont(preferred);
		assertSame(preferred, item.getFont());
		assertEquals(Font.FACE_MONOSPACE, item.getFont().getFace());
		item.setText("updated");
		assertSame(preferred, item.getFont());

		// MIDP explicitly defines null as a request for the implementation default.
		item.setFont(null);
		assertSame(Font.getDefaultFont(), item.getFont());
		assertEquals(Font.FACE_SYSTEM, item.getFont().getFace());
	}

	@Test
	public void fontHintContractAlsoAppliesToButtonAppearance() {
		StringItem button = new StringItem(null, "Go", Item.BUTTON);
		Font preferred = Font.getFont(Font.FACE_PROPORTIONAL,
				Font.STYLE_ITALIC, Font.SIZE_LARGE);
		button.setFont(preferred);
		assertSame(preferred, button.getFont());
		button.setFont(null);
		assertSame(Font.getDefaultFont(), button.getFont());
	}

	@Test
	public void duplicateItemReferenceIsRejectedAtomically() {
		StringItem repeated = new StringItem(null, "A");
		StringItem other = new StringItem(null, "B");
		try {
			new Form("invalid", new Item[]{repeated, other, repeated});
			fail("The same Item instance cannot occupy two positions in one Form");
		} catch (IllegalStateException expected) {
			// Identity duplication must be rejected before claiming either Item.
		}
		assertFalse(repeated.hasOwner());
		assertFalse(other.hasOwner());

		Form valid = new Form("valid", new Item[]{repeated, other});
		assertEquals(2, valid.size());
		assertSame(repeated, valid.get(0));
		assertSame(other, valid.get(1));
		assertSame(valid, repeated.getOwner());
		assertSame(valid, other.getOwner());
	}

	@Test
	public void ownedOrNullElementsFailWithoutTakingOwnershipOfEarlierItems() {
		Item alreadyOwned = new StringItem(null, "owned");
		Form first = new Form("first", new Item[]{alreadyOwned});
		Item candidate = new StringItem(null, "candidate");
		try {
			new Form("invalid", new Item[]{candidate, alreadyOwned});
			fail("Item already in a Form must be rejected");
		} catch (IllegalStateException expected) {
			// Validation must not move the earlier candidate into this Form.
		}
		assertFalse(candidate.hasOwner());
		assertSame(first, alreadyOwned.getOwner());

		try {
			new Form("invalid", new Item[]{candidate, null});
			fail("Null array elements must be rejected");
		} catch (NullPointerException expected) {
			// A failed constructor must not attach preceding items.
		}
		assertFalse(candidate.hasOwner());
		Form empty = new Form("empty", (Item[]) null);
		assertEquals(0, empty.size());
		assertEquals(0, new Form("empty", new Item[0]).size());
		empty.append(candidate);
		assertSame(empty, candidate.getOwner());
	}
}
