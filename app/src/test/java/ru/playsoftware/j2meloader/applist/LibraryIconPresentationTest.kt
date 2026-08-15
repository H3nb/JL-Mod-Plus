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

package ru.playsoftware.j2meloader.applist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryIconPresentationTest {
    @Test
    fun denseRoundSubjectKeepsBreathingRoom() {
        val decision = decideLibraryIconPresentation(
            input(
                transparentRatio = 0.34f,
                boundsCoverage = 0.72f,
                occupancy = 0.80f,
                aspectFill = 0.98f,
            ),
        )

        assertEquals(LibraryIconPresentationMode.Subject, decision.mode)
        assertTrue(decision.visualScale in 0.60f..0.63f)
    }

    @Test
    fun circularObjectWithDominantColorIsNotPromotedToBackplate() {
        val decision = decideLibraryIconPresentation(
            input(
                transparentRatio = 0.21f,
                boundsCoverage = 1f,
                occupancy = 0.79f,
                aspectFill = 1f,
                hasBackingColor = true,
            ),
        )

        assertEquals(LibraryIconPresentationMode.Subject, decision.mode)
        assertTrue(decision.visualScale < 0.66f)
    }

    @Test
    fun elongatedSubjectGetsMoreRoomThanDenseRoundSubject() {
        val denseRound = decideLibraryIconPresentation(
            input(
                transparentRatio = 0.34f,
                boundsCoverage = 0.72f,
                occupancy = 0.80f,
                aspectFill = 0.98f,
            ),
        )
        val elongated = decideLibraryIconPresentation(
            input(
                transparentRatio = 0.30f,
                boundsCoverage = 0.64f,
                occupancy = 0.70f,
                aspectFill = 0.22f,
            ),
        )

        assertEquals(LibraryIconPresentationMode.Subject, denseRound.mode)
        assertEquals(LibraryIconPresentationMode.Subject, elongated.mode)
        assertTrue(elongated.visualScale in 0.72f..0.75f)
        assertTrue(elongated.visualScale > denseRound.visualScale)
    }

    @Test
    fun extremelySparseSubjectNeverInflatesToSlotEdges() {
        val decision = decideLibraryIconPresentation(
            input(
                transparentRatio = 0.90f,
                boundsCoverage = 0.30f,
                occupancy = 0.05f,
                aspectFill = 0.10f,
            ),
        )

        assertEquals(LibraryIconPresentationMode.Subject, decision.mode)
        assertEquals(0.78f, decision.visualScale, 0.0001f)
    }

    @Test
    fun detectedSelfBackedArtworkUsesBackedPresentation() {
        val decision = decideLibraryIconPresentation(
            input(
                transparentRatio = 0.12f,
                boundsCoverage = 0.88f,
                occupancy = 0.91f,
                hasBackingColor = true,
            ),
        )

        assertEquals(LibraryIconPresentationMode.Backed, decision.mode)
        assertEquals(0.86f, decision.visualScale, 0.0001f)
    }

    @Test
    fun matteFramedArtworkBecomesContainedSubject() {
        val decision = decideLibraryIconPresentation(
            input(
                transparentRatio = 0f,
                boundsCoverage = 1f,
                occupancy = 1f,
                hasFramedCrop = true,
            ),
        )

        assertEquals(LibraryIconPresentationMode.Subject, decision.mode)
        assertEquals(0.74f, decision.visualScale, 0.0001f)
    }

    @Test
    fun opaqueHighDiversityCoverCanCropToFill() {
        val decision = decideLibraryIconPresentation(
            input(
                transparentRatio = 0.01f,
                boundsCoverage = 0.995f,
                occupancy = 0.98f,
                highColorDiversity = true,
                sourceAspectRatio = 0.75f,
            ),
        )

        assertEquals(LibraryIconPresentationMode.Cover, decision.mode)
    }

    @Test
    fun coverWinsOverBackingEvidence() {
        val decision = decideLibraryIconPresentation(
            input(
                transparentRatio = 0.01f,
                boundsCoverage = 0.995f,
                occupancy = 0.98f,
                hasBackingColor = true,
                highColorDiversity = true,
                sourceAspectRatio = 0.75f,
            ),
        )

        assertEquals(LibraryIconPresentationMode.Cover, decision.mode)
    }

    @Test
    fun lowDiversityOpaqueArtworkFallsBackToSafeFit() {
        val decision = decideLibraryIconPresentation(
            input(
                transparentRatio = 0f,
                boundsCoverage = 1f,
                occupancy = 1f,
                highColorDiversity = false,
                sourceAspectRatio = 0.75f,
            ),
        )

        assertEquals(LibraryIconPresentationMode.SafeFit, decision.mode)
    }

    @Test
    fun extremeBannerDoesNotReceiveDestructiveCoverCrop() {
        val decision = decideLibraryIconPresentation(
            input(
                transparentRatio = 0f,
                boundsCoverage = 1f,
                occupancy = 1f,
                highColorDiversity = true,
                sourceAspectRatio = 2.2f,
            ),
        )

        assertEquals(LibraryIconPresentationMode.SafeFit, decision.mode)
    }

    @Test
    fun coverConfidenceBoundaryIsInclusive() {
        val narrow = decideLibraryIconPresentation(
            input(
                transparentRatio = 0.025f,
                boundsCoverage = 0.985f,
                occupancy = 0.94f,
                highColorDiversity = true,
                sourceAspectRatio = 0.62f,
            ),
        )
        val wide = decideLibraryIconPresentation(
            input(
                transparentRatio = 0.025f,
                boundsCoverage = 0.985f,
                occupancy = 0.94f,
                highColorDiversity = true,
                sourceAspectRatio = 1.62f,
            ),
        )

        assertEquals(LibraryIconPresentationMode.Cover, narrow.mode)
        assertEquals(LibraryIconPresentationMode.Cover, wide.mode)
    }

    @Test
    fun uncertainOpaqueArtworkStaysSafeFit() {
        val decision = decideLibraryIconPresentation(
            input(
                transparentRatio = 0.03f,
                boundsCoverage = 0.98f,
                occupancy = 0.93f,
                highColorDiversity = true,
                sourceAspectRatio = 0.75f,
            ),
        )

        assertEquals(LibraryIconPresentationMode.SafeFit, decision.mode)
    }

    private fun input(
        transparentRatio: Float = 0f,
        boundsCoverage: Float = 1f,
        occupancy: Float = 1f,
        aspectFill: Float = 1f,
        hasFramedCrop: Boolean = false,
        hasBackingColor: Boolean = false,
        highColorDiversity: Boolean = false,
        sourceAspectRatio: Float = 1f,
    ) = LibraryIconPresentationInput(
        transparentRatio = transparentRatio,
        boundsCoverage = boundsCoverage,
        occupancy = occupancy,
        aspectFill = aspectFill,
        hasFramedCrop = hasFramedCrop,
        hasBackingColor = hasBackingColor,
        highColorDiversity = highColorDiversity,
        sourceAspectRatio = sourceAspectRatio,
    )
}
