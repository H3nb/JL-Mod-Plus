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

package io.github.h3nb.jlmodplus.input

/**
 * Boundary used by Canvas touch dispatch for the optional virtual joystick/cursor arbitration.
 * Returning true means the controller owns that contact; false preserves the existing MIDP
 * pointer path. Coordinates are already in guest space.
 */
interface ControllerPointerConsumer {
    fun onPhysicalPointerPressed(pointerId: Int, x: Int, y: Int): Boolean

    fun onPhysicalPointerDragged(pointerId: Int, x: Int, y: Int): Boolean

    fun onPhysicalPointerReleased(pointerId: Int, x: Int, y: Int): Boolean

    fun onPhysicalPointerCancelled()
}
