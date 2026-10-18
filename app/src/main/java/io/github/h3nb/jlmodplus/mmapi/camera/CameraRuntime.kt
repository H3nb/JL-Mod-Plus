// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.mmapi.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.util.Size
import android.view.View
import android.widget.FrameLayout
import android.graphics.Color
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.Collections
import javax.microedition.media.MediaException
import javax.microedition.media.Player
import javax.microedition.shell.MicroActivity
import javax.microedition.util.ContextHolder

/** Android main thread owns the hardware lease. Guest threads only await cancellable calls. */
object CameraRuntime {
    fun interface SwitchFailure { fun accept(message: String) }
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "CameraSnapshot").apply { isDaemon = true } }
    private val epoch = AtomicLong()
    private val players = Collections.newSetFromMap(ConcurrentHashMap<CameraCapturePlayer, Boolean>())
    private var host: MicroActivity? = null
    private var foreground = false
    private var hostHasResumed = false
    private var permission: Runnable? = null
    private var refresh: Runnable? = null
    private var requestingPermission = false
    private var provider: ProcessCameraProvider? = null
    private var loadingProvider = false
    private var owner: CameraCapturePlayer? = null
    private var binding: Binding? = null
    private var overrideFacing: Int? = null
    private var switchFailure: SwitchFailure? = null
    private var previousFacing: Int? = null
    private var capturing: CameraCall<ByteArray>? = null
    private val pending = ArrayList<Pending>()

    private class Pending(val player: CameraCapturePlayer, val call: CameraCall<*>, val ready: (Binding) -> Unit)
    private class Binding(val player: CameraCapturePlayer, val view: PreviewView?, val facing: Int,
                          val rotation: Int, val capture: ImageCapture, val preview: Preview?) {
        lateinit var camera: Camera
        lateinit var cameraObserver: Observer<CameraState>
        var streamObserver: Observer<PreviewView.StreamState>? = null
        var open = false
        var streaming = view == null
        val ready get() = open && streaming
    }

    internal fun generation() = epoch.get()
    internal fun register(player: CameraCapturePlayer) { players.add(player) }
    private fun post(action: () -> Unit) { main.post(action) }
    private fun valid(player: CameraCapturePlayer) = player.runtimeGeneration == epoch.get() && player.getState() != Player.CLOSED

    @JvmStatic fun beginRuntime() = resetRuntime()
    @JvmStatic fun closeRuntime() = resetRuntime()
    private fun resetRuntime() {
        val generation = epoch.incrementAndGet()
        // Close synchronously to unblock guests even if Android is busy destroying its host.
        for (player in players) if (player.runtimeGeneration < generation) player.close()
        post {
            if (epoch.get() == generation) {
                unbind()
                owner = null
                overrideFacing = null
                finishSwitch("Camera session ended")
                discardPending()
                notifyMenu()
            }
        }
    }

    @JvmStatic fun isCameraAvailable(): Boolean {
        val pm = ContextHolder.getAppContext().packageManager
        return pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }

    @JvmStatic fun attachHost(activity: MicroActivity, requestPermission: Runnable, refreshActions: Runnable) {
        if (host !== activity) {
            owner?.hostSuspended()
            unbind()
            host = activity
            foreground = false
            hostHasResumed = false
        }
        permission = requestPermission
        refresh = refreshActions
        notifyMenu()
    }
    @JvmStatic fun onHostResumed(activity: MicroActivity) {
        if (host !== activity) return
        foreground = true
        hostHasResumed = true
        drain()
    }
    @JvmStatic fun onHostPaused(activity: MicroActivity) {
        if (host !== activity) return
        foreground = false
        // A permission dialog may pause the host; its pending request must survive the dialog.
        if (!requestingPermission) {
            owner?.hostSuspended()
            failPending(MediaException("Camera host is not active"))
        }
        unbind()
        finishSwitch("Camera host is not active")
        notifyMenu()
    }
    @JvmStatic fun onHostDestroyed(activity: MicroActivity) {
        if (host !== activity) return
        onHostPaused(activity)
        failPending(MediaException("Camera host was destroyed"))
        host = null
        permission = null
        refresh = null
        requestingPermission = false
    }
    @JvmStatic fun onHostConfigurationChanged(activity: MicroActivity) {
        if (host !== activity) return
        unbind()
        drain()
    }
    @JvmStatic fun onPermissionResult(activity: MicroActivity, granted: Boolean) {
        if (host !== activity) return
        requestingPermission = false
        if (!granted) {
            failPending(SecurityException("Camera permission denied"))
            owner = null
            notifyMenu()
        } else drain()
    }

    internal fun prepared(player: CameraCapturePlayer, start: Boolean) {
        val call = CameraCall<Unit>(120_000)
        player.waitFor(call) {
            post { enqueue(Pending(player, call) {
                player.markPrepared(start, call)
                updatePreviewVisibility()
            }) }
        }
    }
    internal fun snapshot(player: CameraCapturePlayer, options: CameraCaptureOptions.Snapshot): ByteArray {
        val call = CameraCall<ByteArray>(20_000)
        return player.waitFor(call) {
            post {
                if (capturing != null || switchFailure != null) call.fail(MediaException("Camera is busy"))
                else enqueue(Pending(player, call) { capture(it, call, options) })
            }
        }
    }
    private fun enqueue(request: Pending) {
        if (request.call.isComplete) return
        if (!valid(request.player)) { request.call.fail(MediaException("Camera session ended")); return }
        if (host == null || (!foreground && hostHasResumed)) { request.call.fail(MediaException("Camera host is not active")); return }
        if (owner != null && owner !== request.player) {
            request.call.fail(MediaException("Camera is in use by another Player")); return
        }
        if (pending.size >= 8) { request.call.fail(MediaException("Too many camera operations")); return }
        owner = request.player
        pending.add(request)
        drain()
    }
    private fun discardPending() {
        pending.removeAll { it.call.isComplete || !valid(it.player) }
        if (capturing?.isComplete == true) unbind()
        if (owner?.let { !valid(it) || (it.getState() < Player.PREFETCHED && pending.isEmpty()) } == true) {
            unbind()
            owner = null
        }
    }
    internal fun discardCompletedCalls() = post { discardPending(); drain(); notifyMenu() }
    internal fun playerStopped(player: CameraCapturePlayer) = post {
        discardPending()
        if (owner === player) updatePreviewVisibility()
        notifyMenu()
    }
    internal fun release(player: CameraCapturePlayer) {
        if (player.getState() == Player.CLOSED) players.remove(player)
        post {
            pending.removeAll { it.player === player }
            if (owner === player) {
                unbind()
                owner = null
                finishSwitch("Camera Player released")
            }
            notifyMenu()
        }
    }

    private fun drain() {
        discardPending()
        val player = owner ?: return
        val activity = host ?: return
        if (!foreground || !valid(player)) return
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (!requestingPermission && pending.isNotEmpty()) {
                requestingPermission = true
                try { permission?.run() ?: throw MediaException("No camera permission host") }
                catch (failure: Exception) { requestingPermission = false; backendFailed(failure) }
            }
            return
        }
        val cameras = provider
        if (cameras == null) {
            if (!loadingProvider) {
                loadingProvider = true
                val future = ProcessCameraProvider.getInstance(activity.applicationContext)
                future.addListener({
                    loadingProvider = false
                    try { provider = future.get(); drain() }
                    catch (failure: Exception) { backendFailed(failure) }
                }, ContextCompat.getMainExecutor(activity))
            }
            return
        }
        try {
            if (binding == null) bind(cameras, activity, player)
            val current = binding ?: return
            if (!current.ready) return
            player.hostResumed()
            finishSwitch(null)
            updatePreviewVisibility()
            val ready = pending.toList()
            pending.clear()
            for (request in ready) {
                if (!request.call.isComplete && valid(request.player)) {
                    try { request.ready(current) }
                    catch (failure: Exception) { request.call.fail(failure) }
                }
            }
            notifyMenu()
        } catch (failure: Exception) { backendFailed(failure) }
    }

    private fun selector(facing: Int) = CameraSelector.Builder().requireLensFacing(facing).build()
    private fun facing(player: CameraCapturePlayer, cameras: ProcessCameraProvider): Int {
        val requested = overrideFacing ?: player.source.facing
        if (requested != null) {
            if (!cameras.hasCamera(selector(requested))) throw MediaException("Requested camera is unavailable")
            return requested
        }
        return when {
            cameras.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.LENS_FACING_BACK
            cameras.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.LENS_FACING_FRONT
            else -> throw MediaException("No camera is available")
        }
    }

    private fun bind(cameras: ProcessCameraProvider, activity: MicroActivity, player: CameraCapturePlayer) {
        val lens = facing(player, cameras)
        val container = player.previewContainer?.takeIf { it.isAttachedToWindow && it.isShown }
        // A new View starts at IDLE. Reusing PreviewView can expose its old STREAMING value
        // before the new camera has produced a frame, especially during a lens switch.
        val view = container?.let {
            newPreview(it.context).also { preview ->
                it.removeAllViews()
                it.addView(preview, FrameLayout.LayoutParams(-1, -1))
            }
        }
        @Suppress("DEPRECATION")
        val rotation = activity.windowManager.defaultDisplay.rotation
        val resolution = ResolutionSelector.Builder()
            .setResolutionStrategy(ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
            .setResolutionFilter { sizes, _ -> sizes.filter { it.width.toLong() * it.height <= 4_194_304L } }
            .build()
        val capture = ImageCapture.Builder().setResolutionSelector(resolution)
            .setTargetRotation(rotation).setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setOutputFormat(ImageCapture.OUTPUT_FORMAT_JPEG).build()
        val preview = view?.let { Preview.Builder().setResolutionSelector(resolution).setTargetRotation(rotation).build() }
        val next = Binding(player, view, lens, rotation, capture, preview)
        val group = UseCaseGroup.Builder().setViewPort(ViewPort.Builder(
            Rational(player.source.width, player.source.height), rotation).setScaleType(ViewPort.FILL_CENTER).build())
            .addUseCase(capture)
        if (preview != null && view != null) {
            // PreviewView mirrors front cameras by default, including pre-API33. Cancel that
            // display transform so the guest and its encoded image both see physical pixels.
            view.scaleX = if (lens == CameraSelector.LENS_FACING_FRONT) -1f else 1f
            view.alpha = 0f
            preview.setSurfaceProvider(view.surfaceProvider)
            group.addUseCase(preview)
        }
        binding = next
        try { next.camera = cameras.bindToLifecycle(activity, selector(lens), group.build()) }
        catch (failure: Exception) { binding = null; throw failure }
        next.cameraObserver = Observer { state ->
            if (binding === next) {
                next.open = state.type == CameraState.Type.OPEN
                if (state.error != null) backendFailed(MediaException("Camera hardware error ${state.error!!.code}"))
                else drain()
            }
        }
        next.camera.cameraInfo.cameraState.observeForever(next.cameraObserver)
        if (binding !== next) return
        if (view != null) {
            val observer = Observer<PreviewView.StreamState> { state ->
                if (binding === next) { next.streaming = state == PreviewView.StreamState.STREAMING; drain() }
            }
            next.streamObserver = observer
            view.previewStreamState.observeForever(observer)
        }
        main.postDelayed({ if (binding === next && !next.ready) backendFailed(MediaException("Camera did not become ready")) }, 15_000)
        notifyMenu()
    }

    private fun unbind() {
        val old = binding
        binding = null
        capturing?.fail(MediaException("Camera stream changed; retry capture"))
        capturing = null
        if (old != null) {
            old.view?.alpha = 0f
            (old.view?.parent as? FrameLayout)?.removeView(old.view)
            old.camera.cameraInfo.cameraState.removeObserver(old.cameraObserver)
            old.streamObserver?.let { old.view?.previewStreamState?.removeObserver(it) }
            if (old.preview != null) provider?.unbind(old.capture, old.preview)
            else provider?.unbind(old.capture)
        }
    }
    private fun backendFailed(failure: Exception) {
        val player = owner
        unbind()
        failPending(failure)
        if (switchFailure != null) {
            overrideFacing = previousFacing
            finishSwitch(failure.message ?: "Could not switch camera")
            // Recover the previous stream once; a second failure follows the ordinary path.
            drain()
        } else {
            owner = null
            if (player != null && player.getState() >= Player.PREFETCHED) player.fail(failure.message ?: "Camera failed")
        }
        notifyMenu()
    }
    private fun failPending(failure: Exception) {
        for (request in pending) request.call.fail(failure)
        pending.clear()
    }
    private fun updatePreviewVisibility() {
        val current = binding ?: return
        current.view?.alpha = if (current.ready && current.player.getState() == Player.STARTED) 1f else 0f
    }

    private fun newPreview(context: Context) = PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FIT_CENTER
        setBackgroundColor(Color.BLACK)
        isClickable = false
        isFocusable = false
        alpha = 0f
    }
    internal fun createPreview(player: CameraCapturePlayer, context: Context): View {
        check(Looper.myLooper() == Looper.getMainLooper())
        val container = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        player.previewContainer = container
        container.viewTreeObserver.addOnGlobalLayoutListener { presentationChanged(player) }
        container.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { presentationChanged(player) }
            override fun onViewDetachedFromWindow(v: View) { presentationChanged(player) }
        })
        return container
    }
    internal fun detachPreview(player: CameraCapturePlayer, view: View) {
        if (player.previewContainer === view) player.previewContainer = null
        presentationChanged(player)
    }
    private fun presentationChanged(player: CameraCapturePlayer) = post {
        if (owner === player) {
            val shown = player.previewContainer?.let { it.isAttachedToWindow && it.isShown } == true
            val current = binding
            if (current != null && ((current.view != null) != shown ||
                        (current.view != null && current.view.parent !== player.previewContainer))) {
                unbind()
                drain()
            }
        }
    }

    private fun capture(current: Binding, call: CameraCall<ByteArray>, options: CameraCaptureOptions.Snapshot) {
        if (capturing != null || switchFailure != null) { call.fail(MediaException("Camera is busy")); return }
        capturing = call
        notifyMenu()
        current.capture.takePicture(worker, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    if (!call.isComplete) {
                        val bytes = CameraSnapshotEncoder.encode(image, options)
                        post {
                            if (binding === current && valid(current.player)) call.complete(bytes)
                            else call.fail(MediaException("Camera stream changed"))
                            if (capturing === call) capturing = null
                            notifyMenu()
                        }
                    }
                } catch (failure: Exception) { finishCapture(call, failure) }
                finally {
                    image.close()
                    post { if (capturing === call && call.isComplete) capturing = null; notifyMenu() }
                }
            }
            override fun onError(exception: ImageCaptureException) = finishCapture(call, exception)
        })
    }
    private fun finishCapture(call: CameraCall<ByteArray>, failure: Exception) = post {
        call.fail(failure)
        if (capturing === call) capturing = null
        notifyMenu()
    }

    @JvmStatic fun canSwitchCamera(): Boolean {
        val cameras = provider ?: return false
        val current = binding ?: return false
        return try {
            valid(current.player) && foreground &&
                cameras.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) && cameras.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
        } catch (_: Exception) { false }
    }
    @JvmStatic fun isFrontCamera() = binding?.facing == CameraSelector.LENS_FACING_FRONT
    @JvmStatic fun isSwitchingCamera() = switchFailure != null || capturing != null || binding?.ready == false
    @JvmStatic fun switchCamera(onFailure: SwitchFailure) {
        if (!canSwitchCamera() || isSwitchingCamera()) { onFailure.accept("Camera is not ready"); return }
        previousFacing = overrideFacing
        overrideFacing = if (isFrontCamera()) CameraSelector.LENS_FACING_BACK else CameraSelector.LENS_FACING_FRONT
        switchFailure = onFailure
        unbind()
        drain()
        notifyMenu()
    }
    private fun finishSwitch(message: String?) {
        val callback = switchFailure
        switchFailure = null
        previousFacing = null
        if (message != null) callback?.accept(message)
    }
    private fun notifyMenu() { refresh?.run() }
}
