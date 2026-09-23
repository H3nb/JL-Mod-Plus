/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package javax.microedition.shell;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class MicroLoaderFileConnectionRootTest {
	@Test
	public void privateDataUriUsesLaunchedWorkdir() {
		String primary = "/storage/emulated/0";
		String launched = primary + "/WorkdirA";

		assertEquals("file:///c:/WorkdirA/data/Bounce",
				MicroLoader.fileConnectionDataUri(launched, "Bounce", primary));
	}
}
