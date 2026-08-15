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
    fun transparentSparseArtworkUsesSubjectPresentation() {
        val decision = decideLibraryIconPresentation(
            input(
                transparentRatio = 0.42f,
                boundsCoverage = 0.56f,
                occupancy = 0.64f,
            ),
        )

        assertEquals(LibraryIconPresentationMode.Subject, decision.mode)
        assertTrue(decision.visualScale in 0.80f..0.90f)
    }

    @Test
    fun sparseOrElongatedSubjectNeverInflatesToSlotEdges() {
        val sparse = decideLibraryIconPresentation(
            input(
                transparentRatio = 0.60f,
                boundsCoverage = 0.48f,
                occupancy = 0.18f,
                aspectFill = 0.92f,
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

        assertEquals(LibraryIconPresentationMode.Subject, sparse.mode)
        assertEquals(LibraryIconPresentationMode.Subject, elongated.mode)
        assertTrue(sparse.visualScale in 0.80f..0.90f)
        assertTrue(elongated.visualScale in 0.80f..0.90f)
    }

    @Test
    fun framedArtworkKeepsBadgeSizedSubjectPresentation() {
        val decision = decideLibraryIconPresentation(
            input(
                transparentRatio = 0f,
                boundsCoverage = 1f,
                occupancy = 1f,
                hasFramedCrop = true,
            ),
        )

        assertEquals(LibraryIconPresentationMode.Subject, decision.mode)
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
        highColorDiversity: Boolean = false,
        sourceAspectRatio: Float = 1f,
    ) = LibraryIconPresentationInput(
        transparentRatio = transparentRatio,
        boundsCoverage = boundsCoverage,
        occupancy = occupancy,
        aspectFill = aspectFill,
        hasFramedCrop = hasFramedCrop,
        highColorDiversity = highColorDiversity,
        sourceAspectRatio = sourceAspectRatio,
    )
}
