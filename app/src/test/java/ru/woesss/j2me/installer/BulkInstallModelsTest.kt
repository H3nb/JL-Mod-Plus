/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.woesss.j2me.installer

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BulkInstallModelsTest {
    @Test
    fun exactReinstallActionMakesAlreadyInstalledItemSelectable() {
        val source = File("/tmp/retained.jar")
        val unit = BulkSourceUnit(
            id = "reinstall-7",
            origin = BulkSourceOrigin.ExplicitSelection,
            kind = BulkSourceKind.JarOnly,
            primaryFile = source,
            sourceFiles = listOf(source),
            jarFile = source,
            reinstallAppId = 7L,
            reinstallStorageKey = "Demo",
        )
        val reinstall = BulkInstallItem(
            id = unit.id,
            unit = unit,
            name = "Demo",
            vendor = "Vendor",
            version = "1.0",
            status = BulkInstallStatus.AlreadyInstalled,
            action = BulkInstallAction.Reinstall,
            selected = true,
        )
        val ordinarySkip = reinstall.copy(action = BulkInstallAction.Skip, selected = false)

        assertTrue(reinstall.installable)
        assertFalse(ordinarySkip.installable)
        assertFalse(reinstall.copy(
            unit = unit.copy(reinstallAppId = null),
        ).installable)
    }
}
