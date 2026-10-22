// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.mmapi.camera

import android.Manifest
import java.io.File
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.core.UseCase
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.util.Size
import android.view.View
import android.view.Gravity
import android.widget.FrameLayout
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
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
    @Volatile private var runtimeOpen = false
    internal var defaultDimensions = CameraCaptureOptions.Dimensions()
        private set
    internal var defaultFacing = CameraCaptureOptions.BACK
        private set
    private val players = Collections.newSetFromMap(ConcurrentHashMap<CameraCapturePlayer, Boolean>())
    private var host: MicroActivity? = null
    private var displayManager: DisplayManager? = null
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            val activity = host ?: return
            val current = binding ?: return
            @Suppress("DEPRECATION")
            val display = activity.windowManager.defaultDisplay
            // A 180-degree turn can change display rotation without a configuration callback.
            if (foreground && recordingSession == null && display.displayId == displayId && current.rotation != display.rotation) {
                unbind()
                drain()
            }
        }
    }
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
    private var recordingSession: VideoSession? = null
    private class VideoSession(
        val player: CameraCapturePlayer, val file: File, val microphone: Boolean,
        val started: CameraCall<Unit>,
    ) {
        var launched = false
        var recorder: Recording? = null
        var finalized = false
        var problem: MediaException? = null
        var finalCall: CameraCall<Unit>? = null
    }
    private val pending = ArrayList<Pending>()

    private class Pending(val player: CameraCapturePlayer, val call: CameraCall<*>, val ready: (Binding) -> Unit)
    private class Binding(val player: CameraCapturePlayer, val view: PreviewView?, val facing: Int,
                          val rotation: Int, val capture: ImageCapture?, val preview: Preview?,
                          val video: VideoCapture<Recorder>?) {
        lateinit var camera: Camera
        lateinit var cameraObserver: Observer<CameraState>
        var streamObserver: Observer<PreviewView.StreamState>? = null
        var open = false
        var streaming = view == null
        val ready get() = open && streaming
    }

    internal fun generation() = epoch.get()
    internal fun register(player: CameraCapturePlayer) {
        if (!valid(player)) throw MediaException("Camera runtime is closed")
        players.add(player)
        if (!valid(player)) {
            player.close()
            throw MediaException("Camera runtime is closed")
        }
    }
    private fun post(action: () -> Unit) { main.post(action) }
    private fun valid(player: CameraCapturePlayer) = runtimeOpen && player.runtimeGeneration == epoch.get() && player.getState() != Player.CLOSED

    @JvmStatic @JvmOverloads fun beginRuntime(
        dimensions: CameraCaptureOptions.Dimensions = CameraCaptureOptions.Dimensions(),
        facing: Int = CameraCaptureOptions.BACK,
    ) {
        runtimeOpen = false
        resetRuntime()
        defaultDimensions = dimensions
        defaultFacing = CameraCaptureOptions.normalizeFacing(facing)
        runtimeOpen = true
    }
    @JvmStatic fun closeRuntime() {
        runtimeOpen = false
        resetRuntime()
    }
    private fun resetRuntime() {
        val generation = epoch.incrementAndGet()
        // Close synchronously to unblock guests even if Android is busy destroying its host.
        for (player in players) if (player.runtimeGeneration < generation) player.close()
        post {
            if (epoch.get() == generation) {
                abortRecording("Camera session ended")
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
            abortRecording("Camera host changed")
            unbind()
            host = activity
            foreground = false
            hostHasResumed = false
        }
        if (displayManager == null) {
            displayManager = activity.applicationContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            displayManager!!.registerDisplayListener(displayListener, main)
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
        abortRecording("Camera host is not active")
        unbind()
        finishSwitch("Camera host is not active")
        notifyMenu()
    }
    @JvmStatic fun onHostDestroyed(activity: MicroActivity) {
        if (host !== activity) return
        onHostPaused(activity)
        failPending(MediaException("Camera host was destroyed"))
        host = null
        displayManager?.unregisterDisplayListener(displayListener)
        displayManager = null
        permission = null
        refresh = null
        requestingPermission = false
    }
    @JvmStatic fun onHostConfigurationChanged(activity: MicroActivity) {
        if (host !== activity) return
        if (recordingSession == null) {
            unbind()
            drain()
        }
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
                if (capturing != null || switchFailure != null || recordingSession != null)
                    call.fail(MediaException("Camera is busy"))
                else enqueue(Pending(player, call) { capture(it, call, options) })
            }
        }
    }
    /** All CameraX binding and Recording objects stay on the Android main thread. */
    internal fun beginRecording(player: CameraCapturePlayer, file: File, microphone: Boolean) {
        val call = CameraCall<Unit>(40_000)
        player.waitFor(call) {
            post {
                if (!valid(player) || owner !== player || !foreground ||
                    recordingSession != null || capturing != null) {
                    call.fail(MediaException("Camera is unavailable for recording"))
                } else {
                    recordingSession = VideoSession(player, file, microphone, call)
                    unbind()
                    drain()
                }
            }
        }
    }

    internal fun pauseRecording(player: CameraCapturePlayer) = recordingCommand(player) { it.pause() }
    internal fun resumeRecording(player: CameraCapturePlayer) = recordingCommand(player) { it.resume() }

    private fun recordingCommand(player: CameraCapturePlayer, operation: (Recording) -> Unit) {
        val call = CameraCall<Unit>(10_000)
        player.waitFor(call) {
            post {
                val session = recordingSession
                val active = session?.recorder
                if (session?.player !== player || active == null || session.problem != null) {
                    call.fail(MediaException("Video recording is no longer active"))
                } else {
                    try { operation(active); call.complete(Unit) }
                    catch (failure: Exception) { call.fail(cameraFailure(failure)) }
                }
            }
        }
    }

    internal fun finishRecording(player: CameraCapturePlayer) {
        val call = CameraCall<Unit>(45_000)
        player.waitFor(call) {
            post {
                val session = recordingSession
                if (session?.player !== player) {
                    call.fail(MediaException("Video recording no longer exists"))
                } else {
                    session.finalCall = call
                    if (session.finalized) completeVideo(session)
                    else try { session.recorder?.stop()
                        ?: call.fail(MediaException("Video recorder never started"))
                    } catch (failure: Exception) { call.fail(cameraFailure(failure)) }
                }
            }
        }
    }

    internal fun abandonRecording(player: CameraCapturePlayer) = post {
        if (recordingSession?.player === player) {
            abortRecording("Recording abandoned")
            unbind()
            drain()
        }
    }

    private fun startVideo(current: Binding) {
        val session = recordingSession ?: return
        if (session.launched || current.player !== session.player) return
        val video = current.video ?: return
        val activity = host ?: return
        session.launched = true
        try {
            val output = FileOutputOptions.Builder(session.file)
                .setFileSizeLimit(64L * 1024 * 1024).build()
            var pending = video.output.prepareRecording(activity, output)
            if (session.microphone) {
                if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) !=
                    PackageManager.PERMISSION_GRANTED) throw SecurityException("Microphone permission revoked")
                pending = pending.withAudioEnabled()
            }
            session.recorder = pending.start(ContextCompat.getMainExecutor(activity)) { event ->
                if (recordingSession !== session) return@start
                when (event) {
                    is VideoRecordEvent.Start -> session.started.complete(Unit)
                    is VideoRecordEvent.Finalize -> {
                        session.finalized = true
                        if (event.hasError()) session.problem = MediaException(
                            "Video recording failed: " + event.error)
                        if (!session.started.isComplete) session.started.fail(
                            session.problem ?: MediaException("Recording ended before starting"))
                        if (session.finalCall == null && session.problem != null) {
                            session.player.postEvent(javax.microedition.media.PlayerListener.RECORD_ERROR,
                                session.problem!!.message)
                        }
                        if (session.finalCall != null) completeVideo(session)
                    }
                }
            }
        } catch (failure: Exception) {
            session.problem = MediaException("Video recorder failed: " + failure.message)
            session.started.fail(session.problem!!)
            session.finalCall?.fail(session.problem!!)
            recordingSession = null
            unbind()
            drain()
        }
    }

    private fun completeVideo(session: VideoSession) {
        val call = session.finalCall
        val error = session.problem
        if (error != null) call?.fail(error) else call?.complete(Unit)
        if (recordingSession === session) {
            recordingSession = null
            unbind()
            drain()
        }
    }

    private fun abortRecording(reason: String) {
        val session = recordingSession ?: return
        recordingSession = null
        val failure = MediaException(reason)
        session.started.fail(failure)
        session.finalCall?.fail(failure)
        try { session.recorder?.close() } catch (_: Exception) {}
        if (session.launched) {
            session.player.postEvent(javax.microedition.media.PlayerListener.RECORD_ERROR, reason)
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
        if (capturing?.isComplete == true && recordingSession == null) unbind()
        if (owner?.let { !valid(it) || (it.getState() < Player.PREFETCHED && pending.isEmpty()) } == true) {
            abortRecording("Camera owner released")
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
                abortRecording("Camera Player released")
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
            startVideo(current)
            val ready = pending.toList()
            pending.clear()
            for (request in ready) {
                if (!request.call.isComplete && valid(request.player)) {
                    try { request.ready(current) }
                    catch (failure: Exception) {
                        request.call.fail(if (request.player.getState() == Player.CLOSED) failure else cameraFailure(failure))
                    }
                }
            }
            notifyMenu()
        } catch (failure: Exception) { backendFailed(failure) }
    }

    private fun selector(facing: Int) = CameraSelector.Builder().requireLensFacing(facing).build()
    private fun facing(player: CameraCapturePlayer, cameras: ProcessCameraProvider): Int {
        val requested = overrideFacing ?: player.source.facing
        return CameraCaptureOptions.selectFacing(requested, player.defaultFacing,
            cameras.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA),
            cameras.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA))
    }

    private fun bind(cameras: ProcessCameraProvider, activity: MicroActivity, player: CameraCapturePlayer) {
        val lens = facing(player, cameras)
        val container = player.previewContainer?.takeIf { it.isAttachedToWindow && it.isShown }
        // A new View starts at IDLE. Reusing PreviewView can expose its old STREAMING value
        // before the new camera has produced a frame, especially during a lens switch.
        val view = container?.let {
            newPreview(it.context).also { preview ->
                it.foreground = ColorDrawable(Color.BLACK)
                it.removeAllViews()
                it.addView(preview, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
            }
        }
        @Suppress("DEPRECATION")
        val rotation = activity.windowManager.defaultDisplay.rotation
        val resolution = ResolutionSelector.Builder()
            .setResolutionStrategy(ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
            .setResolutionFilter { sizes, _ -> sizes.filter { it.width.toLong() * it.height <= 4_194_304L } }
            .build()
        val video = if (recordingSession?.player === player) VideoCapture.withOutput(
            Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.LOWEST)).build()
        ) else null
        val capture = if (video == null) ImageCapture.Builder().setResolutionSelector(resolution)
            .setTargetRotation(rotation).setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setOutputFormat(ImageCapture.OUTPUT_FORMAT_JPEG).build() else null
        val preview = view?.let { Preview.Builder().setResolutionSelector(resolution).setTargetRotation(rotation).build() }
        val next = Binding(player, view, lens, rotation, capture, preview, video)
        val group = UseCaseGroup.Builder().setViewPort(ViewPort.Builder(
            Rational(player.source.width, player.source.height), rotation).setScaleType(ViewPort.FILL_CENTER).build())
        if (capture != null) group.addUseCase(capture)
        if (video != null) group.addUseCase(video)
        if (preview != null && view != null) {
            // PreviewView mirrors front cameras by default, including pre-API33. Cancel that
            // display transform so the guest and its encoded image both see physical pixels.
            view.scaleX = if (lens == CameraSelector.LENS_FACING_FRONT) -1f else 1f
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
            (old.view?.parent as? FrameLayout)?.foreground = ColorDrawable(Color.BLACK)
            (old.view?.parent as? FrameLayout)?.removeView(old.view)
            old.camera.cameraInfo.cameraState.removeObserver(old.cameraObserver)
            old.streamObserver?.let { old.view?.previewStreamState?.removeObserver(it) }
            val cases = listOfNotNull<UseCase>(old.capture, old.preview, old.video)
            if (cases.isNotEmpty()) provider?.unbind(*cases.toTypedArray())
        }
    }
    private fun backendFailed(failure: Exception) {
        val player = owner
        abortRecording(failure.message ?: "Camera backend failed")
        unbind()
        failPending(cameraFailure(failure))
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
    private fun cameraFailure(failure: Exception): Exception =
        if (failure is MediaException || failure is SecurityException) failure
        else MediaException("Camera operation failed: ${failure.message}")

    private fun failPending(failure: Exception) {
        for (request in pending) request.call.fail(failure)
        pending.clear()
    }
    private fun updatePreviewVisibility() {
        val current = binding ?: return
        // Preserve TextureView's normal draw/update path under the cover: STREAMING
        // depends on its frame-update callback, not just the arrival of a camera buffer.
        (current.view?.parent as? FrameLayout)?.foreground =
            if (current.ready && current.player.getState() == Player.STARTED) null else ColorDrawable(Color.BLACK)
    }

    private fun newPreview(context: Context) = PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        // The parent fits this View to the ViewPort aspect. FIT_CENTER on a larger,
        // differently shaped View can expose buffer pixels outside the shared crop.
        scaleType = PreviewView.ScaleType.FILL_CENTER
        setBackgroundColor(Color.BLACK)
        isClickable = false
        isFocusable = false
    }
    internal fun createPreview(player: CameraCapturePlayer, context: Context): View {
        check(Looper.myLooper() == Looper.getMainLooper())
        val container = CameraPreviewContainer(context, player.source.width, player.source.height)
            .apply { setBackgroundColor(Color.BLACK) }
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
        if (owner === player && recordingSession == null) {
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
        val still = current.capture
        if (still == null) { call.fail(MediaException("Snapshot unavailable during video recording")); return }
        still.takePicture(worker, object : ImageCapture.OnImageCapturedCallback() {
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
        call.fail(cameraFailure(failure))
        if (capturing === call) capturing = null
        notifyMenu()
    }

    @JvmStatic fun canSwitchCamera(): Boolean {
        val cameras = provider ?: return false
        val player = binding?.player ?: owner?.takeIf { switchFailure != null } ?: return false
        return try {
            recordingSession == null && valid(player) && foreground &&
                cameras.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) && cameras.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
        } catch (_: Exception) { false }
    }
    @JvmStatic fun isFrontCamera() = (binding?.facing ?: overrideFacing) == CameraSelector.LENS_FACING_FRONT
    @JvmStatic fun isSwitchingCamera() = recordingSession != null || switchFailure != null || capturing != null || binding?.ready == false
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
