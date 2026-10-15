/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.h3nb.jlmodplus.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class AppLocaleTagsTest {
	@Test
	public void canonicalizesDeprecatedIndonesianAlias() {
		assertEquals("id", AppLocaleTags.canonicalize("in"));
	}

	@Test
	public void canonicalizesDeprecatedHebrewAlias() {
		assertEquals("he", AppLocaleTags.canonicalize("iw"));
	}

	@Test
	public void preservesCanonicalRegionAndScriptTags() {
		assertEquals("pt-BR", AppLocaleTags.canonicalize("pt-BR"));
		assertEquals("zh-Hant-TW", AppLocaleTags.canonicalize("zh-Hant-TW"));
	}

	@Test
	public void retiredSantaliFallsBackToSystem() {
		assertEquals("", AppLocaleTags.withoutRetiredSantali("sat"));
		assertEquals("", AppLocaleTags.withoutRetiredSantali("sat-Olck-IN"));
	}

	@Test
	public void retirementPreservesOtherRequestedLocalesAndTheirOrder() {
		assertEquals("id,pt-BR", AppLocaleTags.withoutRetiredSantali("sat,id,pt-BR"));
		assertEquals("en-US,he", AppLocaleTags.withoutRetiredSantali("en-US,sat-IN,he"));
	}

	@Test
	public void unrelatedOrSystemLocalesRequireNoMigration() {
		assertNull(AppLocaleTags.withoutRetiredSantali(""));
		assertNull(AppLocaleTags.withoutRetiredSantali("id,pt-BR"));
		assertNull(AppLocaleTags.withoutRetiredSantali("satirical"));
	}

	@Test
	public void preservesFollowSystemSentinel() {
		assertEquals("", AppLocaleTags.canonicalize(""));
	}
}
