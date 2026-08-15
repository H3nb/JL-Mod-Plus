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

/**
 * Small deterministic decision layer for square library icon presentation.
 *
 * Bitmap inspection stays in [LibraryComposeBridge.kt]; this type only turns already-computed
 * evidence into a conservative render mode. Keeping the decision free of Android/Compose types
 * makes threshold behavior cheap to verify without introducing another image framework.
 */
internal enum class LibraryIconPresentationMode {
    Subject,
    Backed,
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
    val hasBackingColor: Boolean,
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
    val highConfidenceCover =
        input.transparentRatio <= LIBRARY_PRESENTATION_COVER_MAX_TRANSPARENT_RATIO &&
            input.boundsCoverage >= LIBRARY_PRESENTATION_COVER_MIN_BOUNDS_COVERAGE &&
            input.occupancy >= LIBRARY_PRESENTATION_COVER_MIN_OCCUPANCY &&
            input.highColorDiversity &&
            input.sourceAspectRatio in
                LIBRARY_PRESENTATION_COVER_MIN_ASPECT..LIBRARY_PRESENTATION_COVER_MAX_ASPECT

    if (highConfidenceCover) {
        return LibraryIconPresentationDecision(
            mode = LibraryIconPresentationMode.Cover,
            visualScale = 1f,
        )
    }

    if (input.hasBackingColor || input.hasFramedCrop) {
        return LibraryIconPresentationDecision(
            mode = LibraryIconPresentationMode.Backed,
            visualScale = LIBRARY_PRESENTATION_BACKED_SCALE,
        )
    }

    val transparentSubject =
        input.transparentRatio >= LIBRARY_PRESENTATION_FOREGROUND_MIN_TRANSPARENT_RATIO &&
            (input.boundsCoverage < LIBRARY_PRESENTATION_FOREGROUND_BOUNDS_COVERAGE ||
                input.occupancy < LIBRARY_PRESENTATION_FOREGROUND_OCCUPANCY)

    if (transparentSubject) {
        // Single-object icons should keep breathing room. Elongated artwork gets a modest
        // compensation because ContentScale.Fit otherwise makes it look much smaller, while dense
        // round/square subjects (for example a ball) are deliberately reduced instead of inflated.
        val denseAmount = (
            (input.occupancy - LIBRARY_PRESENTATION_SUBJECT_DENSE_START) /
                LIBRARY_PRESENTATION_SUBJECT_DENSE_RANGE
            ).coerceIn(0f, 1f)
        val elongatedAmount = (
            (LIBRARY_PRESENTATION_SUBJECT_ELONGATED_START - input.aspectFill) /
                LIBRARY_PRESENTATION_SUBJECT_ELONGATED_RANGE
            ).coerceIn(0f, 1f)
        val visualScale = (
            LIBRARY_PRESENTATION_SUBJECT_BASE_SCALE -
                denseAmount * LIBRARY_PRESENTATION_SUBJECT_DENSE_REDUCTION +
                elongatedAmount * LIBRARY_PRESENTATION_SUBJECT_ELONGATED_BONUS
            ).coerceIn(
            LIBRARY_PRESENTATION_SUBJECT_MIN_SCALE,
            LIBRARY_PRESENTATION_SUBJECT_MAX_SCALE,
        )
        return LibraryIconPresentationDecision(
            mode = LibraryIconPresentationMode.Subject,
            visualScale = visualScale,
        )
    }

    return LibraryIconPresentationDecision(
        mode = LibraryIconPresentationMode.SafeFit,
        visualScale = 1f,
    )
}

private const val LIBRARY_PRESENTATION_FOREGROUND_MIN_TRANSPARENT_RATIO = 0.06f
private const val LIBRARY_PRESENTATION_FOREGROUND_BOUNDS_COVERAGE = 0.88f
private const val LIBRARY_PRESENTATION_FOREGROUND_OCCUPANCY = 0.80f

private const val LIBRARY_PRESENTATION_SUBJECT_BASE_SCALE = 0.66f
private const val LIBRARY_PRESENTATION_SUBJECT_DENSE_START = 0.55f
private const val LIBRARY_PRESENTATION_SUBJECT_DENSE_RANGE = 0.45f
private const val LIBRARY_PRESENTATION_SUBJECT_DENSE_REDUCTION = 0.08f
private const val LIBRARY_PRESENTATION_SUBJECT_ELONGATED_START = 0.72f
private const val LIBRARY_PRESENTATION_SUBJECT_ELONGATED_RANGE = 0.62f
private const val LIBRARY_PRESENTATION_SUBJECT_ELONGATED_BONUS = 0.12f
private const val LIBRARY_PRESENTATION_SUBJECT_MIN_SCALE = 0.58f
private const val LIBRARY_PRESENTATION_SUBJECT_MAX_SCALE = 0.78f
private const val LIBRARY_PRESENTATION_BACKED_SCALE = 0.86f

private const val LIBRARY_PRESENTATION_COVER_MAX_TRANSPARENT_RATIO = 0.025f
private const val LIBRARY_PRESENTATION_COVER_MIN_BOUNDS_COVERAGE = 0.985f
private const val LIBRARY_PRESENTATION_COVER_MIN_OCCUPANCY = 0.94f
private const val LIBRARY_PRESENTATION_COVER_MIN_ASPECT = 0.62f
private const val LIBRARY_PRESENTATION_COVER_MAX_ASPECT = 1.62f
