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

package io.github.h3nb.jlmodplus.applist

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
        assertTrue(decision.visualScale in 0.61f..0.63f)
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
        assertTrue(decision.visualScale in 0.61f..0.63f)
    }

    @Test
    fun roundedBackplateOccupancyCanPromoteDominantColorToBacked() {
        val decision = decideLibraryIconPresentation(
            input(
                transparentRatio = 0.16f,
                boundsCoverage = 0.94f,
                occupancy = 0.84f,
                aspectFill = 1f,
                hasBackingColor = true,
            ),
        )

        assertEquals(LibraryIconPresentationMode.Backed, decision.mode)
        assertEquals(0.92f, decision.visualScale, 0.0001f)
    }

    @Test
    fun sparseElongatedSubjectGetsMoreRoomThanDenseRoundSubject() {
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
        assertEquals(0.90f, elongated.visualScale, 0.0001f)
        assertTrue(elongated.visualScale > denseRound.visualScale)
    }

    @Test
    fun moderatelySparseSubjectUsesOpticalAreaInsteadOfFixedScale() {
        val decision = decideLibraryIconPresentation(
            input(
                transparentRatio = 0.45f,
                boundsCoverage = 0.62f,
                occupancy = 0.58f,
                aspectFill = 0.78f,
            ),
        )

        assertEquals(LibraryIconPresentationMode.Subject, decision.mode)
        assertTrue(decision.visualScale in 0.90f..0.90f)
    }

    @Test
    fun extremelySparseSubjectNeverInflatesPastConservativeCap() {
        val decision = decideLibraryIconPresentation(
            input(
                transparentRatio = 0.90f,
                boundsCoverage = 0.30f,
                occupancy = 0.05f,
                aspectFill = 0.10f,
            ),
        )

        assertEquals(LibraryIconPresentationMode.Subject, decision.mode)
        assertEquals(0.90f, decision.visualScale, 0.0001f)
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
        assertEquals(0.92f, decision.visualScale, 0.0001f)
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
        assertEquals(0.84f, decision.visualScale, 0.0001f)
    }

    @Test
    fun framedBackplateWinsOverCoverHeuristics() {
        val decision = decideLibraryIconPresentation(
            input(
                transparentRatio = 0f,
                boundsCoverage = 1f,
                occupancy = 1f,
                hasFramedCrop = true,
                hasBackingColor = true,
                highColorDiversity = true,
                sourceAspectRatio = 1f,
            ),
        )

        assertEquals(LibraryIconPresentationMode.Backed, decision.mode)
        assertEquals(0.92f, decision.visualScale, 0.0001f)
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
    fun coverWinsOverBackingEvidenceWithoutFramedSubject() {
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

    @Test
    fun coverOnlyCropsNearMatchingSlotAspect() {
        assertTrue(shouldCropLibraryCover(128, 128, 1f))
        assertTrue(shouldCropLibraryCover(96, 128, 0.75f))
        assertTrue(shouldCropLibraryCover(95, 100, 1f))
        assertTrue(shouldCropLibraryCover(100, 95, 1f))
    }

    @Test
    fun fullBleedPosterPreservesTextEdgesOutsideSquareCenterCrop() {
        // A 3:4 poster in a square slot would lose 25% of its original height.
        assertEquals(false, shouldCropLibraryCover(96, 128, 1f))
        assertEquals(false, shouldCropLibraryCover(128, 128, 0.75f))
        assertEquals(false, shouldCropLibraryCover(224, 64, 1f))
        assertEquals(false, shouldCropLibraryCover(64, 224, 1f))
    }

    @Test
    fun coverCropPolicyRejectsInvalidDimensionsAndSlotRatios() {
        assertEquals(false, shouldCropLibraryCover(0, 128, 1f))
        assertEquals(false, shouldCropLibraryCover(128, 0, 1f))
        assertEquals(false, shouldCropLibraryCover(128, 128, 0f))
        assertEquals(false, shouldCropLibraryCover(128, 128, Float.NaN))
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
