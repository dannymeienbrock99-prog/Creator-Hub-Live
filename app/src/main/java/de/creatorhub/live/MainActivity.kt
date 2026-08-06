package de.creatorhub.live

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.OrientationEventListener
import android.view.SurfaceHolder
import android.widget.ArrayAdapter
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.pedro.common.ConnectChecker
import com.pedro.library.rtmp.RtmpCamera2
import de.creatorhub.live.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), ConnectChecker {

    private lateinit var binding: ActivityMainBinding
    private lateinit var audioManager: AudioManager
    private lateinit var orientationListener: OrientationEventListener

    private var rtmpCamera: RtmpCamera2? = null
    private var previewReady = false
    private var previewStarting = false
    private var activityActive = false
    private var currentRotation = 0
    private var selectedCameraSource = 0

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
                "Bildschirmfreigabe erteilt · Bildschirmstreaming ist noch nicht aktiviert"
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
        audioManager = getSystemService(AudioManager::class.java)

        rtmpCamera = runCatching {
            RtmpCamera2(binding.openGlView, this)
        }.onFailure {
            status("Kameramodul konnte nicht initialisiert werden")
        }.getOrNull()

        configureOrientationSensor()
        configureSources()
        configurePreviewSurface()
        configureControls()
        updateControlsForCameraState()
        requestPermissionsIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        activityActive = true
        if (::orientationListener.isInitialized && orientationListener.canDetectOrientation()) {
            orientationListener.enable()
        }
        startPreviewIfPossible()
        showConfiguration()
    }

    override fun onPause() {
        activityActive = false
        if (::orientationListener.isInitialized) orientationListener.disable()

        // Ohne Foreground-Service darf Android die Kamera im Hintergrund schließen.
        // Ein kontrolliertes Beenden verhindert Kamera- und Encoder-Abstürze.
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
            "Handykamera (Rück-/Frontkamera)",
            "Handyspiel / Bildschirm",
            "TV / HDMI über USB-Capture"
        )
        binding.sourceSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            sources
        )
        binding.sourceSpinner.setSelection(0)
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

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) {
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

        binding.chatButton.setOnClickListener {
            startActivity(Intent(this, ChatActivity::class.java))
        }

        binding.startButton.setOnClickListener {
            when (selectedCameraSource) {
                1 -> requestScreenCapture()
                2 -> showUsbCaptureStatus()
                else -> toggleCameraStream()
            }
        }

        binding.switchCameraButton.setOnClickListener {
            val camera = rtmpCamera
            if (camera == null) {
                status("Kameramodul ist nicht verfügbar")
                return@setOnClickListener
            }
            runCatching { camera.switchCamera() }
                .onSuccess { status("Kamera gewechselt") }
                .onFailure { status("Kamera konnte nicht gewechselt werden") }
        }

        binding.micSwitch.setOnCheckedChangeListener { _, enabled ->
            val camera = rtmpCamera
            if (camera?.isStreaming == true) {
                runCatching {
                    if (enabled) camera.enableAudio() else camera.disableAudio()
                }.onFailure {
                    status("Mikrofon konnte nicht umgeschaltet werden")
                }
            } else {
                status(if (enabled) "Mikrofon für den nächsten Stream aktiviert" else "Mikrofon stumm")
            }
        }

        binding.micVolume.setOnSeekBarChangeListener(
            simpleSeekListener { progress ->
                status("Mikrofonpegel: $progress %")
            }
        )

        binding.deviceVolume.setOnSeekBarChangeListener(
            simpleSeekListener { progress ->
                runCatching {
                    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val target = ((progress.coerceIn(0, 100) / 100f) * max).toInt()
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                }.onFailure {
                    status("Gerätelautstärke konnte nicht geändert werden")
                }
            }
        )
    }

    private fun updateControlsForCameraState() {
        val available = rtmpCamera != null
        runOnUiThread {
            if (!::binding.isInitialized || isDestroyed) return@runOnUiThread
            binding.startButton.isEnabled = available
            updateSwitchCameraButton()
        }
        if (!available) status("Kameramodul ist auf diesem Gerät nicht verfügbar")
    }

    private fun updateSwitchCameraButton() {
        if (!::binding.isInitialized || isDestroyed) return
        binding.switchCameraButton.isEnabled = rtmpCamera != null && selectedCameraSource == 0
    }

    private fun startPreviewIfPossible() {
        val camera = rtmpCamera ?: return
        if (!activityActive || !previewReady || previewStarting) return
        if (!hasPermission(Manifest.permission.CAMERA)) return
        if (camera.isStreaming || camera.isOnPreview) return

        previewStarting = true
        binding.openGlView.post {
            if (!activityActive || !previewReady || camera.isStreaming || camera.isOnPreview) {
                previewStarting = false
                return@post
            }

            runCatching { camera.startPreview() }
                .onSuccess { status("Kamera bereit · Stabilitätsmodus aktiv") }
                .onFailure { status("Kameravorschau konnte nicht gestartet werden") }
            previewStarting = false
        }
    }

    private fun stopPreviewSafely() {
        previewStarting = false
        val camera = rtmpCamera ?: return
        runCatching {
            if (camera.isOnPreview && !camera.isStreaming) camera.stopPreview()
        }
    }

    private fun toggleCameraStream() {
        val camera = rtmpCamera
        if (camera == null) {
            status("Kameramodul ist nicht verfügbar")
            return
        }

        if (camera.isStreaming) {
            stopStreamSafely(showMessage = true)
            startPreviewIfPossible()
            return
        }

        if (!hasPermission(Manifest.permission.CAMERA)) {
            status("Kameraberechtigung fehlt")
            requestPermissionsIfNeeded()
            return
        }
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            status("Mikrofonberechtigung fehlt · Berechtigung wird angefordert")
            requestPermissionsIfNeeded()
            return
        }

        val streamPrefs = getSharedPreferences("stream", MODE_PRIVATE)
        val server = streamPrefs.getString("server", "").orEmpty().trim().trimEnd('/')
        val key = streamPrefs.getString("key", "").orEmpty().trim().trimStart('/')

        if (server.isBlank() || key.isBlank()) {
            status("RTMP-Server und Stream-Key in den Einstellungen speichern")
            return
        }
        if (!server.startsWith("rtmp://") && !server.startsWith("rtmps://")) {
            status("Gespeicherter RTMP-Server ist ungültig")
            return
        }
        if (!previewReady) {
            status("Kamerafläche ist noch nicht bereit")
            return
        }

        val portrait = currentRotation == 0 || currentRotation == 180
        val videoPrepared = runCatching {
            camera.prepareVideo(
                if (portrait) 720 else 1280,
                if (portrait) 1280 else 720,
                30,
                2_500_000,
                currentRotation,
                2
            )
        }.getOrDefault(false)

        if (!videoPrepared) {
            status("Video-Encoder konnte nicht vorbereitet werden")
            return
        }

        val audioPrepared = runCatching {
            camera.prepareAudio(128_000, 44_100, true, false, false)
        }.getOrDefault(false)

        if (!audioPrepared) {
            status("Audio-Encoder konnte nicht vorbereitet werden")
            return
        }

        runCatching { camera.startStream("$server/$key") }
            .onSuccess {
                if (!binding.micSwitch.isChecked) runCatching { camera.disableAudio() }
                setStartButtonText("Stream stoppen")
                status("Verbindung wird aufgebaut …")
            }
            .onFailure {
                setStartButtonText("Live starten")
                status("Streamstart fehlgeschlagen: ${it.message ?: "Unbekannter Fehler"}")
                startPreviewIfPossible()
            }
    }

    private fun stopStreamSafely(showMessage: Boolean) {
        val camera = rtmpCamera
        if (camera != null) {
            runCatching {
                if (camera.isStreaming) camera.stopStream()
            }
        }
        setStartButtonText("Live starten")
        if (showMessage) status("Stream beendet")
    }

    private fun setStartButtonText(text: String) {
        if (!::binding.isInitialized || isDestroyed) return
        runOnUiThread {
            if (!isDestroyed) binding.startButton.text = text
        }
    }

    private fun requestScreenCapture() {
        runCatching {
            val manager = getSystemService(MediaProjectionManager::class.java)
            screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
        }.onFailure {
            status("Bildschirmfreigabe konnte nicht geöffnet werden")
        }
    }

    private fun showUsbCaptureStatus() {
        val usbName = getSharedPreferences("live_settings", MODE_PRIVATE)
            .getString("usb_device_name", "")
            .orEmpty()
        status(
            if (usbName.isBlank()) {
                "Bitte zuerst ein USB-Capture-Gerät in den Einstellungen auswählen"
            } else {
                "USB-Gerät ausgewählt: $usbName · Videoanbindung noch nicht aktiviert"
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
        val configured = !prefs.getString("server", "").isNullOrBlank() &&
            !prefs.getString("key", "").isNullOrBlank()
        status(
            if (configured) {
                "Stream eingerichtet · stabiler Einzelkamera-Modus"
            } else {
                "Stream noch nicht eingerichtet · Einstellungen öffnen"
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
            if (!isDestroyed && !isFinishing) binding.statusText.text = text
        }
    }

    override fun onConnectionStarted(url: String) = status("Verbinde …")

    override fun onConnectionSuccess() {
        setStartButtonText("Stream stoppen")
        status("LIVE · Verbindung steht")
    }

    override fun onConnectionFailed(reason: String) {
        stopStreamSafely(showMessage = false)
        status("Verbindung fehlgeschlagen: $reason")
        startPreviewIfPossible()
    }

    override fun onNewBitrate(bitrate: Long) =
        status("LIVE · ${bitrate / 1000} kbit/s")

    override fun onDisconnect() {
        setStartButtonText("Live starten")
        status("Verbindung getrennt")
        startPreviewIfPossible()
    }

    override fun onAuthError() = status("Stream-Key wurde abgelehnt")

    override fun onAuthSuccess() = status("Authentifizierung erfolgreich")

    override fun onDestroy() {
        activityActive = false
        if (::orientationListener.isInitialized) orientationListener.disable()
        val camera = rtmpCamera
        if (camera != null) {
            runCatching { if (camera.isStreaming) camera.stopStream() }
            runCatching { if (camera.isOnPreview) camera.stopPreview() }
        }
        rtmpCamera = null
        super.onDestroy()
    }
}
