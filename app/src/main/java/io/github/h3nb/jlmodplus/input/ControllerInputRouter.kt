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
import android.util.SparseIntArray
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.google.gson.JsonObject
import javax.microedition.lcdui.Canvas
import javax.microedition.lcdui.Displayable
import javax.microedition.lcdui.event.PointerEvent
import javax.microedition.lcdui.keyboard.KeyMapper
import io.github.h3nb.jlmodplus.config.ProfileModel
import io.github.h3nb.jlmodplus.R
import java.util.EnumMap
import java.util.Locale
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
    fun onHostAction(action: HostAction, pressed: Boolean): Boolean
    fun dispatchGuestKey(keyCode: Int, pressed: Boolean): Boolean
    /** Delivers a ledger repeat without converting it into a second DOWN/UP pair. */
    fun dispatchGuestKeyRepeated(keyCode: Int): Boolean = dispatchGuestKey(keyCode, true)
    /** Host actions normally disable repeat, but custom sinks can preserve its event identity. */
    fun onHostActionRepeated(action: HostAction): Boolean = onHostAction(action, true)
    fun onControllerInputAccepted()
    fun onControllerNotice(message: String)

    /** Notifies app-owned configuration surfaces when a controller source appears or disappears. */
    fun onControllerAvailabilityChanged(available: Boolean) = Unit

    /** True while an app-owned runtime modal owns controller focus. */
    fun isControllerModalActive(): Boolean = false

    /** Gives a runtime modal first refusal before guest Screen/gameplay routing. */
    fun onControllerModalInput(control: String, pressed: Boolean): Boolean = false

    /** Motion is consumed by a modal by default; no guest analog state may leak through it. */
    fun onControllerModalMotion(event: MotionEvent): Boolean = false
}

/** A compact, user-facing snapshot used by the diagnosis surface and tests. */
data class ControllerDiagnosticSnapshot(
    val state: String,
    val deviceId: Int?,
    val deviceName: String?,
    val sessionId: Long,
    val activeDigitalControls: Set<String>,
    val activeAnalogControls: Set<String>,
    val lastAxes: Map<String, Float>,
    val configSource: ResolutionSource,
    val configNotice: String?,
)

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

    private data class PhysicalKey(val deviceId: Int, val keyCode: Int)

    private data class CapturedDigital(
        val physicalKey: PhysicalKey,
        val control: String,
        val binding: Binding?,
        val canvas: Canvas?,
        val deviceId: Int,
        val generation: Long,
        val sessionId: Long,
        val channel: String,
        val directional: Boolean,
        val hostTarget: ControllerHostTarget?,
    )

    private data class DirectionalSource(
        val channel: String,
        val movementGroup: String,
        val canvas: Canvas?,
        val deviceId: Int,
        val generation: Long,
        val sessionId: Long,
        val hostTarget: ControllerHostTarget?,
        var requestedControls: Set<String> = emptySet(),
        val bindings: LinkedHashMap<String, Binding> = LinkedHashMap(),
    )

    private data class BinarySource(
        val channel: String,
        val control: String,
        val binding: Binding,
        val canvas: Canvas?,
        val deviceId: Int,
        val generation: Long,
        val sessionId: Long,
        val hostTarget: ControllerHostTarget?,
    )

    private data class StickRuntime(
        var directionState: StickProcessor.DirectionState = StickProcessor.DirectionState(),
    )

    private val appContext = context.applicationContext
    private val inputManager = context.getSystemService(Context.INPUT_SERVICE) as? InputManager
    private val inputHandler = Handler(Looper.getMainLooper())
    private var config: ControllerConfig = resolveProfile(profile)
    private val lifecycleGate = ControllerLifecycleGate()
    private var lifecycleState = LifecycleState.INACTIVE
    private var activeDeviceId: Int? = null
    private var waitingDeviceId: Int? = null
    private var waitingDigitalKeys = LinkedHashSet<PhysicalKey>()
    private var sessionId = 0L
    private var lastTarget: TargetSnapshot? = null
    private val digital = LinkedHashMap<PhysicalKey, CapturedDigital>()
    private val directional = LinkedHashMap<String, DirectionalSource>()
    private val binary = LinkedHashMap<String, BinarySource>()
    // Modal/keypad consumers must retain ownership through ACTION_UP, even when the DOWN closes
    // the modal before Android delivers the matching release.
    private val modalKeys = LinkedHashSet<PhysicalKey>()
    private val triggerStates = EnumMap<TriggerSide, StickProcessor.TriggerState>(TriggerSide::class.java)
    private val stickStates = EnumMap<StickId, StickRuntime>(StickId::class.java)
    private val axes = LinkedHashMap<String, Float>()
    private var pointerCanvas: Canvas? = null
    private var pointerViewport: GuestViewport? = null
    private val pointerClickOwners = LinkedHashSet<PointerSourceToken>()
    private val directionalPointerOwners = LinkedHashMap<SourceToken, Set<PointerAction>>()
    private val pointerPhysicalTokens = LinkedHashMap<Int, PointerSourceToken>()
    private var pointerJoystickToken: PointerSourceToken? = null
    // The guest MIDP compatibility boundary deliberately uses its single-pointer channel 0.
    // PointerSourceToken remains the source/target identity; it must not be confused with an
    // Android pointer id or used as a second guest pointer channel.
    private val pointerLease = PointerLeaseController(guestPointerId = 0)
    private var activeCursorClickToken: PointerSourceToken? = null
    private var cursorController = ProportionalCursorController(
        pointerLease,
        CursorSettings(speedPerShortestSidePerSecond = config.pointer.speed.toFloat()),
    )
    private var joystickController = VirtualTouchJoystickController(
        pointerLease,
        VirtualJoystickSettings(
            centerXFraction = config.pointer.centerX.toFloat(),
            centerYFraction = config.pointer.centerY.toFloat(),
            radiusFractionOfShortestSide = config.pointer.radius.toFloat(),
        ),
    )
    private val hostLedger = ControllerHostOwnershipLedger(
        sink = ControllerHostOutputSink { event ->
            when (val output = event.output) {
                is ControllerHostOutput.GuestKey -> when (event.type) {
                    ControllerHostEventType.DOWN -> host.dispatchGuestKey(output.code, true)
                    ControllerHostEventType.UP -> host.dispatchGuestKey(output.code, false)
                    ControllerHostEventType.REPEAT -> host.dispatchGuestKeyRepeated(output.code)
                }
                is ControllerHostOutput.Action -> when (event.type) {
                    ControllerHostEventType.DOWN -> host.onHostAction(output.action, true)
                    ControllerHostEventType.UP -> host.onHostAction(output.action, false)
                    ControllerHostEventType.REPEAT -> host.onHostActionRepeated(output.action)
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

    /** Updates the immutable mapping snapshot. Active presses retain their captured binding. */
    fun updateConfig(next: ControllerConfig) {
        if (next == config) return
        beginBoundary(waitForNeutral = true)
        config = next
        recreatePointerControllers()
        if (next.notice != null) host.onControllerNotice(
            appContext.getString(R.string.config_gamepad_unsupported_summary),
        )
    }

    fun currentConfig(): ControllerConfig = config

    /** Called before a Displayable/surface target is replaced. */
    fun onTargetChanged() {
        beginBoundary(waitForNeutral = true)
    }

    /** Focus loss, pause, and destruction use an inactive barrier. */
    fun clear() {
        detachPointerConsumer()
        releaseAll()
        lifecycleGate.clear()
        lifecycleState = LifecycleState.INACTIVE
        activeDeviceId = null
        waitingDeviceId = null
        waitingDigitalKeys.clear()
        lastTarget = null
    }

    fun close() {
        clear()
        inputManager?.unregisterInputDeviceListener(this)
    }

    fun diagnostics(): ControllerDiagnosticSnapshot = ControllerDiagnosticSnapshot(
        state = lifecycleState.name,
        deviceId = activeDeviceId,
        deviceName = activeDeviceId?.let { InputDevice.getDevice(it)?.name },
        sessionId = sessionId,
        activeDigitalControls = digital.values.mapTo(LinkedHashSet()) { it.control },
        activeAnalogControls = binary.values.mapTo(LinkedHashSet()) { it.control } +
            directional.values.flatMapTo(LinkedHashSet()) { it.requestedControls },
        lastAxes = LinkedHashMap(axes),
        configSource = config.resolutionSource,
        configNotice = config.notice,
    )

    /** Handles controller button events before Activity/View dispatch. */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (!isControllerSource(event.source)) return false
        val control = controlTokenForKeyCode(event.keyCode) ?: return true
        val deviceId = event.deviceId
        val physicalKey = PhysicalKey(deviceId, event.keyCode)

        // Legacy gameplay remains on Android's established key-mapping path. Only an explicitly
        // host-owned action is intercepted here, so enabling the compatibility mode cannot make
        // old guest key mappings disappear or cause a second guest dispatch.
        val captured = digital[physicalKey]
        val configuredBinding = config.binding(control)
        if (config.legacy && captured == null &&
            (configuredBinding == null || configuredBinding.kind != BindingKind.HOST_ACTION)
        ) {
            return false
        }

        val lifecycleDecision = lifecycleGate.offerDigital(
            deviceId = deviceId,
            keyCode = event.keyCode,
            down = event.action == KeyEvent.ACTION_DOWN,
        )
        applyLifecycleDecision(lifecycleDecision)
        if (lifecycleDecision != ControllerLifecycleDecision.ACTIVE) {
            return true
        }
        if (!ensureActiveDevice(deviceId)) return true
        if (!ensureTarget()) return true

        val targetCanvas = host.currentCanvas()
        val keypadHandled = targetCanvas != null &&
            targetCanvas.handleControllerKeypad(
                control,
                event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0,
            )
        if (keypadHandled) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) modalKeys += physicalKey
                KeyEvent.ACTION_UP -> modalKeys.remove(physicalKey)
            }
            if (event.action == KeyEvent.ACTION_DOWN) host.onControllerInputAccepted()
            return true
        }

        // A release must always close the source captured by its DOWN, even when the host modal
        // became visible in between. Modal routing is for new contacts; it must not strand a
        // gameplay/menu binding in the ledger.
        if (event.action == KeyEvent.ACTION_UP && captured != null) {
            releaseDigital(physicalKey)
            return true
        }
        if (event.action == KeyEvent.ACTION_UP && modalKeys.remove(physicalKey)) {
            return true
        }
        if (host.isControllerModalActive()) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                modalKeys += physicalKey
            }
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount != 0) return true
            host.onControllerModalInput(control, event.action == KeyEvent.ACTION_DOWN)
            return true
        }

        // Android's repeat flag is not a second ownership source. The ledger's monotonic repeat
        // producer is the only repeat path for a captured controller contact.
        if (event.repeatCount != 0) return true

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (digital.containsKey(physicalKey)) return true
                host.onControllerInputAccepted()
                captureDigital(physicalKey, control)
            }
            KeyEvent.ACTION_UP -> {
                releaseDigital(physicalKey)
            }
            else -> return true
        }
        return true
    }

    /** Closes a key contact captured before a host modal took ownership of subsequent events. */
    fun releaseCapturedKey(event: KeyEvent): Boolean {
        if (!isControllerSource(event.source) || event.action != KeyEvent.ACTION_UP) return false
        val physicalKey = PhysicalKey(event.deviceId, event.keyCode)
        if (!digital.containsKey(physicalKey)) return false
        releaseDigital(physicalKey)
        return true
    }

    /** Handles HAT, stick, and trigger samples. */
    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!isControllerSource(event.source)) return false
        if (config.legacy) return false
        val deviceId = event.deviceId
        val device = InputDevice.getDevice(deviceId)
        if (device == null) {
            if (activeDeviceId == deviceId) clear()
            return true
        }
        val neutral = isNeutralMotion(event, device)
        val lifecycleDecision = lifecycleGate.offerMotion(deviceId, neutral)
        applyLifecycleDecision(lifecycleDecision)
        if (lifecycleDecision != ControllerLifecycleDecision.ACTIVE) {
            return true
        }
        if (!ensureActiveDevice(deviceId)) return true
        if (!ensureTarget()) return true
        if (host.isControllerModalActive()) {
            // Diagnosis remains live while a host modal owns the input. Record raw samples before
            // handing the event to the modal, but never run the guest/output processors here.
            recordDiagnosticMotion(event, device)
            host.onControllerModalMotion(event)
            return true
        }
        if (event.actionMasked != MotionEvent.ACTION_MOVE &&
            event.actionMasked != MotionEvent.ACTION_HOVER_MOVE
        ) return true

        var meaningful = false
        for (history in 0 until event.historySize) {
            meaningful = processMotion(event, device, history) || meaningful
        }
        meaningful = processMotion(event, device, -1) || meaningful
        if (meaningful) host.onControllerInputAccepted()
        return true
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        notifyControllerAvailability()
    }

    override fun onInputDeviceChanged(deviceId: Int) {
        if (activeDeviceId == deviceId) beginBoundary(waitForNeutral = true)
        notifyControllerAvailability()
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        if (activeDeviceId == deviceId || waitingDeviceId == deviceId) clear()
        notifyControllerAvailability()
    }

    private fun notifyControllerAvailability() {
        val available = InputDevice.getDeviceIds().any { deviceId ->
            InputDevice.getDevice(deviceId)?.let { isControllerSource(it.sources) } == true
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

    private fun updateHat(event: MotionEvent, device: InputDevice, history: Int): Boolean {
        val x = axisValue(event, MotionEvent.AXIS_HAT_X, history)
        val y = axisValue(event, MotionEvent.AXIS_HAT_Y, history)
        axes["hat_x"] = x
        axes["hat_y"] = y
        val controls = LinkedHashSet<String>()
        if (y < -HAT_THRESHOLD) controls += CONTROL_DPAD_UP
        if (y > HAT_THRESHOLD) controls += CONTROL_DPAD_DOWN
        if (x < -HAT_THRESHOLD) controls += CONTROL_DPAD_LEFT
        if (x > HAT_THRESHOLD) controls += CONTROL_DPAD_RIGHT
        val present = device.getMotionRange(MotionEvent.AXIS_HAT_X, event.source) != null ||
            device.getMotionRange(MotionEvent.AXIS_HAT_Y, event.source) != null
        if (!present) return false
        updateDirectional("hat", controls)
        return controls.isNotEmpty()
    }

    private fun updateStick(
        event: MotionEvent,
        device: InputDevice,
        stick: StickId,
        history: Int,
    ): Boolean {
        val settings = if (stick == StickId.LEFT) config.leftStick else config.rightStick
        val axesPair = stickAxes(device, event.source, stick) ?: return false
        val rawX = axisValue(event, axesPair.first, history)
        val rawY = axisValue(event, axesPair.second, history)
        val xRange = motionRange(device, event.source, axesPair.first)
        val yRange = motionRange(device, event.source, axesPair.second)
        val calibration = config.calibrations[capabilitySignatureFor(device, event.source)]
        val xCalibration = calibration?.immutableChannels[if (stick == StickId.LEFT) {
            CalibrationChannel.LEFT_X
        } else {
            CalibrationChannel.RIGHT_X
        }]
        val yCalibration = calibration?.immutableChannels[if (stick == StickId.LEFT) {
            CalibrationChannel.LEFT_Y
        } else {
            CalibrationChannel.RIGHT_Y
        }]
        axes["${stick.name.lowercase()}_x"] = rawX
        axes["${stick.name.lowercase()}_y"] = rawY
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
        if (config.pointer.sourceStick == stick) {
            // A pointer-configured stick is a continuous producer. It must not also become a
            // digital movement source, otherwise one physical stick can own two unrelated
            // guest outputs during the same sample.
            updateDirectional("stick:${stick.name.lowercase()}", emptySet())
            val canvas = pointerCanvas ?: host.currentCanvas()
            if (canvas == null) return false
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
        if (settings.mode != StickMode.DIRECTIONS) {
            updateDirectional("stick:${stick.name.lowercase()}", emptySet())
            return processed.magnitude >= settings.pressRadius.toFloat()
        }
        val directionConfig = StickProcessor.DirectionConfig(
            mode = when (settings.directionMode) {
                DirectionMode.FOUR -> StickProcessor.DirectionMode.FOUR_WAY
                else -> StickProcessor.DirectionMode.EIGHT_WAY
            },
            diagonalOutput = if (settings.directionMode == DirectionMode.DIAGONAL_NUMBER) {
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
        updateDirectional("stick:${stick.name.lowercase()}", controls)
        return controls.isNotEmpty()
    }

    private fun updateTrigger(
        event: MotionEvent,
        device: InputDevice,
        side: TriggerSide,
        history: Int,
    ): Boolean {
        val axis = triggerAxis(device, event.source, side) ?: return false
        val raw = axisValue(event, axis, history)
        val range = motionRange(device, event.source, axis, trigger = true)
            ?: StickProcessor.MotionRangeLike(0.0f, 1.0f)
        val calibration = config.calibrations[capabilitySignatureFor(device, event.source)]
        val calibrationChannel = if (side == TriggerSide.LEFT) {
            CalibrationChannel.LEFT_TRIGGER
        } else {
            CalibrationChannel.RIGHT_TRIGGER
        }
        val normalized = calibration?.immutableChannels[calibrationChannel]?.let {
            StickProcessor.normalizeTrigger(raw, it.range)
        } ?: StickProcessor.normalizeTrigger(raw, range)
        axes[if (side == TriggerSide.LEFT) "trigger_left" else "trigger_right"] = normalized
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
            captureBinary(channel, control)
        } else if (!next.pressed && previous.pressed) {
            releaseBinary(channel)
        }
        return next.pressed
    }

    private fun eventTime(event: MotionEvent, history: Int): Long =
        if (history < 0) event.eventTime else event.getHistoricalEventTime(history)

    private fun captureDigital(physicalKey: PhysicalKey, control: String) {
        val binding = effectiveBindingForControl(control)
        if (binding?.kind == BindingKind.HOST_ACTION &&
            binding.hostAction == HostAction.OPEN_MENU
        ) {
            // Opening a host modal is an ownership boundary. Do this before inserting the opener
            // into the new ledger so the opener itself receives a single, well-ordered DOWN.
            releaseAll()
        }
        val canvas = host.currentCanvas()
        val generation = canvas?.inputGeneration() ?: 0L
        val channel = "button:${physicalKey.keyCode}"
        val captured = CapturedDigital(
            physicalKey = physicalKey,
            control = control,
            binding = binding,
            canvas = canvas,
            deviceId = physicalKey.deviceId,
            generation = generation,
            sessionId = sessionId,
            channel = channel,
            directional = isDpadControl(control),
            hostTarget = currentHostTarget(),
        )
        digital[physicalKey] = captured
        if (captured.directional) {
            updateDirectional(channel, setOf(control))
        } else {
            emitBinding(captured.binding, canvas, captured.deviceId, generation,
                captured.sessionId, channel, captured.hostTarget, down = true)
        }
    }

    private fun releaseDigital(physicalKey: PhysicalKey) {
        val captured = digital.remove(physicalKey) ?: return
        if (captured.directional) {
            updateDirectional(captured.channel, emptySet())
        } else {
            emitBinding(captured.binding, captured.canvas, captured.deviceId, captured.generation,
                captured.sessionId, captured.channel, captured.hostTarget, down = false)
        }
    }

    private fun captureBinary(channel: String, control: String) {
        if (binary.containsKey(channel)) return
        val canvas = host.currentCanvas()
        val captured = BinarySource(
            channel = channel,
            control = control,
            binding = effectiveBindingForControl(control) ?: Binding.none(),
            canvas = canvas,
            deviceId = activeDeviceId ?: -1,
            generation = canvas?.inputGeneration() ?: 0L,
            sessionId = sessionId,
            hostTarget = currentHostTarget(),
        )
        binary[channel] = captured
        emitBinding(captured.binding, captured.canvas, captured.deviceId, captured.generation,
            captured.sessionId, channel, captured.hostTarget, down = true)
    }

    private fun releaseBinary(channel: String) {
        val captured = binary.remove(channel) ?: return
        emitBinding(captured.binding, captured.canvas, captured.deviceId, captured.generation,
            captured.sessionId, channel, captured.hostTarget, down = false)
    }

    private fun updateDirectional(
        channel: String,
        controls: Set<String>,
        movementGroup: String = if (channel == "stick:right") RIGHT_MOVEMENT_GROUP
        else LEFT_MOVEMENT_GROUP,
    ) {
        var source = directional[channel]
        if (source == null) {
            if (controls.isEmpty()) return
            val canvas = host.currentCanvas()
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
        for (control in controls) {
            if (!source.bindings.containsKey(control)) {
                source.bindings[control] = bindingForDirectionControl(control)
            }
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
            val guestCodes = LinkedHashSet<Int>()
            val hostActions = LinkedHashSet<HostAction>()
            val pointerActions = LinkedHashSet<PointerAction>()
            for (control in effectiveControls) {
                when (val binding = source.bindings[control] ?: Binding.none()) {
                    else -> when (binding.kind) {
                        BindingKind.GUEST_KEY -> binding.guestKey?.let { guestCodes += guestKeyCode(it) }
                        BindingKind.HOST_ACTION -> binding.hostAction?.let(hostActions::add)
                        BindingKind.POINTER_ACTION -> binding.pointerAction?.let(pointerActions::add)
                        BindingKind.NONE -> Unit
                    }
                }
            }
            val nextGuestCodes = guestCodes.toSet()
            if (source.canvas != null) {
                source.canvas.inputUpdated(
                    CONTROLLER_DEVICE_PREFIX + source.deviceId,
                    source.sessionId,
                    CONTROLLER_KIND,
                    source.channel,
                    *nextGuestCodes.toIntArray(),
                )
            } else {
                val target = source.hostTarget ?: currentHostTarget() ?: continue
                val outputs = nextGuestCodes.mapTo(ArrayList()) { ControllerHostOutput.GuestKey(it) }
                hostLedger.update(
                    sourceToken(source.deviceId, source.sessionId, source.channel),
                    target,
                    outputs,
                    if (outputs.isEmpty()) RepeatSpec.Disabled else RepeatSpec.Default,
                )
            }
            if (source.canvas == null) {
                val target = source.hostTarget ?: currentHostTarget() ?: continue
                hostLedger.update(
                    sourceToken(source.deviceId, source.sessionId, source.channel + ":host"),
                    target,
                    hostActions.map { ControllerHostOutput.Action(it) },
                    RepeatSpec.Disabled,
                )
            } else {
                // Host actions are still owned by the host ledger even while a Canvas is shown;
                // this keeps menu/help mappings from bypassing aggregate ownership.
                hostLedger.update(
                    sourceToken(source.deviceId, source.sessionId, source.channel + ":host"),
                    ControllerHostTarget("canvas@${System.identityHashCode(source.canvas)}",
                        source.generation),
                    hostActions.map { ControllerHostOutput.Action(it) },
                    RepeatSpec.Disabled,
                )
            }
            updateDirectionalPointerActions(source, pointerActions)
        }
    }

    private fun emitBinding(
        binding: Binding?,
        canvas: Canvas?,
        deviceId: Int,
        generation: Long,
        sourceSession: Long,
        channel: String,
        capturedHostTarget: ControllerHostTarget?,
        down: Boolean,
    ) {
        when (binding?.kind) {
            BindingKind.GUEST_KEY -> {
                val key = binding.guestKey ?: return
                if (canvas != null) {
                    val sourceDevice = CONTROLLER_DEVICE_PREFIX + deviceId
                    if (down) {
                        canvas.inputPressed(sourceDevice, sourceSession, CONTROLLER_KIND, channel,
                            guestKeyCode(key))
                    } else {
                            canvas.inputReleased(sourceDevice, sourceSession, CONTROLLER_KIND, channel)
                        }
                } else {
                    val target = capturedHostTarget ?: currentHostTarget() ?: return
                    val source = sourceToken(deviceId, sourceSession, channel)
                    val output = ControllerHostOutput.GuestKey(guestKeyCode(key))
                    if (down) {
                        hostLedger.down(source, target, listOf(output), RepeatSpec.Default)
                    } else {
                        hostLedger.up(source)
                    }
                }
            }
            BindingKind.HOST_ACTION -> binding.hostAction?.let { action ->
                val target = capturedHostTarget ?: currentHostTarget() ?: return
                val source = sourceToken(deviceId, sourceSession, channel)
                val output = ControllerHostOutput.Action(action)
                if (down) hostLedger.down(source, target, listOf(output), RepeatSpec.Disabled)
                else hostLedger.up(source)
            }
            BindingKind.POINTER_ACTION -> binding.pointerAction?.let {
                handlePointerClickBinding(deviceId, sourceSession, channel, down)
            }
            BindingKind.NONE, null -> Unit
        }
    }

    private fun updateDirectionalPointerActions(
        source: DirectionalSource,
        next: Set<PointerAction>,
    ) {
        val pointerSource = sourceToken(
            source.deviceId,
            source.sessionId,
            source.channel + ":pointer",
        )
        val previous = directionalPointerOwners[pointerSource].orEmpty()
        (previous - next).forEach {
            handlePointerClickBinding(source.deviceId, source.sessionId,
                pointerSource.channel, down = false)
        }
        (next - previous).forEach {
            handlePointerClickBinding(source.deviceId, source.sessionId,
                pointerSource.channel, down = true)
        }
        if (next.isEmpty()) directionalPointerOwners.remove(pointerSource)
        else directionalPointerOwners[pointerSource] = next.toSet()
    }

    private fun handlePointerClickBinding(
        deviceId: Int,
        sourceSession: Long,
        channel: String,
        down: Boolean,
    ) {
        if (config.pointer.mode != PointerMode.CURSOR) return
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

    private fun resetPointerState() {
        applyPointerActions(cursorController.reset())
        applyPointerActions(joystickController.reset())
        activeCursorClickToken = null
        pointerClickOwners.clear()
        directionalPointerOwners.clear()
        pointerPhysicalTokens.clear()
        pointerJoystickToken = null
        pointerLease.reset(releaseVirtual = false)
        updatePointerOverlay()
    }

    private fun recreatePointerControllers() {
        cursorController = ProportionalCursorController(
            pointerLease,
            CursorSettings(speedPerShortestSidePerSecond = config.pointer.speed.toFloat()),
        )
        joystickController = VirtualTouchJoystickController(
            pointerLease,
            VirtualJoystickSettings(
                centerXFraction = config.pointer.centerX.toFloat(),
                centerYFraction = config.pointer.centerY.toFloat(),
                radiusFractionOfShortestSide = config.pointer.radius.toFloat(),
            ),
        )
        pointerViewport = null
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
            val actions = joystickController.begin(token, pointerViewport ?: return false)
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
            waitingDigitalKeys.clear()
            lifecycleState = LifecycleState.WAIT_NEUTRAL
        } else {
            activeDeviceId = null
            waitingDeviceId = null
            waitingDigitalKeys.clear()
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
        val waitingDevice = snapshot.waitingDeviceId ?: snapshot.activeDeviceId
        waitingDigitalKeys = snapshot.waitingDigitalKeys.mapTo(LinkedHashSet()) {
            PhysicalKey(waitingDevice ?: -1, it)
        }
    }

    private fun releaseAll() {
        // Host outputs have their own typed ledger because non-Canvas Screens cannot receive
        // Canvas.postKey* callbacks. Clear it first; subsequent source UPs are idempotent.
        hostLedger.clear()
        modalKeys.clear()
        val directionalChannels = directional.keys.toList()
        for (channel in directionalChannels) updateDirectional(channel, emptySet())
        directional.clear()
        val binarySources = binary.values.toList()
        binary.clear()
        for (source in binarySources) {
            emitBinding(source.binding, source.canvas, source.deviceId, source.generation,
                source.sessionId, source.channel, source.hostTarget, down = false)
        }
        val digitalSources = digital.values.toList()
        digital.clear()
        for (source in digitalSources) {
            if (!source.directional) {
                emitBinding(source.binding, source.canvas, source.deviceId, source.generation,
                    source.sessionId, source.channel, source.hostTarget, down = false)
            }
        }
        for (runtime in stickStates.values) runtime.directionState = StickProcessor.DirectionState()
        triggerStates[TriggerSide.LEFT] = StickProcessor.TriggerState()
        triggerStates[TriggerSide.RIGHT] = StickProcessor.TriggerState()
    }

    private fun isNeutralMotion(event: MotionEvent, device: InputDevice): Boolean {
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        if (abs(hatX) > HAT_THRESHOLD || abs(hatY) > HAT_THRESHOLD) return false
        for (axis in STICK_AXES) {
            if (device.getMotionRange(axis, event.source) != null &&
                abs(event.getAxisValue(axis)) > NEUTRAL_AXIS_THRESHOLD
            ) return false
        }
        for (axis in TRIGGER_AXES) {
            val range = device.getMotionRange(axis, event.source) ?: continue
            if (StickProcessor.normalizeTrigger(event.getAxisValue(axis),
                    StickProcessor.MotionRangeLike(range.min, range.max)) > TRIGGER_NEUTRAL_THRESHOLD
            ) return false
        }
        return true
    }

    private fun recordDiagnosticMotion(event: MotionEvent, device: InputDevice) {
        device.motionRanges.forEach { range ->
            if (range.source == 0 || range.source and event.source != 0) {
                val value = event.getAxisValue(range.axis)
                if (value.isFinite()) axes["axis_${range.axis}"] = value
            }
        }
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        if (hatX.isFinite()) axes["hat_x"] = hatX
        if (hatY.isFinite()) axes["hat_y"] = hatY
    }

    private fun axisValue(event: MotionEvent, axis: Int, history: Int): Float =
        if (history < 0) event.getAxisValue(axis)
        else event.getHistoricalAxisValue(axis, history)

    private fun motionRange(
        device: InputDevice,
        source: Int,
        axis: Int,
        trigger: Boolean = false,
    ): StickProcessor.MotionRangeLike {
        val range = device.getMotionRange(axis, source) ?: device.getMotionRange(axis)
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
            val z = device.getMotionRange(MotionEvent.AXIS_Z, source) != null
            val rz = device.getMotionRange(MotionEvent.AXIS_RZ, source) != null
            if (z && rz) MotionEvent.AXIS_Z to MotionEvent.AXIS_RZ
            else MotionEvent.AXIS_RX to MotionEvent.AXIS_RY
        }
        val hasX = device.getMotionRange(axes.first, source) != null ||
            device.getMotionRange(axes.first) != null
        val hasY = device.getMotionRange(axes.second, source) != null ||
            device.getMotionRange(axes.second) != null
        return if (hasX && hasY) axes else null
    }

    private fun triggerAxis(device: InputDevice, source: Int, side: TriggerSide): Int? {
        val preferred = if (side == TriggerSide.LEFT) {
            intArrayOf(MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE)
        } else {
            intArrayOf(MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS)
        }
        return preferred.firstOrNull {
            device.getMotionRange(it, source) != null || device.getMotionRange(it) != null
        }
    }

    private fun bindingForDirectionControl(control: String): Binding {
        return when (control) {
            DIRECTION_NUM1 -> Binding.guestKey(GuestKey.NUM1)
            DIRECTION_NUM3 -> Binding.guestKey(GuestKey.NUM3)
            DIRECTION_NUM7 -> Binding.guestKey(GuestKey.NUM7)
            DIRECTION_NUM9 -> Binding.guestKey(GuestKey.NUM9)
            else -> config.binding(control) ?: Binding.none()
        }
    }

    /**
     * Resolves the binding once, at the DOWN edge, so a later profile or pointer-mode change
     * cannot alter the UP edge.  The explicit cursor click setting is the A-control override;
     * NONE leaves the normal A mapping intact and therefore remains backwards-compatible.
     */
    private fun effectiveBindingForControl(control: String): Binding? {
        // B is a host-context Back action on Screen and app-owned surfaces.  Gameplay still uses
        // the configured B binding (normally NUM0), while an explicit Canvas target preserves the
        // guest mapping exactly as configured.
        if (control == CONTROL_BUTTON_B && host.currentCanvas() == null && !config.legacy) {
            return Binding.hostAction(HostAction.BACK)
        }
        if (control == CONTROL_BUTTON_A &&
            config.pointer.mode == PointerMode.CURSOR &&
            config.pointer.clickBinding.kind == BindingKind.POINTER_ACTION
        ) {
            return config.pointer.clickBinding
        }
        return config.binding(control)
    }

    private fun directionControl(key: StickProcessor.DirectionKey): String = when (key) {
        StickProcessor.DirectionKey.UP -> CONTROL_DPAD_UP
        StickProcessor.DirectionKey.DOWN -> CONTROL_DPAD_DOWN
        StickProcessor.DirectionKey.LEFT -> CONTROL_DPAD_LEFT
        StickProcessor.DirectionKey.RIGHT -> CONTROL_DPAD_RIGHT
        StickProcessor.DirectionKey.NUM1 -> DIRECTION_NUM1
        StickProcessor.DirectionKey.NUM3 -> DIRECTION_NUM3
        StickProcessor.DirectionKey.NUM7 -> DIRECTION_NUM7
        StickProcessor.DirectionKey.NUM9 -> DIRECTION_NUM9
    }

    private fun isDpadControl(control: String): Boolean = control == CONTROL_DPAD_UP ||
        control == CONTROL_DPAD_DOWN || control == CONTROL_DPAD_LEFT || control == CONTROL_DPAD_RIGHT

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
        const val CONTROL_BUTTON_A = "button_a"
        const val CONTROL_BUTTON_B = "button_b"
        const val CONTROL_BUTTON_X = "button_x"
        const val CONTROL_BUTTON_Y = "button_y"
        const val CONTROL_BUTTON_L1 = "button_l1"
        const val CONTROL_BUTTON_R1 = "button_r1"
        const val CONTROL_BUTTON_L2 = "button_l2"
        const val CONTROL_BUTTON_R2 = "button_r2"
        const val CONTROL_BUTTON_START = "button_start"
        const val CONTROL_BUTTON_SELECT = "button_select"

        private const val DIRECTION_NUM1 = "__direction_num1"
        private const val DIRECTION_NUM3 = "__direction_num3"
        private const val DIRECTION_NUM7 = "__direction_num7"
        private const val DIRECTION_NUM9 = "__direction_num9"
        private const val CONTROLLER_KIND = "controller"
        private const val CONTROLLER_DEVICE_PREFIX = "android:"
        private const val LEFT_MOVEMENT_GROUP = "left-movement"
        private const val RIGHT_MOVEMENT_GROUP = "right-movement"
        private const val HAT_THRESHOLD = 0.5f
        private const val NEUTRAL_AXIS_THRESHOLD = 0.15f
        private const val TRIGGER_NEUTRAL_THRESHOLD = 0.40f
        private val STICK_AXES = intArrayOf(
            MotionEvent.AXIS_X, MotionEvent.AXIS_Y,
            MotionEvent.AXIS_RX, MotionEvent.AXIS_RY,
            MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
        )
        private val TRIGGER_AXES = intArrayOf(
            MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER,
            MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_GAS,
        )

        @JvmStatic
        fun isControllerSource(source: Int): Boolean =
            source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
                source and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD

        /**
         * Stable calibration key: Android device ids are process-local and are deliberately not
         * part of this value. Descriptor plus the complete reported motion capability set keeps a
         * profile attached to a physical controller/capability shape across reconnects.
         */
        @JvmStatic
        fun capabilitySignatureFor(device: InputDevice, source: Int): String = buildString {
            append("descriptor=")
            append(device.descriptor.orEmpty().ifBlank { "unknown" }.replace("|", "%7C"))
            append(";source=")
            append(source)
            device.motionRanges
                .sortedWith(
                    compareBy<InputDevice.MotionRange>(
                        { it.axis },
                        { it.source },
                        { it.min },
                        { it.max },
                        { it.flat },
                        { it.fuzz },
                        { it.resolution },
                    ),
                )
                .forEach { range ->
                    append(";axis=")
                    append(range.axis)
                    append(',')
                    append(range.source)
                    append(',')
                    append(capabilityNumber(range.min))
                    append(',')
                    append(capabilityNumber(range.max))
                    append(',')
                    append(capabilityNumber(range.flat))
                    append(',')
                    append(capabilityNumber(range.fuzz))
                    append(',')
                    append(capabilityNumber(range.resolution))
                }
        }

        private fun capabilityNumber(value: Float): String =
            if (value.isFinite()) String.format(Locale.US, "%.6f", value) else "nan"

        @JvmStatic
        fun controlTokenForKeyCode(keyCode: Int): String? = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> CONTROL_DPAD_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> CONTROL_DPAD_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> CONTROL_DPAD_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> CONTROL_DPAD_RIGHT
            KeyEvent.KEYCODE_DPAD_CENTER -> CONTROL_BUTTON_A
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_1 -> CONTROL_BUTTON_A
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_2 -> CONTROL_BUTTON_B
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_3 -> CONTROL_BUTTON_X
            KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_4 -> CONTROL_BUTTON_Y
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_5 -> CONTROL_BUTTON_L1
            KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_6 -> CONTROL_BUTTON_R1
            KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_7 -> CONTROL_BUTTON_L2
            KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_BUTTON_8 -> CONTROL_BUTTON_R2
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_9 -> CONTROL_BUTTON_START
            KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_10 -> CONTROL_BUTTON_SELECT
            else -> null
        }

        @JvmStatic
        fun guestKeyCode(key: GuestKey): Int = when (key) {
            GuestKey.NUM0 -> Canvas.KEY_NUM0
            GuestKey.NUM1 -> Canvas.KEY_NUM1
            GuestKey.NUM2 -> Canvas.KEY_NUM2
            GuestKey.NUM3 -> Canvas.KEY_NUM3
            GuestKey.NUM4 -> Canvas.KEY_NUM4
            GuestKey.NUM5 -> Canvas.KEY_NUM5
            GuestKey.NUM6 -> Canvas.KEY_NUM6
            GuestKey.NUM7 -> Canvas.KEY_NUM7
            GuestKey.NUM8 -> Canvas.KEY_NUM8
            GuestKey.NUM9 -> Canvas.KEY_NUM9
            GuestKey.STAR -> Canvas.KEY_STAR
            GuestKey.POUND -> Canvas.KEY_POUND
            GuestKey.UP -> Canvas.KEY_UP
            GuestKey.DOWN -> Canvas.KEY_DOWN
            GuestKey.LEFT -> Canvas.KEY_LEFT
            GuestKey.RIGHT -> Canvas.KEY_RIGHT
            GuestKey.FIRE -> Canvas.KEY_FIRE
            GuestKey.SOFT_LEFT -> Canvas.KEY_SOFT_LEFT
            GuestKey.SOFT_RIGHT -> Canvas.KEY_SOFT_RIGHT
            GuestKey.CLEAR -> Canvas.KEY_CLEAR
            GuestKey.SEND -> Canvas.KEY_SEND
            GuestKey.END -> Canvas.KEY_END
            GuestKey.GAME_A -> KeyMapper.getKeyCode(Canvas.GAME_A)
            GuestKey.GAME_B -> KeyMapper.getKeyCode(Canvas.GAME_B)
            GuestKey.GAME_C -> KeyMapper.getKeyCode(Canvas.GAME_C)
            GuestKey.GAME_D -> KeyMapper.getKeyCode(Canvas.GAME_D)
        }

        /** Maps a canonical guest key to an Android key for host-only focus surfaces. */
        @JvmStatic
        fun androidKeyCodeForGuestKey(keyCode: Int): Int = when (keyCode) {
            Canvas.KEY_NUM0 -> KeyEvent.KEYCODE_0
            Canvas.KEY_NUM1 -> KeyEvent.KEYCODE_1
            Canvas.KEY_NUM2 -> KeyEvent.KEYCODE_2
            Canvas.KEY_NUM3 -> KeyEvent.KEYCODE_3
            Canvas.KEY_NUM4 -> KeyEvent.KEYCODE_4
            Canvas.KEY_NUM5 -> KeyEvent.KEYCODE_5
            Canvas.KEY_NUM6 -> KeyEvent.KEYCODE_6
            Canvas.KEY_NUM7 -> KeyEvent.KEYCODE_7
            Canvas.KEY_NUM8 -> KeyEvent.KEYCODE_8
            Canvas.KEY_NUM9 -> KeyEvent.KEYCODE_9
            Canvas.KEY_STAR -> KeyEvent.KEYCODE_STAR
            Canvas.KEY_POUND -> KeyEvent.KEYCODE_POUND
            Canvas.KEY_UP -> KeyEvent.KEYCODE_DPAD_UP
            Canvas.KEY_DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
            Canvas.KEY_LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
            Canvas.KEY_RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
            Canvas.KEY_FIRE -> KeyEvent.KEYCODE_DPAD_CENTER
            Canvas.KEY_SOFT_LEFT -> KeyEvent.KEYCODE_SOFT_LEFT
            Canvas.KEY_SOFT_RIGHT -> KeyEvent.KEYCODE_SOFT_RIGHT
            Canvas.KEY_CLEAR -> KeyEvent.KEYCODE_DEL
            Canvas.KEY_SEND -> KeyEvent.KEYCODE_CALL
            Canvas.KEY_END -> KeyEvent.KEYCODE_ENDCALL
            else -> KeyEvent.KEYCODE_UNKNOWN
        }

        private fun resolveProfile(profile: ProfileModel?): ControllerConfig {
            if (profile == null) return ControllerConfig.defaultNavigation()
            val controller = profile.controller?.takeIf { it.isJsonObject }?.asJsonObject
            return ControllerConfig.resolve(controller, legacyMappings(profile.keyMappings))
        }

        private fun legacyMappings(mappings: SparseIntArray?): Map<String, String> {
            if (mappings == null || mappings.size() == 0) return emptyMap()
            val result = LinkedHashMap<String, String>()
            for (index in 0 until mappings.size()) {
                val androidCode = mappings.keyAt(index)
                val control = controlTokenForKeyCode(androidCode) ?: continue
                val token = legacyGuestToken(mappings.valueAt(index)) ?: continue
                result[control] = token
            }
            return result
        }

        private fun legacyGuestToken(keyCode: Int): String? = when (keyCode) {
            KeyMapper.KEY_OPTIONS_MENU -> "LEGACY_MENU"
            Canvas.KEY_NUM0 -> GuestKey.NUM0.token
            Canvas.KEY_NUM1 -> GuestKey.NUM1.token
            Canvas.KEY_NUM2 -> GuestKey.NUM2.token
            Canvas.KEY_NUM3 -> GuestKey.NUM3.token
            Canvas.KEY_NUM4 -> GuestKey.NUM4.token
            Canvas.KEY_NUM5 -> GuestKey.NUM5.token
            Canvas.KEY_NUM6 -> GuestKey.NUM6.token
            Canvas.KEY_NUM7 -> GuestKey.NUM7.token
            Canvas.KEY_NUM8 -> GuestKey.NUM8.token
            Canvas.KEY_NUM9 -> GuestKey.NUM9.token
            Canvas.KEY_STAR -> GuestKey.STAR.token
            Canvas.KEY_POUND -> GuestKey.POUND.token
            Canvas.KEY_UP -> GuestKey.UP.token
            Canvas.KEY_DOWN -> GuestKey.DOWN.token
            Canvas.KEY_LEFT -> GuestKey.LEFT.token
            Canvas.KEY_RIGHT -> GuestKey.RIGHT.token
            Canvas.KEY_FIRE -> GuestKey.FIRE.token
            Canvas.KEY_SOFT_LEFT -> GuestKey.SOFT_LEFT.token
            Canvas.KEY_SOFT_RIGHT -> GuestKey.SOFT_RIGHT.token
            Canvas.KEY_CLEAR -> GuestKey.CLEAR.token
            Canvas.KEY_SEND -> GuestKey.SEND.token
            Canvas.KEY_END -> GuestKey.END.token
            else -> null
        }

        private fun nextSession(current: Long): Long =
            if (current == Long.MAX_VALUE) 1L else current + 1L
    }
}

private fun InputDevice.getMotionRange(axis: Int, source: Int): InputDevice.MotionRange? =
    getMotionRange(axis, source) ?: getMotionRange(axis)
