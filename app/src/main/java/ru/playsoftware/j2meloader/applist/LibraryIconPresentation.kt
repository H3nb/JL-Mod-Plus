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

import kotlin.math.sqrt

/**
 * Small deterministic decision layer for square library icon presentation.
 *
 * Bitmap inspection stays in [LibraryComposeBridge.kt]; this type only turns already-computed
 * evidence into a conservative render mode. Keeping the decision free of Android/Compose types
 * makes threshold behavior cheap to verify without introducing another image framework.
 */
internal enum class LibraryIconPresentationMode {
    Subject,
    Cover,
    SafeFit,
    Fallback,
}

internal data class LibraryIconPresentationInput(
    val transparentRatio: Float,
    val boundsCoverage: Float,
    val occupancy: Float,
    val aspectFill: Float,
    val hasFramedCrop: Boolean,
    val highColorDiversity: Boolean,
    val sourceAspectRatio: Float,
)

internal data class LibraryIconPresentationDecision(
    val mode: LibraryIconPresentationMode,
    val visualScale: Float,
)

internal fun decideLibraryIconPresentation(
    input: LibraryIconPresentationInput,
): LibraryIconPresentationDecision {
    val transparentSubject =
        input.transparentRatio >= LIBRARY_PRESENTATION_FOREGROUND_MIN_TRANSPARENT_RATIO &&
            (input.boundsCoverage < LIBRARY_PRESENTATION_FOREGROUND_BOUNDS_COVERAGE ||
                input.occupancy < LIBRARY_PRESENTATION_FOREGROUND_OCCUPANCY)

    if (transparentSubject) {
        // After alpha trimming, ContentScale.Fit still makes narrow/tall subjects look much
        // smaller than a similarly-sized round or square subject. Normalize against estimated
        // visible area in the enclosing square instead of shrinking elongated artwork further.
        val visibleArea = (input.occupancy * input.aspectFill)
            .coerceAtLeast(LIBRARY_PRESENTATION_SUBJECT_MIN_VISIBLE_AREA)
        val visualScale = sqrt(
            LIBRARY_PRESENTATION_SUBJECT_TARGET_VISIBLE_AREA / visibleArea,
        ).coerceIn(
            LIBRARY_PRESENTATION_SUBJECT_MIN_SCALE,
            LIBRARY_PRESENTATION_SUBJECT_MAX_SCALE,
        )
        return LibraryIconPresentationDecision(
            mode = LibraryIconPresentationMode.Subject,
            visualScale = visualScale,
        )
    }

    if (input.hasFramedCrop) {
        return LibraryIconPresentationDecision(
            mode = LibraryIconPresentationMode.Subject,
            visualScale = LIBRARY_PRESENTATION_FRAMED_SUBJECT_SCALE,
        )
    }

    val highConfidenceCover =
        input.transparentRatio <= LIBRARY_PRESENTATION_COVER_MAX_TRANSPARENT_RATIO &&
            input.boundsCoverage >= LIBRARY_PRESENTATION_COVER_MIN_BOUNDS_COVERAGE &&
            input.occupancy >= LIBRARY_PRESENTATION_COVER_MIN_OCCUPANCY &&
            input.highColorDiversity &&
            input.sourceAspectRatio in
                LIBRARY_PRESENTATION_COVER_MIN_ASPECT..LIBRARY_PRESENTATION_COVER_MAX_ASPECT

    return LibraryIconPresentationDecision(
        mode = if (highConfidenceCover) {
            LibraryIconPresentationMode.Cover
        } else {
            LibraryIconPresentationMode.SafeFit
        },
        visualScale = 1f,
    )
}

private const val LIBRARY_PRESENTATION_FOREGROUND_MIN_TRANSPARENT_RATIO = 0.06f
private const val LIBRARY_PRESENTATION_FOREGROUND_BOUNDS_COVERAGE = 0.88f
private const val LIBRARY_PRESENTATION_FOREGROUND_OCCUPANCY = 0.80f

private const val LIBRARY_PRESENTATION_SUBJECT_TARGET_VISIBLE_AREA = 0.52f
private const val LIBRARY_PRESENTATION_SUBJECT_MIN_VISIBLE_AREA = 0.01f
private const val LIBRARY_PRESENTATION_SUBJECT_MIN_SCALE = 0.66f
private const val LIBRARY_PRESENTATION_SUBJECT_MAX_SCALE = 0.94f
private const val LIBRARY_PRESENTATION_FRAMED_SUBJECT_SCALE = 0.92f

private const val LIBRARY_PRESENTATION_COVER_MAX_TRANSPARENT_RATIO = 0.025f
private const val LIBRARY_PRESENTATION_COVER_MIN_BOUNDS_COVERAGE = 0.985f
private const val LIBRARY_PRESENTATION_COVER_MIN_OCCUPANCY = 0.94f
private const val LIBRARY_PRESENTATION_COVER_MIN_ASPECT = 0.62f
private const val LIBRARY_PRESENTATION_COVER_MAX_ASPECT = 1.62f
