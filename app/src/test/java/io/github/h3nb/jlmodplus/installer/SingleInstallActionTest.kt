/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.github.h3nb.jlmodplus.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class SingleInstallActionTest {
    private fun source(
        status: BulkInstallStatus,
        jarAvailable: Boolean = true,
        reinstallAppId: Long? = null,
    ): BulkInstallItem {
        val source = File("game.jad")
        val jar = File("game.jar").takeIf { jarAvailable }
        return BulkInstallItem(
            id = "game",
            unit = BulkSourceUnit(
                id = "game",
                origin = BulkSourceOrigin.ExplicitSelection,
                kind = BulkSourceKind.JadJarPair,
                primaryFile = source,
                sourceFiles = listOf(source),
                jarFile = jar,
                reinstallAppId = reinstallAppId,
            ),
            name = "Game",
            vendor = "Test",
            version = "1.0",
            status = status,
            action = BulkInstallAction.Skip,
            selected = false,
        )
    }

    @Test
    fun normalInstallAndExplicitReplacementDoNotRequireBatchSelection() {
        assertEquals(BulkInstallAction.Install, singleInstallAction(source(BulkInstallStatus.New)))
        assertEquals(BulkInstallAction.Install, singleInstallAction(source(BulkInstallStatus.Update)))
        assertEquals(BulkInstallAction.Install, singleInstallAction(source(BulkInstallStatus.Downgrade)))
        assertEquals(BulkInstallAction.Install, singleInstallAction(source(BulkInstallStatus.ReinstallOrVariant)))
    }

    @Test
    fun alreadyInstalledSourceCanBeExplicitlyReinstalled() {
        val item = source(BulkInstallStatus.AlreadyInstalled)
        // Ordinary batch planning intentionally leaves an already installed item unselected.
        assertEquals(false, item.installable)
        assertEquals(BulkInstallAction.Reinstall, singleInstallAction(item))
    }

    @Test
    fun specialReviewDecisionsRemainExplicit() {
        assertEquals(
            BulkInstallAction.InstallSeparateCopy,
            singleInstallAction(source(BulkInstallStatus.AmbiguousInstalledMatch)),
        )
        assertEquals(
            BulkInstallAction.InstallJarOnly,
            singleInstallAction(source(BulkInstallStatus.JadJarMismatch)),
        )
        assertNull(singleInstallAction(source(BulkInstallStatus.JadJarMismatch, jarAvailable = false)))
    }

    @Test
    fun invalidSourcesAndIdentityBoundReinstallsDoNotUseSingleSourceExecution() {
        assertNull(singleInstallAction(source(BulkInstallStatus.SourceError)))
        assertNull(singleInstallAction(source(BulkInstallStatus.RemoteSourceUnsupported)))
        assertNull(singleInstallAction(source(BulkInstallStatus.AlreadyInstalled, reinstallAppId = 7L)))
    }
}
