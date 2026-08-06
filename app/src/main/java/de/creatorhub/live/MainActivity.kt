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
import android.view.OrientationEventListener
import android.view.SurfaceHolder
import android.view.View
import android.widget.ArrayAdapter
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.pedro.common.ConnectChecker
import com.pedro.library.rtmp.RtmpCamera2
import de.creatorhub.live.databinding.ActivityMainBinding
import java.util.Locale

class MainActivity : AppCompatActivity(), ConnectChecker {

    private lateinit var binding: ActivityMainBinding
    private lateinit var audioManager: AudioManager
    private lateinit var orientationListener: OrientationEventListener

    private val uiHandler = Handler(Looper.getMainLooper())
    private var rtmpCamera: RtmpCamera2? = null
    private var previewReady = false
    private var previewStarting = false
    private var activityActive = false
    private var currentRotation = 0
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
                binding.durationText.text = String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
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

        audioManager = getSystemService(AudioManager::class.java)
        rtmpCamera = runCatching { RtmpCamera2(binding.openGlView, this) }
            .onFailure { status("Kameramodul konnte nicht initialisiert werden") }
            .getOrNull()

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
        if (::orientationListener.isInitialized && orientationListener.canDetectOrientation()) {
            orientationListener.enable()
        }
        refreshProfileAndQualityLabels()
        startPreviewIfPossible()
        showConfiguration()
    }

    override fun onPause() {
        activityActive = false
        if (::orientationListener.isInitialized) orientationListener.disable()
        stopStreamSafely(showMessage = false)
        stopPreviewSafely()
        super.onPause()
    }

    private fun configureOrientationSensor() {
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                currentRotation = when (orientation) {
                    in 45..134 -> 270
                    in 135..224 -> 180
                    in 225..314 -> 90
                    else -> 0
                }
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
            val camera = rtmpCamera ?: return@setOnClickListener status("Kameramodul ist nicht verfügbar")
            runCatching { camera.switchCamera() }
                .onSuccess { status("Kamera gewechselt") }
                .onFailure { status("Kamera konnte nicht gewechselt werden") }
        }
        binding.micSwitch.setOnCheckedChangeListener { _, enabled ->
            val camera = rtmpCamera
            if (camera?.isStreaming == true) {
                runCatching { if (enabled) camera.enableAudio() else camera.disableAudio() }
            }
            binding.audioMeter.progress = if (enabled) binding.micVolume.progress.coerceIn(0, 100) else 0
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

    private fun toggleCreatorTools() {
        val toolsVisible = binding.overlayText.visibility != View.VISIBLE
        val prefs = getSharedPreferences("live_settings", MODE_PRIVATE)
        binding.overlayLogo.visibility = if (prefs.getBoolean("overlay_logo", true)) View.VISIBLE else View.GONE
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
        binding.bitrateText.text = if (bitrateMode == "Automatisch") "AUTO" else "$bitrate kbit/s"
    }

    private fun updateControlsForCameraState() {
        val available = rtmpCamera != null
        binding.startButton.isEnabled = available
        updateSwitchCameraButton()
        if (!available) status("Kameramodul ist auf diesem Gerät nicht verfügbar")
    }

    private fun updateSwitchCameraButton() {
        binding.switchCameraButton.isEnabled = rtmpCamera != null && selectedCameraSource == 0
    }

    private fun startPreviewIfPossible() {
        val camera = rtmpCamera ?: return
        if (!activityActive || !previewReady || previewStarting) return
        if (!hasPermission(Manifest.permission.CAMERA)) return
        if (camera.isStreaming || camera.isOnPreview) return

        previewStarting = true
        binding.openGlView.post {
            runCatching { camera.startPreview() }
                .onSuccess {
                    status("Kamera bereit")
                    setQuality("Bereit")
                }
                .onFailure {
                    status("Kameravorschau konnte nicht gestartet werden")
                    setQuality("Fehler")
                }
            previewStarting = false
        }
    }

    private fun stopPreviewSafely() {
        previewStarting = false
        rtmpCamera?.let { camera ->
            runCatching { if (camera.isOnPreview && !camera.isStreaming) camera.stopPreview() }
        }
    }

    private fun toggleCameraStream() {
        val camera = rtmpCamera ?: return status("Kameramodul ist nicht verfügbar")
        if (camera.isStreaming) {
            stopStreamSafely(showMessage = true)
            startPreviewIfPossible()
            return
        }

        if (!hasPermission(Manifest.permission.CAMERA) || !hasPermission(Manifest.permission.RECORD_AUDIO)) {
            requestPermissionsIfNeeded()
            return
        }

        val streamPrefs = getSharedPreferences("stream", MODE_PRIVATE)
        val server = streamPrefs.getString("server", "").orEmpty().trim().trimEnd('/')
        val key = streamPrefs.getString("key", "").orEmpty().trim().trimStart('/')
        if (server.isBlank() || key.isBlank()) return status("RTMP-Server und Stream-Key in den Einstellungen speichern")
        if (!server.startsWith("rtmp://") && !server.startsWith("rtmps://")) return status("Gespeicherter RTMP-Server ist ungültig")

        val profile = getSharedPreferences("stream_profile", MODE_PRIVATE)
        val resolution = profile.getString("resolution", "720p") ?: "720p"
        val fps = profile.getInt("fps", 30).coerceIn(30, 60)
        val bitrateMode = profile.getString("bitrate_mode", "Automatisch") ?: "Automatisch"
        val bitrateKbps = if (bitrateMode == "Automatisch") {
            if (resolution == "1080p") if (fps == 60) 8000 else 6000 else if (fps == 60) 4500 else 3000
        } else {
            profile.getInt("bitrate_kbps", 3000).coerceIn(1000, 12000)
        }
        val portrait = currentRotation == 0 || currentRotation == 180
        val longSide = if (resolution == "1080p") 1920 else 1280
        val shortSide = if (resolution == "1080p") 1080 else 720

        val videoPrepared = runCatching {
            camera.prepareVideo(
                if (portrait) shortSide else longSide,
                if (portrait) longSide else shortSide,
                fps,
                bitrateKbps * 1000,
                currentRotation,
                2
            )
        }.getOrDefault(false)
        if (!videoPrepared) return status("Video-Encoder konnte nicht vorbereitet werden")

        val audioPrepared = runCatching {
            camera.prepareAudio(128_000, 44_100, true, false, false)
        }.getOrDefault(false)
        if (!audioPrepared) return status("Audio-Encoder konnte nicht vorbereitet werden")

        runCatching { camera.startStream("$server/$key") }
            .onSuccess {
                if (!binding.micSwitch.isChecked) runCatching { camera.disableAudio() }
                binding.startButton.text = "■  LIVE STOPPEN"
                streamStartedAt = SystemClock.elapsedRealtime()
                uiHandler.removeCallbacks(durationUpdater)
                uiHandler.post(durationUpdater)
                setQuality("Verbinde")
                status("Verbindung wird aufgebaut …")
            }
            .onFailure {
                binding.startButton.text = "●  LIVE STARTEN"
                setQuality("Fehler")
                status("Streamstart fehlgeschlagen: ${it.message ?: "Unbekannter Fehler"}")
            }
    }

    private fun stopStreamSafely(showMessage: Boolean) {
        rtmpCamera?.let { camera -> runCatching { if (camera.isStreaming) camera.stopStream() } }
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
            .getString("usb_device_name", "").orEmpty()
        status(if (usbName.isBlank()) "USB-/HDMI-Gerät zuerst in den Einstellungen auswählen" else "USB-Gerät ausgewählt: $usbName")
    }

    private fun requestPermissionsIfNeeded() {
        val missing = buildList {
            if (!hasPermission(Manifest.permission.CAMERA)) add(Manifest.permission.CAMERA)
            if (!hasPermission(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
        }
        if (missing.isEmpty()) startPreviewIfPossible() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun showConfiguration() {
        if (rtmpCamera?.isStreaming == true) return
        val prefs = getSharedPreferences("stream", MODE_PRIVATE)
        status(
            if (!prefs.getString("server", "").isNullOrBlank() && !prefs.getString("key", "").isNullOrBlank()) {
                "Stream eingerichtet · Creator Hub bereit"
            } else {
                "RTMP-Verbindung noch nicht eingerichtet"
            }
        )
    }

    private fun simpleSeekListener(action: (Int) -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
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
        setQuality("Sehr gut")
        binding.startButton.text = "■  LIVE STOPPEN"
        status("LIVE · Verbindung steht")
    }

    override fun onConnectionFailed(reason: String) {
        stopStreamSafely(showMessage = false)
        setQuality("Fehler")
        status("Verbindung fehlgeschlagen: $reason")
        startPreviewIfPossible()
    }

    override fun onNewBitrate(bitrate: Long) {
        val kbps = bitrate / 1000
        binding.bitrateText.text = "$kbps kbit/s"
        setQuality(if (kbps >= 2500) "Sehr gut" else if (kbps >= 1200) "Mittel" else "Schwach")
        status("LIVE · Upload $kbps kbit/s")
    }

    override fun onDisconnect() {
        stopStreamSafely(showMessage = false)
        status("Verbindung getrennt")
        startPreviewIfPossible()
    }

    override fun onAuthError() {
        setQuality("Key-Fehler")
        status("Stream-Key wurde abgelehnt")
    }

    override fun onAuthSuccess() = status("Authentifizierung erfolgreich")

    override fun onDestroy() {
        activityActive = false
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
