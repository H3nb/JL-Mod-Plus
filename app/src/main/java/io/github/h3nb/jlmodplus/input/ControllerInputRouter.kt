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

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import javax.microedition.lcdui.Canvas
import javax.microedition.lcdui.Displayable
import javax.microedition.lcdui.event.PointerEvent
import io.github.h3nb.jlmodplus.config.ProfileModel
import io.github.h3nb.jlmodplus.R
import java.util.EnumMap
import kotlin.math.abs

/**
 * The host boundary for controller input.  The router never calls guest callbacks directly:
 * Canvas producers enter its ledger, while host/screen actions are sent through this interface.
 */
interface ControllerHostSink {
    fun currentCanvas(): Canvas?
    fun currentDisplayable(): Displayable?
    /** Optional host-only target for Activities whose current surface is not a MIDP Displayable. */
    fun currentControllerTarget(): ControllerHostTarget? = null
    /** Delivers an Android-host command without translating through MIDP/Canvas key codes. */
    fun onHostCommand(command: HostCommand, pressed: Boolean): Boolean = false
    fun onControllerInputAccepted()
    fun onControllerNotice(message: String)

    /** Notifies app-owned configuration surfaces when a controller source appears or disappears. */
    fun onControllerAvailabilityChanged(available: Boolean) = Unit

    /** True while an app-owned runtime modal owns controller focus. */
    fun isControllerModalActive(): Boolean = false

    /** Motion is consumed by a modal by default; no guest analog state may leak through it. */
    fun onControllerModalMotion(event: MotionEvent): Boolean = false
}

/**
 * Android controller input adapter.
 *
 * It is deliberately a single owner for gamepad key and motion events.  Each DOWN captures a
 * source/session/target and each UP uses that capture; no UP re-resolves the current profile.
 * A controller that appears while another one is active must first report a neutral state, so a
 * noisy reconnect cannot take over a running game.  All methods are called from the Android main
 * thread, with the device listener also marshalled to that thread.
 */
class ControllerInputRouter(
    context: Context,
    private val host: ControllerHostSink,
    profile: ProfileModel?,
) : InputManager.InputDeviceListener, ControllerPointerConsumer {
    private enum class LifecycleState { INACTIVE, WAIT_NEUTRAL, ACTIVE }

    private data class DirectionalSource(
        val channel: String,
        val movementGroup: String,
        val canvas: Canvas?,
        val deviceId: Int,
        val generation: Long,
        val sessionId: Long,
        val hostTarget: ControllerHostTarget?,
        var requestedControls: Set<String> = emptySet(),
    )

    private data class BinarySource(
        val channel: String,
        val control: String,
        val code: Int,
        val canvas: Canvas?,
        val deviceId: Int,
        val generation: Long,
        val sessionId: Long,
        val hostTarget: ControllerHostTarget?,
    )

    private data class PointerClickKey(
        val deviceId: Int,
        val keyCode: Int,
    )

    private data class StickRuntime(
        var directionState: StickProcessor.DirectionState = StickProcessor.DirectionState(),
    )

    private val appContext = context.applicationContext
    private val inputManager = context.getSystemService(Context.INPUT_SERVICE) as? InputManager
    private val inputHandler = Handler(Looper.getMainLooper())
    private val hostInputRouter = HostInputRouter(host)
    private val capabilityCache = ControllerCapabilityCache()
    private val config: ControllerConfig = resolveProfile(profile)
    private val lifecycleGate = ControllerLifecycleGate()
    private var lifecycleState = LifecycleState.INACTIVE
    private var activeDeviceId: Int? = null
    private var waitingDeviceId: Int? = null
    private var sessionId = 0L
    private var hostModalActive = false
    private var lastTarget: TargetSnapshot? = null
    private val directional = LinkedHashMap<String, DirectionalSource>()
    private val binary = LinkedHashMap<String, BinarySource>()
    private val triggerStates = EnumMap<TriggerSide, StickProcessor.TriggerState>(TriggerSide::class.java)
    private val stickStates = EnumMap<StickId, StickRuntime>(StickId::class.java)
    private var pointerCanvas: Canvas? = null
    private var pointerViewport: GuestViewport? = null
    private val pointerClickOwners = LinkedHashSet<PointerSourceToken>()
    private val pointerClickKeys = LinkedHashSet<PointerClickKey>()
    private val pointerPhysicalTokens = LinkedHashMap<Int, PointerSourceToken>()
    private var pointerJoystickToken: PointerSourceToken? = null
    // The guest MIDP compatibility boundary deliberately uses its single-pointer channel 0.
    // PointerSourceToken remains the source/target identity; it must not be confused with an
    // Android pointer id or used as a second guest pointer channel.
    private val pointerLease = PointerLeaseController(guestPointerId = 0)
    private var activeCursorClickToken: PointerSourceToken? = null
    private val cursorController = ProportionalCursorController(
        pointerLease,
        CursorSettings(speedPerShortestSidePerSecond = config.pointer.speed.toFloat()),
    )
    private val joystickController = VirtualTouchJoystickController(
        pointerLease,
        VirtualJoystickSettings(
            centerXFraction = config.pointer.centerX.toFloat(),
            centerYFraction = config.pointer.centerY.toFloat(),
            radiusFractionOfShortestSide = config.pointer.radius.toFloat(),
            mode = config.pointer.joystickMode,
        ),
    )
    private val hostLedger = ControllerHostOwnershipLedger(
        sink = ControllerHostOutputSink { event ->
            when (val output = event.output) {
                is ControllerHostOutput.Command -> when (event.type) {
                    ControllerHostEventType.DOWN -> host.onHostCommand(output.command, true)
                    ControllerHostEventType.UP -> host.onHostCommand(output.command, false)
                    ControllerHostEventType.REPEAT -> host.onHostCommand(output.command, true)
                }
            }
        },
        repeatScheduler = AndroidRepeatScheduler(inputHandler),
    )

    init {
        triggerStates[TriggerSide.LEFT] = StickProcessor.TriggerState()
        triggerStates[TriggerSide.RIGHT] = StickProcessor.TriggerState()
        stickStates[StickId.LEFT] = StickRuntime()
        stickStates[StickId.RIGHT] = StickRuntime()
        inputManager?.registerInputDeviceListener(this, inputHandler)
        notifyControllerAvailability()
        if (config.notice != null) host.onControllerNotice(
            appContext.getString(R.string.config_gamepad_unsupported_summary),
        )
    }


    /** Applies an explicit host-modal ownership transition instead of waiting for another event. */
    fun onHostModalChanged(active: Boolean) {
        syncHostModalBoundary(active)
    }

    /** Called before a Displayable/surface target is replaced. */
    fun onTargetChanged() {
        beginBoundary(waitForNeutral = true)
    }

    /** Focus loss, pause, and destruction use an inactive barrier. */
    fun clear() {
        hostInputRouter.clear()
        detachPointerConsumer()
        releaseAll()
        lifecycleGate.clear()
        lifecycleState = LifecycleState.INACTIVE
        activeDeviceId = null
        waitingDeviceId = null
        lastTarget = null
        hostModalActive = false
    }

    fun close() {
        clear()
        inputManager?.unregisterInputDeviceListener(this)
    }

    /** Handles host-owned controller buttons, then the configured virtual pointer click. */
    fun onKeyEvent(event: KeyEvent): Boolean {
        syncHostModalBoundary()
        val hostHandled = hostInputRouter.onKeyEvent(event)
        syncHostModalBoundary()
        if (hostHandled) {
            return true
        }
        if (!isGamepadEvent(event) || !config.enabled) return false
        if (config.pointer.mode != PointerMode.CURSOR ||
            config.pointer.clickAction != PointerAction.CLICK ||
            host.currentCanvas() == null
        ) return false
        if (HostCommand.fromAndroidKeyCode(event.keyCode) != HostCommand.Activate) return false

        val key = PointerClickKey(event.deviceId, event.keyCode)
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount != 0) return true
                if (!pointerClickKeys.add(key)) return true
                handlePointerClickBinding(
                    event.deviceId,
                    sessionId,
                    pointerClickChannel(key),
                    down = true,
                )
                true
            }
            KeyEvent.ACTION_UP -> {
                if (!pointerClickKeys.remove(key)) return false
                handlePointerClickBinding(
                    event.deviceId,
                    sessionId,
                    pointerClickChannel(key),
                    down = false,
                )
                true
            }
            else -> false
        }
    }


    private fun syncHostModalBoundary(active: Boolean = host.isControllerModalActive()) {
        if (active == hostModalActive) return
        hostModalActive = active
        if (active) {
            host.currentCanvas()?.clearInputState()
            releaseAll()
            resetPointerState()
        } else {
            beginBoundary(waitForNeutral = true)
        }
    }

    /** Handles HAT, stick, and trigger samples. */
    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!isControllerSource(event.source)) return false
        val deviceId = event.deviceId
        val device = InputDevice.getDevice(deviceId)
        if (device == null) {
            if (activeDeviceId == deviceId) clear()
            return true
        }
        if (!isGamepadDevice(device)) return false
        syncHostModalBoundary()
        val neutral = isNeutralMotion(event, device)
        val lifecycleDecision = lifecycleGate.offerMotion(deviceId, neutral)
        applyLifecycleDecision(lifecycleDecision)
        if (lifecycleDecision != ControllerLifecycleDecision.ACTIVE) {
            return true
        }
        if (!ensureActiveDevice(deviceId)) return true
        if (!ensureTarget()) return true

        val modalActive = host.isControllerModalActive()
        // A plain MIDP Screen is not a router-owned analog target. Let its Android/View layer
        // receive generic motion unless an explicit host target or modal owns the controller.
        if (!modalActive && host.currentCanvas() == null && host.currentControllerTarget() == null) {
            return false
        }
        if (!modalActive && !config.enabled && host.currentCanvas() != null) return false
        if (event.actionMasked != MotionEvent.ACTION_MOVE &&
            event.actionMasked != MotionEvent.ACTION_HOVER_MOVE
        ) return true

        var meaningful = false
        for (history in 0 until event.historySize) {
            meaningful = if (modalActive) {
                processHostMotion(event, device, history) || meaningful
            } else {
                processMotion(event, device, history) || meaningful
            }
        }
        meaningful = if (modalActive) {
            processHostMotion(event, device, -1) || meaningful
        } else {
            processMotion(event, device, -1) || meaningful
        }
        if (meaningful) host.onControllerInputAccepted()
        return true
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        capabilityCache.invalidate(deviceId)
        notifyControllerAvailability()
    }

    override fun onInputDeviceChanged(deviceId: Int) {
        capabilityCache.invalidate(deviceId)
        if (activeDeviceId == deviceId) beginBoundary(waitForNeutral = true)
        notifyControllerAvailability()
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        capabilityCache.invalidate(deviceId)
        if (activeDeviceId == deviceId || waitingDeviceId == deviceId) clear()
        notifyControllerAvailability()
    }

    private fun notifyControllerAvailability() {
        val available = InputDevice.getDeviceIds().any { deviceId ->
            InputDevice.getDevice(deviceId)?.let { isGamepadDevice(it) } == true
        }
        host.onControllerAvailabilityChanged(available)
    }

    private fun processMotion(event: MotionEvent, device: InputDevice, history: Int): Boolean {
        var meaningful = false
        meaningful = updateHat(event, device, history) || meaningful
        meaningful = updateStick(event, device, StickId.LEFT, history) || meaningful
        meaningful = updateStick(event, device, StickId.RIGHT, history) || meaningful
        meaningful = updateTrigger(event, device, TriggerSide.LEFT, history) || meaningful
        meaningful = updateTrigger(event, device, TriggerSide.RIGHT, history) || meaningful
        return meaningful
    }

    private fun processHostMotion(
        event: MotionEvent,
        device: InputDevice,
        history: Int,
    ): Boolean {
        var meaningful = false
        meaningful = updateHat(event, device, history, hostOnly = true) || meaningful
        meaningful = updateStick(
            event,
            device,
            StickId.LEFT,
            history,
            hostOnly = true,
        ) || meaningful
        return meaningful
    }

    private fun updateHat(
        event: MotionEvent,
        device: InputDevice,
        history: Int,
        hostOnly: Boolean = false,
    ): Boolean {
        val x = axisValue(event, MotionEvent.AXIS_HAT_X, history)
        val y = axisValue(event, MotionEvent.AXIS_HAT_Y, history)
        val controls = LinkedHashSet<String>()
        if (y < -HAT_THRESHOLD) controls += CONTROL_DPAD_UP
        if (y > HAT_THRESHOLD) controls += CONTROL_DPAD_DOWN
        if (x < -HAT_THRESHOLD) controls += CONTROL_DPAD_LEFT
        if (x > HAT_THRESHOLD) controls += CONTROL_DPAD_RIGHT
        val present = findMotionRange(device, event.source, MotionEvent.AXIS_HAT_X) != null ||
            findMotionRange(device, event.source, MotionEvent.AXIS_HAT_Y) != null
        if (!present) return false
        updateDirectional("hat", controls, hostOnly = hostOnly)
        return controls.isNotEmpty()
    }

    private fun updateStick(
        event: MotionEvent,
        device: InputDevice,
        stick: StickId,
        history: Int,
        hostOnly: Boolean = false,
    ): Boolean {
        val settings = if (hostOnly) {
            HOST_STICK_SETTINGS
        } else if (stick == StickId.LEFT) {
            config.leftStick
        } else {
            config.rightStick
        }
        val guestCanvas = if (hostOnly) null else host.currentCanvas()
        val axesPair = stickAxes(device, event.source, stick) ?: return false
        val rawX = axisValue(event, axesPair.first, history)
        val rawY = axisValue(event, axesPair.second, history)
        val xRange = motionRange(device, event.source, axesPair.first)
        val yRange = motionRange(device, event.source, axesPair.second)
        val calibration = config.calibrations[capabilityCache.signature(device)]
        val xCalibration = calibration?.channels[if (stick == StickId.LEFT) {
            CalibrationChannel.LEFT_X
        } else {
            CalibrationChannel.RIGHT_X
        }]
        val yCalibration = calibration?.channels[if (stick == StickId.LEFT) {
            CalibrationChannel.LEFT_Y
        } else {
            CalibrationChannel.RIGHT_Y
        }]
        val swapped = settings.swapAxes
        val processorConfig = StickProcessor.StickConfig(
            innerDeadzone = settings.innerDeadzone.toFloat(),
            outerClamp = settings.outerSaturation.toFloat(),
            responseExponent = settings.responseExponent.toFloat(),
            invertX = if (swapped) settings.invertY else settings.invertX,
            invertY = if (swapped) settings.invertX else settings.invertY,
        )
        val processed = if (swapped) {
            StickProcessor.processCalibratedStick(
                rawX = rawY,
                rawY = rawX,
                xCalibration = yCalibration,
                yCalibration = xCalibration,
                xRange = yRange,
                yRange = xRange,
                config = processorConfig,
            )
        } else {
            StickProcessor.processCalibratedStick(
                rawX = rawX,
                rawY = rawY,
                xCalibration = xCalibration,
                yCalibration = yCalibration,
                xRange = xRange,
                yRange = yRange,
                config = processorConfig,
            )
        }
        // Pointer mode is a guest/runtime concern. A stick must never lose its ability to
        // navigate JL-Mod Plus simply because this MIDlet profile assigns that stick to a pointer.
        if (guestCanvas != null &&
            config.pointer.mode != PointerMode.OFF &&
            config.pointer.sourceStick == stick
        ) {
            // A pointer-configured stick is a continuous producer. It must not also become a
            // digital movement source, otherwise one physical stick can own two unrelated
            // guest outputs during the same sample.
            updateDirectional("stick:${stick.name.lowercase()}", emptySet(), hostOnly = hostOnly)
            val canvas = pointerCanvas ?: guestCanvas
            attachPointerConsumer(canvas)
            val now = eventTime(event, history)
            when (config.pointer.mode) {
                PointerMode.CURSOR -> {
                    cursorController.onResumeIfUnset(now)
                    applyPointerActions(cursorController.tick(now, processed.x, processed.y))
                    return processed.magnitude > 0.0f
                }
                PointerMode.TOUCH_JOYSTICK -> {
                    val viewport = pointerViewport ?: return false
                    val pressRadius = settings.pressRadius.toFloat().coerceIn(0.0f, 1.0f)
                    val releaseRadius = settings.releaseRadius.toFloat().coerceIn(0.0f, pressRadius)
                    val activeToken = pointerJoystickToken
                    if (activeToken == null) {
                        if (processed.radialMagnitude < pressRadius) return false
                        // A controller can be attached after a physical touch already started.
                        // The Canvas callback has not had a chance to register that contact in
                        // the lease, so consult the authoritative PointerEvent state as well.
                        if (PointerEvent.hasActivePointer(canvas)) return false
                        val token = PointerSourceToken(
                            kind = PointerSourceKind.VIRTUAL,
                            contactId = pointerContactId(
                                sessionId,
                                "gamepadJoystick:${stick.name.lowercase()}",
                            ),
                            targetId = pointerTargetId(canvas),
                            generation = canvas.inputGeneration(),
                        )
                        val press = joystickController.begin(token, viewport)
                        if (press.isEmpty()) return false
                        pointerJoystickToken = token
                        applyPointerActions(press)
                        applyPointerActions(joystickController.moveVector(token, processed.x, processed.y))
                        return true
                    }
                    if (processed.radialMagnitude <= releaseRadius) {
                        applyPointerActions(joystickController.end(activeToken))
                        pointerJoystickToken = null
                        return true
                    }
                    applyPointerActions(joystickController.moveVector(activeToken, processed.x, processed.y))
                    return processed.magnitude > 0.0f
                }
                PointerMode.OFF -> return false
            }
        }
        // Stick assignment is likewise guest-only. Host surfaces always retain basic movement
        // navigation even if the MIDlet itself marks this stick unassigned.
        if (guestCanvas != null && settings.mode != StickMode.DIRECTIONS) {
            updateDirectional("stick:${stick.name.lowercase()}", emptySet(), hostOnly = hostOnly)
            return processed.magnitude >= settings.pressRadius.toFloat()
        }
        val hostNavigation = guestCanvas == null
        val directionConfig = StickProcessor.DirectionConfig(
            mode = if (hostNavigation) {
                StickProcessor.DirectionMode.FOUR_WAY
            } else {
                when (settings.directionMode) {
                    DirectionMode.FOUR -> StickProcessor.DirectionMode.FOUR_WAY
                    else -> StickProcessor.DirectionMode.EIGHT_WAY
                }
            },
            diagonalOutput = if (!hostNavigation &&
                settings.directionMode == DirectionMode.DIAGONAL_NUMBER
            ) {
                StickProcessor.DiagonalOutputMode.NUMBER_KEYS
            } else {
                StickProcessor.DiagonalOutputMode.CARDINALS
            },
            pressRadius = settings.pressRadius.toFloat().coerceIn(0.0f, 1.0f),
            releaseRadius = settings.releaseRadius.toFloat().coerceIn(0.0f, 1.0f),
            angularHysteresisDegrees = settings.angularHysteresisDegrees.toFloat()
                .coerceIn(0.0f, 180.0f),
        )
        val runtime = stickStates.getValue(stick)
        val result = StickProcessor.resolveDirection(processed, runtime.directionState, directionConfig)
        runtime.directionState = result.state
        val controls = result.keys.mapTo(LinkedHashSet(), ::directionControl)
        updateDirectional("stick:${stick.name.lowercase()}", controls, hostOnly = hostOnly)
        return controls.isNotEmpty()
    }

    private fun updateTrigger(
        event: MotionEvent,
        device: InputDevice,
        side: TriggerSide,
        history: Int,
    ): Boolean {
        val axis = triggerAxis(device, event.source, side) ?: return false
        if (!config.triggers.enabled) {
            val previous = triggerStates.getValue(side)
            if (previous.pressed) releaseBinary("trigger:${side.name.lowercase()}")
            triggerStates[side] = StickProcessor.TriggerState()
            return false
        }
        val raw = axisValue(event, axis, history)
        val range = motionRange(device, event.source, axis, trigger = true)
            ?: StickProcessor.MotionRangeLike(0.0f, 1.0f)
        val calibration = config.calibrations[capabilityCache.signature(device)]
        val calibrationChannel = if (side == TriggerSide.LEFT) {
            CalibrationChannel.LEFT_TRIGGER
        } else {
            CalibrationChannel.RIGHT_TRIGGER
        }
        val normalized = calibration?.channels[calibrationChannel]?.let {
            StickProcessor.normalizeTrigger(raw, it.range)
        } ?: StickProcessor.normalizeTrigger(raw, range)
        val thresholds = StickProcessor.TriggerThresholds(
            press = config.triggers.pressThreshold.toFloat(),
            release = config.triggers.releaseThreshold.toFloat(),
        )
        val previous = triggerStates.getValue(side)
        val next = StickProcessor.updateTrigger(normalized, previous, thresholds)
        triggerStates[side] = next
        val control = if (side == TriggerSide.LEFT) CONTROL_BUTTON_L2 else CONTROL_BUTTON_R2
        val channel = "trigger:${side.name.lowercase()}"
        if (next.pressed && !previous.pressed) {
            captureBinary(
                channel = channel,
                control = control,
                code = if (side == TriggerSide.LEFT) Canvas.KEY_STAR else Canvas.KEY_POUND,
            )
        } else if (!next.pressed && previous.pressed) {
            releaseBinary(channel)
        }
        return next.pressed
    }

    private fun eventTime(event: MotionEvent, history: Int): Long =
        if (history < 0) event.eventTime else event.getHistoricalEventTime(history)

    private fun captureBinary(channel: String, control: String, code: Int) {
        if (binary.containsKey(channel)) return
        val canvas = host.currentCanvas()
        val captured = BinarySource(
            channel = channel,
            control = control,
            code = code,
            canvas = canvas,
            deviceId = activeDeviceId ?: -1,
            generation = canvas?.inputGeneration() ?: 0L,
            sessionId = sessionId,
            hostTarget = currentHostTarget(),
        )
        binary[channel] = captured
        captured.canvas?.inputPressed(
            CONTROLLER_DEVICE_PREFIX + captured.deviceId,
            captured.sessionId,
            CONTROLLER_KIND,
            captured.channel,
            captured.code,
        )
    }

    private fun releaseBinary(channel: String) {
        val captured = binary.remove(channel) ?: return
        captured.canvas?.inputReleased(
            CONTROLLER_DEVICE_PREFIX + captured.deviceId,
            captured.sessionId,
            CONTROLLER_KIND,
            captured.channel,
        )
    }

    private fun updateDirectional(
        channel: String,
        controls: Set<String>,
        movementGroup: String = if (channel == "stick:right") RIGHT_MOVEMENT_GROUP
        else LEFT_MOVEMENT_GROUP,
        hostOnly: Boolean = false,
    ) {
        var source = directional[channel]
        if (source == null) {
            if (controls.isEmpty()) return
            val canvas = if (hostOnly) null else host.currentCanvas()
            source = DirectionalSource(
                channel = channel,
                movementGroup = movementGroup,
                canvas = canvas,
                deviceId = activeDeviceId ?: -1,
                generation = canvas?.inputGeneration() ?: 0L,
                sessionId = sessionId,
                hostTarget = currentHostTarget(),
            )
            directional[channel] = source
        }
        source.requestedControls = controls.toSet()
        rebuildDirectional()
        if (controls.isEmpty()) directional.remove(channel)
    }

    /** Reconciles the entire controller movement group, including opposite directions. */
    private fun rebuildDirectional() {
        val neutralizedByGroup = LinkedHashMap<String, Set<String>>()
        directional.values.groupBy { it.movementGroup }.forEach { (group, sources) ->
            val requested = sources.flatMapTo(LinkedHashSet()) { it.requestedControls }
            val neutralized = LinkedHashSet<String>()
            if (CONTROL_DPAD_UP in requested && CONTROL_DPAD_DOWN in requested) {
                neutralized += CONTROL_DPAD_UP
                neutralized += CONTROL_DPAD_DOWN
            }
            if (CONTROL_DPAD_LEFT in requested && CONTROL_DPAD_RIGHT in requested) {
                neutralized += CONTROL_DPAD_LEFT
                neutralized += CONTROL_DPAD_RIGHT
            }
            neutralizedByGroup[group] = neutralized
        }
        for (source in directional.values.sortedBy { it.channel }) {
            val effectiveControls = source.requestedControls -
                (neutralizedByGroup[source.movementGroup] ?: emptySet())
            if (source.canvas != null) {
                // HAT/stick quantization is an analog adapter. Its output is canonical MIDP
                // input, while digital controller KeyEvents still travel through KeyMapper.
                val nextGuestCodes = effectiveControls.mapNotNullTo(LinkedHashSet(), ::guestCodeForDirection)
                source.canvas.inputUpdated(
                    CONTROLLER_DEVICE_PREFIX + source.deviceId,
                    source.sessionId,
                    CONTROLLER_KIND,
                    source.channel,
                    *nextGuestCodes.toIntArray(),
                )
            } else {
                val target = source.hostTarget ?: currentHostTarget() ?: continue
                val outputs = effectiveControls.mapNotNullTo(ArrayList(), ::hostCommandForDirection)
                    .map { ControllerHostOutput.Command(it) }
                hostLedger.update(
                    sourceToken(source.deviceId, source.sessionId, source.channel + ":host"),
                    target,
                    outputs,
                    RepeatSpec.Disabled,
                )
            }
        }
    }

    private fun handlePointerClickBinding(
        deviceId: Int,
        sourceSession: Long,
        channel: String,
        down: Boolean,
    ) {
        if (config.pointer.mode != PointerMode.CURSOR ||
            config.pointer.clickAction != PointerAction.CLICK
        ) return
        val canvas = pointerCanvas ?: host.currentCanvas() ?: return
        configurePointerViewport(canvas)
        val targetId = pointerTargetId(canvas)
        val token = PointerSourceToken(
            kind = PointerSourceKind.VIRTUAL,
            contactId = pointerContactId(sourceSession, channel),
            targetId = targetId,
            generation = canvas.inputGeneration(),
        )
        if (down) {
            if (!pointerClickOwners.add(token) || pointerClickOwners.size != 1) return
            if (PointerEvent.hasActivePointer(canvas)) {
                pointerClickOwners.remove(token)
                return
            }
            val actions = cursorController.beginClick(token)
            if (actions.isEmpty()) pointerClickOwners.remove(token)
            else activeCursorClickToken = token
        } else if (pointerClickOwners.remove(token) && pointerClickOwners.isEmpty()) {
            val active = activeCursorClickToken
            activeCursorClickToken = null
            if (active != null) applyPointerActions(cursorController.endClick(active))
        }
    }

    private fun applyPointerActions(actions: List<PointerLeaseAction>) {
        val canvas = pointerCanvas ?: return
        val targetId = pointerTargetId(canvas)
        for (action in actions) {
            if (action.token.targetId != targetId) continue
            when (action) {
                is PointerLeaseAction.Press -> PointerEvent.sendPressed(
                    canvas, pointerLease.guestPointerId(), action.x, action.y,
                )
                is PointerLeaseAction.Drag -> PointerEvent.sendDragged(
                    canvas, pointerLease.guestPointerId(), action.x, action.y,
                )
                is PointerLeaseAction.Release -> PointerEvent.sendReleased(
                    canvas, pointerLease.guestPointerId(), action.x, action.y,
                )
            }
        }
        updatePointerOverlay()
    }

    private fun applyVirtualPointerActions(actions: List<PointerLeaseAction>) {
        applyPointerActions(actions.filter { it.token.kind == PointerSourceKind.VIRTUAL })
    }

    private fun configurePointerViewport(canvas: Canvas) {
        if (config.pointer.mode == PointerMode.OFF) return
        val width = canvas.width.coerceAtLeast(1)
        val height = canvas.height.coerceAtLeast(1)
        val next = GuestViewport(width, height)
        if (pointerCanvas !== canvas || pointerViewport != next) {
            pointerCanvas = canvas
            pointerViewport = next
            cursorController.setViewport(next)
            cursorController.onResume(SystemClock.elapsedRealtime())
        }
        updatePointerOverlay()
    }

    private fun pointerTargetId(canvas: Canvas): String =
        "canvas@${Integer.toHexString(System.identityHashCode(canvas))}"

    private fun pointerContactId(sourceSession: Long, channel: String): Int =
        31 * sourceSession.hashCode() + channel.hashCode()

    private fun pointerClickChannel(key: PointerClickKey): String =
        "cursor-click:${key.deviceId}:${key.keyCode}"

    private fun resetPointerState() {
        val clickKeys = pointerClickKeys.toList()
        pointerClickKeys.clear()
        for (key in clickKeys) {
            handlePointerClickBinding(
                key.deviceId,
                sessionId,
                pointerClickChannel(key),
                down = false,
            )
        }
        applyPointerActions(cursorController.reset())
        applyPointerActions(joystickController.reset())
        activeCursorClickToken = null
        pointerClickOwners.clear()
        pointerPhysicalTokens.clear()
        pointerJoystickToken = null
        pointerLease.reset(releaseVirtual = false)
        updatePointerOverlay()
    }


    private fun attachPointerConsumer(canvas: Canvas?) {
        if (pointerCanvas !== canvas) {
            detachPointerConsumer()
            pointerCanvas = canvas
            canvas?.setControllerPointerConsumer(this)
        }
        if (canvas != null) configurePointerViewport(canvas)
        updatePointerOverlay()
    }

    private fun detachPointerConsumer() {
        // A target boundary must close ordinary physical MIDP pointers as well as the virtual
        // lease. The Canvas callback may already be detached by the time a later MotionEvent
        // arrives, so cancel while the old target is still known.
        pointerCanvas?.let(PointerEvent::cancel)
        resetPointerState()
        pointerCanvas?.setControllerPointerConsumer(null)
        pointerCanvas = null
        pointerViewport = null
    }

    override fun onPhysicalPointerPressed(pointerId: Int, x: Int, y: Int): Boolean {
        val canvas = pointerCanvas ?: return false
        if (config.pointer.mode == PointerMode.OFF) return false
        configurePointerViewport(canvas)
        val generation = canvas.inputGeneration()
        if (pointerJoystickToken != null) {
            val token = PointerSourceToken(
                PointerSourceKind.PHYSICAL,
                pointerId,
                pointerTargetId(canvas),
                generation,
            )
            applyVirtualPointerActions(joystickController.beginPhysical(token, x, y))
            pointerJoystickToken = null
            pointerPhysicalTokens[pointerId] = token
            updatePointerOverlay()
            return false
        }
        if (config.pointer.mode == PointerMode.TOUCH_JOYSTICK &&
            isInsideJoystickStart(canvas, x, y)
        ) {
            val token = PointerSourceToken(
                PointerSourceKind.VIRTUAL,
                pointerId,
                pointerTargetId(canvas),
                generation,
            )
            val actions = if (config.pointer.joystickMode == VirtualAnalogStickMode.FLOATING) {
                joystickController.begin(
                    token,
                    pointerViewport ?: return false,
                    x.toFloat(),
                    y.toFloat(),
                )
            } else {
                joystickController.begin(token, pointerViewport ?: return false)
            }
            if (actions.isNotEmpty()) {
                pointerJoystickToken = token
                applyPointerActions(actions)
                updatePointerOverlay()
                return true
            }
        }
        val token = PointerSourceToken(
            PointerSourceKind.PHYSICAL,
            pointerId,
            pointerTargetId(canvas),
            generation,
        )
        val actions = if (config.pointer.mode == PointerMode.CURSOR) {
            cursorController.beginPhysical(token, x, y)
        } else {
            joystickController.beginPhysical(token, x, y)
        }
        // Canvas will deliver the physical contact through its established MIDP path. The lease
        // is still updated for arbitration, but only a virtual release from a takeover is emitted
        // here; forwarding the physical PRESS as well would duplicate the guest callback.
        applyVirtualPointerActions(actions)
        pointerPhysicalTokens[pointerId] = token
        // A physical touch remains on the established MIDP pointer path. The return value only
        // claims a touch-origin virtual joystick contact.
        updatePointerOverlay()
        return false
    }

    override fun onPhysicalPointerDragged(pointerId: Int, x: Int, y: Int): Boolean {
        val joystickToken = pointerJoystickToken
        if (joystickToken != null && joystickToken.contactId == pointerId) {
            applyPointerActions(joystickController.move(joystickToken, x.toFloat(), y.toFloat()))
            return true
        }
        val token = pointerPhysicalTokens[pointerId] ?: return false
        if (config.pointer.mode == PointerMode.CURSOR) {
            cursorController.updatePhysical(token, x, y)
        } else {
            joystickController.updatePhysical(token, x, y)
        }
        updatePointerOverlay()
        return false
    }

    override fun onPhysicalPointerReleased(pointerId: Int, x: Int, y: Int): Boolean {
        val joystickToken = pointerJoystickToken
        if (joystickToken != null && joystickToken.contactId == pointerId) {
            applyPointerActions(joystickController.end(joystickToken))
            pointerJoystickToken = null
            updatePointerOverlay()
            return true
        }
        val token = pointerPhysicalTokens.remove(pointerId) ?: return false
        if (config.pointer.mode == PointerMode.CURSOR) {
            cursorController.endPhysical(token, x, y)
        } else {
            joystickController.endPhysical(token, x, y)
        }
        updatePointerOverlay()
        return false
    }

    override fun onPhysicalPointerCancelled() {
        resetPointerState()
    }

    private fun updatePointerOverlay() {
        val canvas = pointerCanvas ?: return
        if (config.pointer.mode != PointerMode.TOUCH_JOYSTICK) {
            canvas.setControllerJoystickOverlay(false, 0f, 0f, 0f, 0f, 0f, false)
            return
        }
        val viewport = pointerViewport
        if (viewport == null) {
            canvas.setControllerJoystickOverlay(false, 0f, 0f, 0f, 0f, 0f, false)
            return
        }
        val visual = joystickController.visualState(viewport)
        canvas.setControllerJoystickOverlay(
            true,
            visual.centerX,
            visual.centerY,
            visual.radius,
            visual.thumbX,
            visual.thumbY,
            visual.active,
        )
    }

    private fun isInsideJoystickStart(canvas: Canvas, x: Int, y: Int): Boolean {
        if (config.pointer.joystickMode == VirtualAnalogStickMode.FLOATING) return true
        val width = canvas.width.coerceAtLeast(1).toFloat()
        val height = canvas.height.coerceAtLeast(1).toFloat()
        val centerX = config.pointer.centerX.toFloat() * width
        val centerY = config.pointer.centerY.toFloat() * height
        val radius = config.pointer.radius.toFloat() * minOf(width, height)
        val dx = x - centerX
        val dy = y - centerY
        return dx * dx + dy * dy <= radius * radius
    }

    private fun ensureActiveDevice(deviceId: Int): Boolean {
        if (activeDeviceId != null && activeDeviceId != deviceId) {
            beginBoundary(waitForNeutral = true, nextDeviceId = deviceId)
            return false
        }
        return lifecycleState == LifecycleState.ACTIVE
    }

    private fun ensureTarget(): Boolean {
        val canvas = host.currentCanvas()
        val target = TargetSnapshot(canvas, host.currentDisplayable(), canvas?.inputGeneration() ?: 0L)
        if (lastTarget != null && lastTarget != target) {
            beginBoundary(waitForNeutral = true, nextDeviceId = activeDeviceId)
            return false
        }
        lastTarget = target
        attachPointerConsumer(canvas)
        return true
    }

    private fun currentHostTarget(): ControllerHostTarget? {
        host.currentControllerTarget()?.let { return it }
        val displayable = host.currentDisplayable() ?: return null
        val canvas = host.currentCanvas()
        val id = "displayable@${Integer.toHexString(System.identityHashCode(displayable))}"
        return ControllerHostTarget(id, canvas?.inputGeneration() ?: sessionId)
    }

    private fun sourceToken(deviceId: Int, sourceSession: Long, channel: String): SourceToken =
        SourceToken(
            deviceId = CONTROLLER_DEVICE_PREFIX + deviceId,
            sessionId = sourceSession,
            kind = CONTROLLER_KIND,
            channel = channel,
        )

    private fun beginBoundary(waitForNeutral: Boolean, nextDeviceId: Int? = activeDeviceId) {
        detachPointerConsumer()
        releaseAll()
        lifecycleGate.beginBoundary(waitForNeutral, nextDeviceId)
        syncLifecycleFields()
        lastTarget = null
        if (waitForNeutral && nextDeviceId != null) {
            activeDeviceId = nextDeviceId
            waitingDeviceId = nextDeviceId
            lifecycleState = LifecycleState.WAIT_NEUTRAL
        } else {
            activeDeviceId = null
            waitingDeviceId = null
            lifecycleState = LifecycleState.INACTIVE
        }
    }

    private fun applyLifecycleDecision(decision: ControllerLifecycleDecision) {
        val previousState = lifecycleState
        val previousDeviceId = activeDeviceId
        syncLifecycleFields()
        if (previousState == LifecycleState.ACTIVE &&
            lifecycleState == LifecycleState.WAIT_NEUTRAL &&
            previousDeviceId != null && previousDeviceId != activeDeviceId
        ) {
            // The gate consumes the first sample from a new device. Before doing so, close every
            // output owned by the old device; its UP events are intentionally ignored while the
            // new device proves neutral.
            detachPointerConsumer()
            releaseAll()
            lastTarget = null
        }
        if (decision == ControllerLifecycleDecision.ACTIVATED) {
            sessionId = nextSession(sessionId)
            lastTarget = null
        }
    }

    private fun syncLifecycleFields() {
        val snapshot = lifecycleGate.snapshot()
        lifecycleState = when (snapshot.state) {
            ControllerLifecycleState.INACTIVE -> LifecycleState.INACTIVE
            ControllerLifecycleState.WAIT_NEUTRAL -> LifecycleState.WAIT_NEUTRAL
            ControllerLifecycleState.ACTIVE -> LifecycleState.ACTIVE
        }
        activeDeviceId = snapshot.activeDeviceId
        waitingDeviceId = snapshot.waitingDeviceId
    }

    private fun releaseAll() {
        // Host outputs have their own typed ledger because non-Canvas Screens cannot receive
        // Canvas.postKey* callbacks. Clear it first; subsequent source UPs are idempotent.
        hostLedger.clear()
        val directionalChannels = directional.keys.toList()
        for (channel in directionalChannels) updateDirectional(channel, emptySet())
        directional.clear()
        val binarySources = binary.values.toList()
        binary.clear()
        for (source in binarySources) {
            source.canvas?.inputReleased(
                CONTROLLER_DEVICE_PREFIX + source.deviceId,
                source.sessionId,
                CONTROLLER_KIND,
                source.channel,
            )
        }
        for (runtime in stickStates.values) runtime.directionState = StickProcessor.DirectionState()
        triggerStates[TriggerSide.LEFT] = StickProcessor.TriggerState()
        triggerStates[TriggerSide.RIGHT] = StickProcessor.TriggerState()
    }

    private fun isNeutralMotion(event: MotionEvent, device: InputDevice): Boolean {
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        if (abs(hatX) > HAT_THRESHOLD || abs(hatY) > HAT_THRESHOLD) return false
        val calibration = config.calibrations[capabilityCache.signature(device)]
        for (stick in StickId.entries) {
            val pair = stickAxes(device, event.source, stick) ?: continue
            val xChannel = if (stick == StickId.LEFT) CalibrationChannel.LEFT_X else CalibrationChannel.RIGHT_X
            val yChannel = if (stick == StickId.LEFT) CalibrationChannel.LEFT_Y else CalibrationChannel.RIGHT_Y
            val x = StickProcessor.normalizeCalibratedAxis(
                event.getAxisValue(pair.first),
                calibration?.channels?.get(xChannel),
                motionRange(device, event.source, pair.first),
            )
            val y = StickProcessor.normalizeCalibratedAxis(
                event.getAxisValue(pair.second),
                calibration?.channels?.get(yChannel),
                motionRange(device, event.source, pair.second),
            )
            if (abs(x) > NEUTRAL_AXIS_THRESHOLD || abs(y) > NEUTRAL_AXIS_THRESHOLD) return false
        }
        for (axis in TRIGGER_AXES) {
            val range = findMotionRange(device, event.source, axis) ?: continue
            if (StickProcessor.normalizeTrigger(event.getAxisValue(axis),
                    StickProcessor.MotionRangeLike(range.min, range.max)) > TRIGGER_NEUTRAL_THRESHOLD
            ) return false
        }
        return true
    }

    private fun axisValue(event: MotionEvent, axis: Int, history: Int): Float =
        if (history < 0) event.getAxisValue(axis)
        else event.getHistoricalAxisValue(axis, history)

    private fun findMotionRange(
        device: InputDevice,
        source: Int,
        axis: Int,
    ): InputDevice.MotionRange? =
        device.getMotionRange(axis, source) ?: device.getMotionRange(axis)

    private fun motionRange(
        device: InputDevice,
        source: Int,
        axis: Int,
        trigger: Boolean = false,
    ): StickProcessor.MotionRangeLike {
        val range = findMotionRange(device, source, axis)
        if (range != null && range.min < range.max && range.min.isFinite() && range.max.isFinite()) {
            return StickProcessor.MotionRangeLike(range.min, range.max)
        }
        return if (trigger) StickProcessor.MotionRangeLike(0.0f, 1.0f)
        else StickProcessor.MotionRangeLike.SIGNED
    }

    private fun stickAxes(
        device: InputDevice,
        source: Int,
        stick: StickId,
    ): Pair<Int, Int>? {
        val axes = if (stick == StickId.LEFT) {
            MotionEvent.AXIS_X to MotionEvent.AXIS_Y
        } else {
            val z = findMotionRange(device, source, MotionEvent.AXIS_Z) != null
            val rz = findMotionRange(device, source, MotionEvent.AXIS_RZ) != null
            if (z && rz) MotionEvent.AXIS_Z to MotionEvent.AXIS_RZ
            else MotionEvent.AXIS_RX to MotionEvent.AXIS_RY
        }
        val hasX = findMotionRange(device, source, axes.first) != null
        val hasY = findMotionRange(device, source, axes.second) != null
        return if (hasX && hasY) axes else null
    }

    private fun triggerAxis(device: InputDevice, source: Int, side: TriggerSide): Int? {
        val preferred = if (side == TriggerSide.LEFT) {
            intArrayOf(MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE)
        } else {
            intArrayOf(MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS)
        }
        return preferred.firstOrNull {
            findMotionRange(device, source, it) != null
        }
    }

    private fun guestCodeForDirection(control: String): Int? = when (control) {
        CONTROL_DPAD_UP -> Canvas.KEY_UP
        CONTROL_DPAD_DOWN -> Canvas.KEY_DOWN
        CONTROL_DPAD_LEFT -> Canvas.KEY_LEFT
        CONTROL_DPAD_RIGHT -> Canvas.KEY_RIGHT
        DIRECTION_NUM1 -> Canvas.KEY_NUM1
        DIRECTION_NUM2 -> Canvas.KEY_NUM2
        DIRECTION_NUM3 -> Canvas.KEY_NUM3
        DIRECTION_NUM4 -> Canvas.KEY_NUM4
        DIRECTION_NUM6 -> Canvas.KEY_NUM6
        DIRECTION_NUM7 -> Canvas.KEY_NUM7
        DIRECTION_NUM8 -> Canvas.KEY_NUM8
        DIRECTION_NUM9 -> Canvas.KEY_NUM9
        else -> null
    }

    private fun hostCommandForDirection(control: String): HostCommand? = when (control) {
        CONTROL_DPAD_UP -> HostCommand.NavigateUp
        CONTROL_DPAD_DOWN -> HostCommand.NavigateDown
        CONTROL_DPAD_LEFT -> HostCommand.NavigateLeft
        CONTROL_DPAD_RIGHT -> HostCommand.NavigateRight
        else -> null
    }

    private fun directionControl(key: StickProcessor.DirectionKey): String = when (key) {
        StickProcessor.DirectionKey.UP -> CONTROL_DPAD_UP
        StickProcessor.DirectionKey.DOWN -> CONTROL_DPAD_DOWN
        StickProcessor.DirectionKey.LEFT -> CONTROL_DPAD_LEFT
        StickProcessor.DirectionKey.RIGHT -> CONTROL_DPAD_RIGHT
        StickProcessor.DirectionKey.NUM1 -> DIRECTION_NUM1
        StickProcessor.DirectionKey.NUM2 -> DIRECTION_NUM2
        StickProcessor.DirectionKey.NUM3 -> DIRECTION_NUM3
        StickProcessor.DirectionKey.NUM4 -> DIRECTION_NUM4
        StickProcessor.DirectionKey.NUM6 -> DIRECTION_NUM6
        StickProcessor.DirectionKey.NUM7 -> DIRECTION_NUM7
        StickProcessor.DirectionKey.NUM8 -> DIRECTION_NUM8
        StickProcessor.DirectionKey.NUM9 -> DIRECTION_NUM9
    }

    private data class TargetSnapshot(
        val canvas: Canvas?,
        val displayable: Displayable?,
        val generation: Long,
    )

    private enum class TriggerSide { LEFT, RIGHT }

    companion object {
        const val CONTROL_DPAD_UP = "dpad_up"
        const val CONTROL_DPAD_DOWN = "dpad_down"
        const val CONTROL_DPAD_LEFT = "dpad_left"
        const val CONTROL_DPAD_RIGHT = "dpad_right"
        const val CONTROL_BUTTON_L2 = "button_l2"
        const val CONTROL_BUTTON_R2 = "button_r2"

        private const val DIRECTION_NUM1 = "__direction_num1"
        private const val DIRECTION_NUM2 = "__direction_num2"
        private const val DIRECTION_NUM3 = "__direction_num3"
        private const val DIRECTION_NUM4 = "__direction_num4"
        private const val DIRECTION_NUM6 = "__direction_num6"
        private const val DIRECTION_NUM7 = "__direction_num7"
        private const val DIRECTION_NUM8 = "__direction_num8"
        private const val DIRECTION_NUM9 = "__direction_num9"
        private const val CONTROLLER_KIND = "controller"
        private const val CONTROLLER_DEVICE_PREFIX = "android:"
        private const val LEFT_MOVEMENT_GROUP = "left-movement"
        private const val RIGHT_MOVEMENT_GROUP = "right-movement"
        private const val HAT_THRESHOLD = 0.5f
        private val HOST_STICK_SETTINGS = StickSettings(directionMode = DirectionMode.FOUR)
        private const val NEUTRAL_AXIS_THRESHOLD = 0.15f
        private const val TRIGGER_NEUTRAL_THRESHOLD = 0.40f
        private val TRIGGER_AXES = intArrayOf(
            MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER,
            MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_GAS,
        )

        @JvmStatic
        fun isControllerSource(source: Int): Boolean =
            source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
                source and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD

        /** True only for an input event backed by a connected gamepad/joystick device. */
        @JvmStatic
        fun isGamepadEvent(event: KeyEvent): Boolean =
            isControllerSource(event.source) &&
                InputDevice.getDevice(event.deviceId)?.let(::isGamepadDevice) == true

        /** True only for a motion event backed by a connected gamepad/joystick device. */
        @JvmStatic
        fun isGamepadMotionEvent(event: MotionEvent): Boolean =
            isControllerSource(event.source) &&
                InputDevice.getDevice(event.deviceId)?.let(::isGamepadDevice) == true

        /** True when an input device should be presented as a gamepad in app-owned UI. */
        @JvmStatic
        fun isGamepadDevice(device: InputDevice): Boolean =
            device.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                device.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK

        /**
         * Stable calibration key: Android device ids are process-local and are deliberately not
         * part of this value. Descriptor plus the complete reported motion capability set keeps a
         * profile attached to a physical controller/capability shape across reconnects.
         */
        @JvmStatic
        fun capabilitySignatureFor(device: InputDevice): String =
            ControllerCapabilityCache.buildSignature(device)


        private fun resolveProfile(profile: ProfileModel?): ControllerConfig {
            if (profile == null) return ControllerConfig.defaultNavigation()
            val controller = profile.controller?.takeIf { it.isJsonObject }?.asJsonObject
            return ControllerConfig.resolve(controller)
        }

        private fun nextSession(current: Long): Long =
            if (current == Long.MAX_VALUE) 1L else current + 1L
    }
}