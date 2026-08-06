package de.creatorhub.live

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.OrientationEventListener
import android.view.SurfaceHolder
import android.view.View
import android.widget.ArrayAdapter
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.video.CameraCallbacks
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.library.rtmp.RtmpCamera2
import de.creatorhub.live.databinding.ActivityMainBinding
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity(), ConnectChecker {

    private data class CameraProfile(
        val width: Int,
        val height: Int,
        val fps: Int
    )

    private lateinit var binding: ActivityMainBinding
    private lateinit var audioManager: AudioManager
    private lateinit var orientationListener: OrientationEventListener

    private val uiHandler = Handler(Looper.getMainLooper())
    private var rtmpCamera: RtmpCamera2? = null
    private var previewReady = false
    private var previewStarting = false
    private var activityActive = false
    private var currentRotation = 0
    private var currentCameraFacing = CameraHelper.Facing.BACK
    private var cameraSwitchInProgress = false
    private var selectedCameraSource = 0
    private var streamStartedAt = 0L
    private var recordingRequested = false

    private val durationUpdater = object : Runnable {
        override fun run() {
            if (streamStartedAt > 0L) {
                val elapsed = SystemClock.elapsedRealtime() - streamStartedAt
                val totalSeconds = elapsed / 1000
                val hours = totalSeconds / 3600
                val minutes = (totalSeconds % 3600) / 60
                val seconds = totalSeconds % 60
                binding.durationText.text = String.format(
                    Locale.US,
                    "%02d:%02d:%02d",
                    hours,
                    minutes,
                    seconds
                )
                uiHandler.postDelayed(this, 1000)
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        when {
            result[Manifest.permission.CAMERA] != true -> status("Kameraberechtigung fehlt")
            result[Manifest.permission.RECORD_AUDIO] != true -> {
                status("Mikrofonberechtigung fehlt")
                startPreviewIfPossible()
            }
            else -> startPreviewIfPossible()
        }
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        status(
            if (result.resultCode == RESULT_OK) {
                "Bildschirmfreigabe erteilt · gemeinsame Kamera-/Bildschirmansicht ist vorbereitet"
            } else {
                "Bildschirmaufnahme abgebrochen"
            }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        currentRotation = CameraHelper.getCameraOrientation(this)
        audioManager = getSystemService(AudioManager::class.java)
        rtmpCamera = runCatching { RtmpCamera2(binding.openGlView, this) }
            .onFailure { status("Kameramodul konnte nicht initialisiert werden") }
            .getOrNull()

        rtmpCamera?.let { camera ->
            currentCameraFacing = runCatching { camera.getCameraFacing() }
                .getOrDefault(CameraHelper.Facing.BACK)
            configureCameraCallbacks(camera)
            applyCameraMirroring(camera)
        }

        configureOrientationSensor()
        configureSources()
        configurePreviewSurface()
        configureControls()
        refreshProfileAndQualityLabels()
        updateControlsForCameraState()
        requestPermissionsIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        activityActive = true
        currentRotation = CameraHelper.getCameraOrientation(this)
        if (::orientationListener.isInitialized && orientationListener.canDetectOrientation()) {
            orientationListener.enable()
        }
        refreshProfileAndQualityLabels()
        startPreviewIfPossible()
        showConfiguration()
    }

    override fun onPause() {
        activityActive = false
        cameraSwitchInProgress = false
        if (::orientationListener.isInitialized) orientationListener.disable()
        stopStreamSafely(showMessage = false)
        stopPreviewSafely()
        super.onPause()
    }

    private fun configureCameraCallbacks(camera: RtmpCamera2) {
        camera.setCameraCallbacks(object : CameraCallbacks {
            override fun onCameraChanged(facing: CameraHelper.Facing) {
                uiHandler.post {
                    currentCameraFacing = facing
                    cameraSwitchInProgress = false
                    applyCameraMirroring(camera)
                    updateSwitchCameraButton()
                    status("${cameraName(facing)} aktiv")
                }
            }

            override fun onCameraError(error: String) {
                uiHandler.post {
                    cameraSwitchInProgress = false
                    updateSwitchCameraButton()
                    setQuality("Fehler")
                    status("Kamerafehler: $error")
                }
            }

            override fun onCameraOpened() {
                uiHandler.post {
                    currentCameraFacing = runCatching { camera.getCameraFacing() }
                        .getOrDefault(currentCameraFacing)
                    cameraSwitchInProgress = false
                    applyCameraMirroring(camera)
                    updateSwitchCameraButton()
                }
            }

            override fun onCameraDisconnected() {
                uiHandler.post {
                    cameraSwitchInProgress = false
                    updateSwitchCameraButton()
                    if (activityActive) status("Kamera wurde getrennt")
                }
            }
        })
    }

    private fun configureOrientationSensor() {
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                currentRotation = CameraHelper.getCameraOrientation(this@MainActivity)
            }
        }
    }

    private fun configureSources() {
        val sources = listOf(
            "Handykamera",
            "Bildschirmfreigabe",
            "Kamera + Bildschirm",
            "USB-/HDMI-Capture"
        )
        binding.sourceSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            sources
        )
        binding.sourceSpinner.onItemSelectedListener = SimpleItemSelectedListener { position ->
            selectedCameraSource = position
            updateSwitchCameraButton()
        }
    }

    private fun configurePreviewSurface() {
        binding.openGlView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                previewReady = true
                startPreviewIfPossible()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                previewReady = true
                startPreviewIfPossible()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                previewReady = false
                previewStarting = false
                cameraSwitchInProgress = false
                stopPreviewSafely()
            }
        })
    }

    private fun configureControls() {
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.voiceButton.setOnClickListener {
            startActivity(Intent(this, ChatActivity::class.java))
        }
        binding.accountButton.setOnClickListener {
            status("TikTok-Konto ist vorbereitet · OAuth wird erst mit offizieller App-Freigabe aktiviert")
        }
        binding.toolsButton.setOnClickListener {
            toggleCreatorTools()
        }
        binding.startButton.setOnClickListener {
            when (selectedCameraSource) {
                1, 2 -> requestScreenCapture()
                3 -> showUsbCaptureStatus()
                else -> toggleCameraStream()
            }
        }
        binding.recordButton.setOnClickListener {
            recordingRequested = !recordingRequested
            binding.recordButton.text = if (recordingRequested) "REC AN" else "REC"
            status(
                if (recordingRequested) "Lokale Aufnahme für den nächsten Stream aktiviert"
                else "Lokale Aufnahme deaktiviert"
            )
        }
        binding.switchCameraButton.setOnClickListener {
            switchCameraSafely()
        }
        binding.micSwitch.setOnCheckedChangeListener { _, enabled ->
            val camera = rtmpCamera
            if (camera?.isStreaming == true) {
                runCatching { if (enabled) camera.enableAudio() else camera.disableAudio() }
            }
            binding.audioMeter.progress =
                if (enabled) binding.micVolume.progress.coerceIn(0, 100) else 0
            status(if (enabled) "Mikrofon aktiv" else "Mikrofon stumm")
        }
        binding.micVolume.setOnSeekBarChangeListener(simpleSeekListener { progress ->
            binding.audioMeter.progress = progress.coerceIn(0, 100)
            status("Mikrofonpegel: $progress %")
        })
        binding.deviceVolume.setOnSeekBarChangeListener(simpleSeekListener { progress ->
            runCatching {
                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                audioManager.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    ((progress.coerceIn(0, 100) / 100f) * max).toInt(),
                    0
                )
            }
        })
    }

    private fun switchCameraSafely() {
        val camera = rtmpCamera ?: return status("Kameramodul ist nicht verfügbar")
        if (selectedCameraSource != 0) return status("Kamerawechsel ist nur bei Handykamera verfügbar")
        if (cameraSwitchInProgress) return

        val targetFacing = if (currentCameraFacing == CameraHelper.Facing.FRONT) {
            CameraHelper.Facing.BACK
        } else {
            CameraHelper.Facing.FRONT
        }

        if (cameraSizes(camera, targetFacing).isEmpty()) {
            status("${cameraName(targetFacing)} ist auf diesem Gerät nicht verfügbar")
            return
        }

        cameraSwitchInProgress = true
        updateSwitchCameraButton()
        status("Wechsle zu ${cameraName(targetFacing)} …")

        runCatching { camera.switchCamera() }
            .onFailure { error ->
                cameraSwitchInProgress = false
                applyCameraMirroring(camera)
                updateSwitchCameraButton()
                status("Kamerawechsel fehlgeschlagen: ${error.message ?: "Unbekannter Fehler"}")
                recoverPreviewAfterCameraError(camera)
            }

        uiHandler.postDelayed({
            if (!cameraSwitchInProgress) return@postDelayed

            val actualFacing = runCatching { camera.getCameraFacing() }
                .getOrDefault(currentCameraFacing)
            currentCameraFacing = actualFacing
            cameraSwitchInProgress = false
            applyCameraMirroring(camera)
            updateSwitchCameraButton()

            status(
                if (actualFacing == targetFacing) {
                    "${cameraName(actualFacing)} aktiv"
                } else {
                    "Kamerawechsel konnte nicht bestätigt werden"
                }
            )
        }, 2500L)
    }

    private fun recoverPreviewAfterCameraError(camera: RtmpCamera2) {
        if (camera.isStreaming) return
        runCatching {
            if (camera.isOnPreview) camera.stopPreview()
        }
        uiHandler.postDelayed({ startPreviewIfPossible() }, 250L)
    }

    private fun toggleCreatorTools() {
        val toolsVisible = binding.overlayText.visibility != View.VISIBLE
        val prefs = getSharedPreferences("live_settings", MODE_PRIVATE)
        binding.overlayLogo.visibility =
            if (prefs.getBoolean("overlay_logo", true)) View.VISIBLE else View.GONE
        binding.overlayText.visibility = if (toolsVisible) View.VISIBLE else View.GONE
        binding.giftOverlay.visibility = View.GONE
        status(if (toolsVisible) "Creator-Overlay eingeblendet" else "Creator-Overlay ausgeblendet")
    }

    private fun refreshProfileAndQualityLabels() {
        val prefs = getSharedPreferences("stream_profile", MODE_PRIVATE)
        val resolution = prefs.getString("resolution", "720p") ?: "720p"
        val fps = prefs.getInt("fps", 30)
        val bitrateMode = prefs.getString("bitrate_mode", "Automatisch") ?: "Automatisch"
        val bitrate = prefs.getInt("bitrate_kbps", if (resolution == "1080p") 6000 else 3000)
        binding.resolutionText.text = "$resolution · $fps FPS"
        binding.bitrateText.text =
            if (bitrateMode == "Automatisch") "AUTO" else "$bitrate kbit/s"
    }

    private fun updateControlsForCameraState() {
        val available = rtmpCamera != null
        binding.startButton.isEnabled = available
        updateSwitchCameraButton()
        if (!available) status("Kameramodul ist auf diesem Gerät nicht verfügbar")
    }

    private fun updateSwitchCameraButton() {
        val camera = rtmpCamera
        val targetFacing = if (currentCameraFacing == CameraHelper.Facing.FRONT) {
            CameraHelper.Facing.BACK
        } else {
            CameraHelper.Facing.FRONT
        }
        val targetAvailable = camera != null && cameraSizes(camera, targetFacing).isNotEmpty()

        binding.switchCameraButton.isEnabled =
            camera != null &&
                selectedCameraSource == 0 &&
                !cameraSwitchInProgress &&
                targetAvailable
    }

    private fun startPreviewIfPossible() {
        val camera = rtmpCamera ?: return
        if (!activityActive || !previewReady || previewStarting) return
        if (!hasPermission(Manifest.permission.CAMERA)) return
        if (camera.isStreaming || camera.isOnPreview) return

        previewStarting = true
        binding.openGlView.post {
            currentRotation = CameraHelper.getCameraOrientation(this)
            val previewProfile = chooseCameraProfile(
                camera = camera,
                facing = currentCameraFacing,
                requestedWidth = 1280,
                requestedHeight = 720,
                requestedFps = 30
            )
            applyCameraMirroring(camera)

            runCatching {
                camera.startPreview(
                    currentCameraFacing,
                    previewProfile.width,
                    previewProfile.height,
                    previewProfile.fps,
                    currentRotation
                )
            }.onSuccess {
                currentCameraFacing = runCatching { camera.getCameraFacing() }
                    .getOrDefault(currentCameraFacing)
                applyCameraMirroring(camera)
                updateSwitchCameraButton()
                status("${cameraName(currentCameraFacing)} bereit")
                setQuality("Bereit")
            }.onFailure { error ->
                status(
                    "Kameravorschau konnte nicht gestartet werden: " +
                        (error.message ?: "Unbekannter Fehler")
                )
                setQuality("Fehler")
            }

            previewStarting = false
        }
    }

    private fun stopPreviewSafely() {
        previewStarting = false
        rtmpCamera?.let { camera ->
            runCatching {
                if (camera.isOnPreview && !camera.isStreaming) camera.stopPreview()
            }
        }
    }

    private fun toggleCameraStream() {
        val camera = rtmpCamera ?: return status("Kameramodul ist nicht verfügbar")
        if (camera.isStreaming) {
            stopStreamSafely(showMessage = true)
            startPreviewIfPossible()
            return
        }

        if (!hasPermission(Manifest.permission.CAMERA) ||
            !hasPermission(Manifest.permission.RECORD_AUDIO)
        ) {
            requestPermissionsIfNeeded()
            return
        }

        val streamPrefs = getSharedPreferences("stream", MODE_PRIVATE)
        val server = streamPrefs.getString("server", "").orEmpty().trim().trimEnd('/')
        val key = streamPrefs.getString("key", "").orEmpty().trim().trimStart('/')
        if (server.isBlank() || key.isBlank()) {
            return status("RTMP-Server und Stream-Key in den Einstellungen speichern")
        }
        if (!server.startsWith("rtmp://") && !server.startsWith("rtmps://")) {
            return status("Gespeicherter RTMP-Server ist ungültig")
        }

        val profile = getSharedPreferences("stream_profile", MODE_PRIVATE)
        val resolution = profile.getString("resolution", "720p") ?: "720p"
        val requestedFps = profile.getInt("fps", 30).coerceIn(30, 60)
        val bitrateMode = profile.getString("bitrate_mode", "Automatisch") ?: "Automatisch"
        val requestedWidth = if (resolution == "1080p") 1920 else 1280
        val requestedHeight = if (resolution == "1080p") 1080 else 720
        val cameraProfile = chooseCameraProfile(
            camera = camera,
            facing = currentCameraFacing,
            requestedWidth = requestedWidth,
            requestedHeight = requestedHeight,
            requestedFps = requestedFps
        )

        val bitrateKbps = if (bitrateMode == "Automatisch") {
            if (resolution == "1080p") {
                if (cameraProfile.fps >= 60) 8000 else 6000
            } else {
                if (cameraProfile.fps >= 60) 4500 else 3000
            }
        } else {
            profile.getInt("bitrate_kbps", 3000).coerceIn(1000, 12000)
        }

        currentRotation = CameraHelper.getCameraOrientation(this)
        applyCameraMirroring(camera)

        val videoPrepared = runCatching {
            camera.prepareVideo(
                cameraProfile.width,
                cameraProfile.height,
                cameraProfile.fps,
                bitrateKbps * 1000,
                2,
                currentRotation
            )
        }.getOrDefault(false)
        if (!videoPrepared) {
            return status(
                "Video-Encoder konnte für ${cameraName(currentCameraFacing)} nicht vorbereitet werden"
            )
        }

        val audioPrepared = runCatching {
            camera.prepareAudio(128_000, 44_100, true, false, false)
        }.getOrDefault(false)
        if (!audioPrepared) return status("Audio-Encoder konnte nicht vorbereitet werden")

        runCatching { camera.startStream("$server/$key") }
            .onSuccess {
                if (!binding.micSwitch.isChecked) {
                    runCatching { camera.disableAudio() }
                }
                binding.startButton.text = "■  LIVE STOPPEN"
                streamStartedAt = SystemClock.elapsedRealtime()
                uiHandler.removeCallbacks(durationUpdater)
                uiHandler.post(durationUpdater)
                setQuality("Verbinde")
                status(
                    "Verbindung wird aufgebaut · ${cameraName(currentCameraFacing)} · " +
                        "${cameraProfile.width}×${cameraProfile.height} · ${cameraProfile.fps} FPS"
                )
            }
            .onFailure {
                binding.startButton.text = "●  LIVE STARTEN"
                setQuality("Fehler")
                status("Streamstart fehlgeschlagen: ${it.message ?: "Unbekannter Fehler"}")
            }
    }

    private fun chooseCameraProfile(
        camera: RtmpCamera2,
        facing: CameraHelper.Facing,
        requestedWidth: Int,
        requestedHeight: Int,
        requestedFps: Int
    ): CameraProfile {
        val sizes = cameraSizes(camera, facing)
        val requestedLongSide = maxOf(requestedWidth, requestedHeight)
        val requestedShortSide = minOf(requestedWidth, requestedHeight)
        val requestedArea = requestedWidth.toLong() * requestedHeight.toLong()
        val requestedRatio = requestedLongSide.toDouble() / requestedShortSide.toDouble()

        val exactSize = sizes.firstOrNull { size ->
            maxOf(size.width, size.height) == requestedLongSide &&
                minOf(size.width, size.height) == requestedShortSide
        }
        val matchingRatioSizes = sizes.filter { size ->
            val shortSide = minOf(size.width, size.height)
            if (shortSide <= 0) {
                false
            } else {
                val ratio = maxOf(size.width, size.height).toDouble() / shortSide.toDouble()
                abs(ratio - requestedRatio) <= 0.08
            }
        }
        val selectedSize = exactSize
            ?: matchingRatioSizes.minByOrNull { size ->
                abs(size.width.toLong() * size.height.toLong() - requestedArea)
            }
            ?: sizes.minByOrNull { size ->
                abs(size.width.toLong() * size.height.toLong() - requestedArea)
            }

        val width = selectedSize?.width ?: requestedWidth
        val height = selectedSize?.height ?: requestedHeight
        val supportedFps = runCatching {
            camera.getSupportedFps(Size(width, height), facing)
        }.getOrDefault(emptyList())

        val fps = when {
            supportedFps.isEmpty() -> requestedFps
            supportedFps.any { range ->
                requestedFps >= range.lower && requestedFps <= range.upper
            } -> requestedFps
            supportedFps.any { range -> 30 >= range.lower && 30 <= range.upper } -> 30
            else -> supportedFps
                .flatMap { range -> listOf(range.lower, range.upper) }
                .filter { value -> value > 0 }
                .minByOrNull { value -> abs(value - requestedFps) }
                ?.coerceIn(15, 60)
                ?: requestedFps
        }

        return CameraProfile(width = width, height = height, fps = fps)
    }

    private fun cameraSizes(
        camera: RtmpCamera2,
        facing: CameraHelper.Facing
    ): List<Size> = runCatching {
        if (facing == CameraHelper.Facing.FRONT) {
            camera.getResolutionsFront()
        } else {
            camera.getResolutionsBack()
        }
    }.getOrDefault(emptyList())

    private fun applyCameraMirroring(camera: RtmpCamera2) {
        val frontCamera = currentCameraFacing == CameraHelper.Facing.FRONT
        runCatching {
            camera.getGlInterface().setIsPreviewHorizontalFlip(frontCamera)
            camera.getGlInterface().setIsStreamHorizontalFlip(false)
        }
    }

    private fun cameraName(facing: CameraHelper.Facing): String =
        if (facing == CameraHelper.Facing.FRONT) "Frontkamera" else "Rückkamera"

    private fun stopStreamSafely(showMessage: Boolean) {
        rtmpCamera?.let { camera ->
            runCatching {
                if (camera.isStreaming) camera.stopStream()
            }
        }
        streamStartedAt = 0L
        uiHandler.removeCallbacks(durationUpdater)
        binding.durationText.text = "00:00:00"
        binding.startButton.text = "●  LIVE STARTEN"
        setQuality("Bereit")
        if (showMessage) status("Stream beendet")
    }

    private fun setQuality(text: String) {
        if (!::binding.isInitialized || isDestroyed) return
        runOnUiThread { binding.qualityText.text = text }
    }

    private fun requestScreenCapture() {
        runCatching {
            screenCaptureLauncher.launch(
                getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()
            )
        }.onFailure { status("Bildschirmfreigabe konnte nicht geöffnet werden") }
    }

    private fun showUsbCaptureStatus() {
        val usbName = getSharedPreferences("live_settings", MODE_PRIVATE)
            .getString("usb_device_name", "")
            .orEmpty()
        status(
            if (usbName.isBlank()) {
                "USB-/HDMI-Gerät zuerst in den Einstellungen auswählen"
            } else {
                "USB-Gerät ausgewählt: $usbName"
            }
        )
    }

    private fun requestPermissionsIfNeeded() {
        val missing = buildList {
            if (!hasPermission(Manifest.permission.CAMERA)) add(Manifest.permission.CAMERA)
            if (!hasPermission(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
        }
        if (missing.isEmpty()) {
            startPreviewIfPossible()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun showConfiguration() {
        if (rtmpCamera?.isStreaming == true) return
        val prefs = getSharedPreferences("stream", MODE_PRIVATE)
        status(
            if (!prefs.getString("server", "").isNullOrBlank() &&
                !prefs.getString("key", "").isNullOrBlank()
            ) {
                "Stream eingerichtet · Creator Hub bereit"
            } else {
                "RTMP-Verbindung noch nicht eingerichtet"
            }
        )
    }

    private fun simpleSeekListener(action: (Int) -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: SeekBar?,
                progress: Int,
                fromUser: Boolean
            ) {
                if (fromUser) action(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }

    private fun status(text: String) {
        if (isDestroyed || isFinishing || !::binding.isInitialized) return
        runOnUiThread {
            binding.statusText.text = text
            binding.networkText.text = text
        }
    }

    override fun onConnectionStarted(url: String) {
        setQuality("Verbinde")
        status("RTMP-Verbindung wird hergestellt")
    }

    override fun onConnectionSuccess() {
        runOnUiThread {
            setQuality("Sehr gut")
            binding.startButton.text = "■  LIVE STOPPEN"
            status("LIVE · Verbindung steht")
        }
    }

    override fun onConnectionFailed(reason: String) {
        runOnUiThread {
            stopStreamSafely(showMessage = false)
            setQuality("Fehler")
            status("Verbindung fehlgeschlagen: $reason")
            startPreviewIfPossible()
        }
    }

    override fun onNewBitrate(bitrate: Long) {
        runOnUiThread {
            val kbps = bitrate / 1000
            binding.bitrateText.text = "$kbps kbit/s"
            setQuality(
                if (kbps >= 2500) "Sehr gut"
                else if (kbps >= 1200) "Mittel"
                else "Schwach"
            )
            status("LIVE · Upload $kbps kbit/s")
        }
    }

    override fun onDisconnect() {
        runOnUiThread {
            stopStreamSafely(showMessage = false)
            status("Verbindung getrennt")
            startPreviewIfPossible()
        }
    }

    override fun onAuthError() {
        setQuality("Key-Fehler")
        status("Stream-Key wurde abgelehnt")
    }

    override fun onAuthSuccess() = status("Authentifizierung erfolgreich")

    override fun onDestroy() {
        activityActive = false
        cameraSwitchInProgress = false
        uiHandler.removeCallbacksAndMessages(null)
        if (::orientationListener.isInitialized) orientationListener.disable()
        rtmpCamera?.let { camera ->
            runCatching { if (camera.isStreaming) camera.stopStream() }
            runCatching { if (camera.isOnPreview) camera.stopPreview() }
        }
        rtmpCamera = null
        super.onDestroy()
    }
}
