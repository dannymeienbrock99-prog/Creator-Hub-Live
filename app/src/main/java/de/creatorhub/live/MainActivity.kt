package de.creatorhub.live

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.OrientationEventListener
import android.view.Surface
import android.view.SurfaceHolder
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.pedro.common.ConnectChecker
import com.pedro.library.rtmp.RtmpCamera2
import de.creatorhub.live.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), ConnectChecker {

    private lateinit var binding: ActivityMainBinding
    private lateinit var rtmpCamera: RtmpCamera2
    private lateinit var audioManager: AudioManager
    private lateinit var orientationListener: OrientationEventListener
    private var previewReady = false
    private var currentRotation = 0

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) startCameraPreview() else status("Kamera- und Mikrofonrechte fehlen")
    }

    private val screenCaptureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            Toast.makeText(this, "Bildschirmfreigabe erteilt", Toast.LENGTH_SHORT).show()
            status("Bildschirmaufnahme vorbereitet – Encoder-Service folgt")
        } else status("Bildschirmaufnahme abgebrochen")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        audioManager = getSystemService(AudioManager::class.java)
        rtmpCamera = RtmpCamera2(binding.openGlView, this)
        configureOrientationSensor()
        configureSources()
        configurePreview()
        configureControls()
        requestPermissionsIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        if (::orientationListener.isInitialized && orientationListener.canDetectOrientation()) orientationListener.enable()
        showActiveLiveConfiguration()
    }

    override fun onPause() {
        if (::orientationListener.isInitialized) orientationListener.disable()
        super.onPause()
    }

    private fun configureOrientationSensor() {
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val rotation = when (orientation) {
                    in 45..134 -> 270
                    in 135..224 -> 180
                    in 225..314 -> 90
                    else -> 0
                }
                if (rotation != currentRotation) {
                    currentRotation = rotation
                    updateCameraRotation(rotation)
                }
            }
        }
    }

    private fun updateCameraRotation(rotation: Int) {
        val mode = when (rotation) {
            90 -> "Querformat links"
            180 -> "Hochformat gedreht"
            270 -> "Querformat rechts"
            else -> "Hochformat"
        }
        val surfaceRotation = when (rotation) {
            90 -> Surface.ROTATION_90
            180 -> Surface.ROTATION_180
            270 -> Surface.ROTATION_270
            else -> Surface.ROTATION_0
        }
        binding.openGlView.rotation = rotation.toFloat()
        binding.openGlView.requestLayout()
        status("Kameraausrichtung: $mode · Sensor aktiv · Rotation $surfaceRotation")
    }

    private fun configureSources() {
        binding.sourceSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("Rückkamera", "Frontkamera", "Handyspiel / Bildschirm", "TV / HDMI über USB-Capture")
        )
    }

    private fun configurePreview() {
        binding.openGlView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                previewReady = true
                startCameraPreview()
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                previewReady = false
                if (rtmpCamera.isOnPreview) rtmpCamera.stopPreview()
            }
        })
    }

    private fun configureControls() {
        binding.settingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.chatButton.setOnClickListener { startActivity(Intent(this, ChatActivity::class.java)) }
        binding.startButton.setOnClickListener {
            when (binding.sourceSpinner.selectedItemPosition) {
                2 -> requestScreenCapture()
                3 -> {
                    val usbName = getSharedPreferences("live_settings", MODE_PRIVATE)
                        .getString("usb_device_name", "")
                    status(if (usbName.isNullOrBlank()) "Bitte zuerst ein USB-Capture-Gerät auswählen" else "USB-Capture gewählt: $usbName")
                }
                else -> toggleCameraStream()
            }
        }
        binding.switchCameraButton.setOnClickListener {
            runCatching { rtmpCamera.switchCamera() }
                .onFailure { status("Kamera konnte nicht gewechselt werden") }
        }
        binding.micSwitch.setOnCheckedChangeListener { _, enabled ->
            if (enabled) rtmpCamera.enableAudio() else rtmpCamera.disableAudio()
        }
        binding.micVolume.setOnSeekBarChangeListener(simpleSeekListener { status("Mikrofonpegel: $it %") })
        binding.deviceVolume.setOnSeekBarChangeListener(simpleSeekListener { progress ->
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                ((progress.coerceIn(0, 100) / 100f) * max).toInt(),
                0
            )
            status("Geräteton: $progress %")
        })
    }

    private fun showActiveLiveConfiguration() {
        if (::rtmpCamera.isInitialized && rtmpCamera.isStreaming) return
        val prefs = getSharedPreferences("live_settings", MODE_PRIVATE)
        val guestCount = prefs.getInt("guest_count", 1)
        val usbName = prefs.getString("usb_device_name", "").orEmpty()
        val overlayParts = buildList {
            if (prefs.getBoolean("overlay_chat", true)) add("Chat")
            if (prefs.getBoolean("overlay_gifts", true)) add("Geschenke")
            if (prefs.getBoolean("overlay_goal", false)) add("Ziel")
            if (prefs.getBoolean("overlay_viewers", true)) add("Zuschauer")
            if (prefs.getBoolean("overlay_guests", true)) add("Gäste")
            if (prefs.getBoolean("overlay_logo", true)) add("Logo")
        }
        val streamPrefs = getSharedPreferences("stream", MODE_PRIVATE)
        val configured = !streamPrefs.getString("server", "").isNullOrBlank() &&
            !streamPrefs.getString("key", "").isNullOrBlank()
        val connection = if (configured) "Stream eingerichtet" else "Stream nicht eingerichtet"
        status("$connection · Overlay: ${overlayParts.joinToString()} · Gäste: $guestCount · USB: ${if (usbName.isBlank()) "kein USB-Gerät" else usbName}")
    }

    private fun toggleCameraStream() {
        if (rtmpCamera.isStreaming) {
            rtmpCamera.stopStream()
            binding.startButton.text = "Live starten"
            status("Stream beendet")
            return
        }

        val streamPrefs = getSharedPreferences("stream", MODE_PRIVATE)
        val server = streamPrefs.getString("server", "").orEmpty().trim().trimEnd('/')
        val key = streamPrefs.getString("key", "").orEmpty().trim().trimStart('/')
        if (server.isBlank() || key.isBlank()) {
            status("Bitte RTMP-Server und Stream-Key in den Einstellungen speichern")
            return
        }
        if (!server.startsWith("rtmp://") && !server.startsWith("rtmps://")) {
            status("Gespeicherter Server ist ungültig")
            return
        }

        val portrait = currentRotation == 0 || currentRotation == 180
        val width = if (portrait) 720 else 1280
        val height = if (portrait) 1280 else 720
        if (!rtmpCamera.prepareVideo(width, height, 30, 3_500_000, currentRotation, 2) ||
            !rtmpCamera.prepareAudio(128_000, 44_100, true, false, false)
        ) {
            status("Encoder konnte nicht vorbereitet werden")
            return
        }
        rtmpCamera.startStream("$server/$key")
        binding.startButton.text = "Stream stoppen"
        status("Verbindung wird aufgebaut …")
    }

    private fun requestScreenCapture() {
        screenCaptureLauncher.launch(
            getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()
        )
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (permissions.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }) {
            startCameraPreview()
        } else permissionLauncher.launch(permissions)
    }

    private fun startCameraPreview() {
        if (!previewReady || rtmpCamera.isOnPreview) return
        runCatching { rtmpCamera.startPreview() }
            .onFailure { status("Kameravorschau konnte nicht gestartet werden") }
    }

    private fun simpleSeekListener(onChanged: (Int) -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) onChanged(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }

    private fun status(text: String) {
        runOnUiThread { binding.statusText.text = text }
    }

    override fun onConnectionStarted(url: String) = status("Verbinde mit TikTok …")
    override fun onConnectionSuccess() = status("LIVE – Verbindung steht")
    override fun onConnectionFailed(reason: String) {
        status("Verbindung fehlgeschlagen: $reason")
        if (rtmpCamera.isStreaming) rtmpCamera.stopStream()
        runOnUiThread { binding.startButton.text = "Live starten" }
    }
    override fun onNewBitrate(bitrate: Long) = status("LIVE – ${bitrate / 1000} kbit/s")
    override fun onDisconnect() = status("Verbindung getrennt")
    override fun onAuthError() = status("TikTok hat den Stream-Key abgelehnt")
    override fun onAuthSuccess() = status("TikTok-Authentifizierung erfolgreich")

    override fun onDestroy() {
        if (::orientationListener.isInitialized) orientationListener.disable()
        if (rtmpCamera.isStreaming) rtmpCamera.stopStream()
        if (rtmpCamera.isOnPreview) rtmpCamera.stopPreview()
        super.onDestroy()
    }
}
