package de.creatorhub.live

import android.Manifest
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
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
    private var previewReady = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) startCameraPreview()
        else status("Kamera- und Mikrofonrechte fehlen")
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Toast.makeText(this, "Bildschirmfreigabe erteilt", Toast.LENGTH_SHORT).show()
            status("Bildschirmaufnahme vorbereitet – Encoder-Service folgt")
        } else status("Bildschirmaufnahme abgebrochen")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        rtmpCamera = RtmpCamera2(binding.openGlView, this)
        loadSavedConnection()
        configureSources()
        configurePreview()
        configureControls()
        requestPermissionsIfNeeded()
    }

    private fun configureSources() {
        val sources = listOf(
            "Rückkamera",
            "Frontkamera",
            "Handyspiel / Bildschirm",
            "TV / HDMI über USB-Capture"
        )
        binding.sourceSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            sources
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
        binding.startButton.setOnClickListener {
            when (binding.sourceSpinner.selectedItemPosition) {
                2 -> requestScreenCapture()
                3 -> status("USB-Capture gewählt. Gerät anschließen und UVC-Unterstützung ergänzen.")
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

        binding.micVolume.setOnSeekBarChangeListener(simpleSeekListener { progress ->
            val gain = (progress.coerceAtLeast(1) / 100f)
            runCatching { rtmpCamera.setMicrophoneMode(com.pedro.encoder.input.audio.MicrophoneMode.SYNC) }
            status("Mikrofonpegel: ${(gain * 100).toInt()} %")
        })

        binding.deviceVolume.setOnSeekBarChangeListener(simpleSeekListener { progress ->
            status("Geräteton: $progress %")
        })
    }

    private fun toggleCameraStream() {
        if (rtmpCamera.isStreaming) {
            rtmpCamera.stopStream()
            binding.startButton.text = "Live starten"
            status("Stream beendet")
            return
        }

        val server = binding.serverInput.text.toString().trim().trimEnd('/')
        val key = binding.streamKeyInput.text.toString().trim().trimStart('/')
        if (server.isBlank() || key.isBlank()) {
            status("TikTok-Server und Stream-Key eingeben")
            return
        }
        if (!server.startsWith("rtmp://") && !server.startsWith("rtmps://")) {
            status("Server muss mit rtmp:// oder rtmps:// beginnen")
            return
        }

        saveConnection(server, key)
        if (!rtmpCamera.prepareVideo(1280, 720, 30, 3_500_000, 0, 2) ||
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
        val manager = getSystemService(MediaProjectionManager::class.java)
        screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            startCameraPreview()
        } else permissionLauncher.launch(permissions)
    }

    private fun startCameraPreview() {
        if (!previewReady || rtmpCamera.isOnPreview) return
        runCatching { rtmpCamera.startPreview() }
            .onFailure { status("Kameravorschau konnte nicht gestartet werden") }
    }

    private fun saveConnection(server: String, key: String) {
        getSharedPreferences("stream", MODE_PRIVATE).edit()
            .putString("server", server)
            .putString("key", key)
            .apply()
    }

    private fun loadSavedConnection() {
        val prefs = getSharedPreferences("stream", MODE_PRIVATE)
        binding.serverInput.setText(prefs.getString("server", ""))
        binding.streamKeyInput.setText(prefs.getString("key", ""))
    }

    private fun simpleSeekListener(onChanged: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
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
        if (rtmpCamera.isStreaming) rtmpCamera.stopStream()
        if (rtmpCamera.isOnPreview) rtmpCamera.stopPreview()
        super.onDestroy()
    }
}
