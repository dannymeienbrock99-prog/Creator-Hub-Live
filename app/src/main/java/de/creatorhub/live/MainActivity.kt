package de.creatorhub.live

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.view.SurfaceHolder
import android.view.TextureView
import android.view.View
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
    private lateinit var cameraManager: CameraManager
    private lateinit var orientationListener: OrientationEventListener
    private val mainHandler = Handler(Looper.getMainLooper())

    private var previewReady = false
    private var currentRotation = 0
    private var activityActive = false
    private var concurrentSupported = false
    private var frontOpening = false
    private var frontCamera: CameraDevice? = null
    private var frontSession: CameraCaptureSession? = null
    private var dragDownX = 0f
    private var dragDownY = 0f
    private var dragStartX = 0f
    private var dragStartY = 0f

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) startMainPreview()
        else status("Kamera- und Mikrofonrechte fehlen")
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        status(if (result.resultCode == RESULT_OK) "Bildschirmfreigabe erteilt" else "Bildschirmaufnahme abgebrochen")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        audioManager = getSystemService(AudioManager::class.java)
        cameraManager = getSystemService(CameraManager::class.java)
        concurrentSupported = detectConcurrentSupport()
        rtmpCamera = RtmpCamera2(binding.openGlView, this)

        configureOrientation()
        configureSources()
        configurePreview()
        configureFrontOverlay()
        configureControls()
        requestPermissionsIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        activityActive = true
        if (::orientationListener.isInitialized && orientationListener.canDetectOrientation()) orientationListener.enable()
        if (hasCameraPermission()) startMainPreview()
        showConfiguration()
    }

    override fun onPause() {
        activityActive = false
        if (::orientationListener.isInitialized) orientationListener.disable()
        disableFrontCircle(updateSwitch = true)
        super.onPause()
    }

    private fun detectConcurrentSupport(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return runCatching {
            val front = cameraManager.cameraIdList.filter { id ->
                cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            }.toSet()
            val back = cameraManager.cameraIdList.filter { id ->
                cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }.toSet()
            cameraManager.concurrentCameraIds.any { group -> group.any(front::contains) && group.any(back::contains) }
        }.getOrDefault(false)
    }

    private fun configureOrientation() {
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
                    binding.openGlView.rotation = rotation.toFloat()
                    binding.frontCameraPreview.rotation = rotation.toFloat()
                }
            }
        }
    }

    private fun configureSources() {
        binding.sourceSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("Rückkamera", "Frontkamera", "Handyspiel / Bildschirm", "TV / HDMI über USB-Capture")
        )
        binding.frontCircleSwitch.isEnabled = concurrentSupported
        if (!concurrentSupported) binding.frontCircleSwitch.text = "Frontkamera-Kreis wird von diesem Handy nicht unterstützt"
    }

    private fun configurePreview() {
        binding.openGlView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                previewReady = true
                startMainPreview()
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                previewReady = false
                runCatching { if (rtmpCamera.isOnPreview) rtmpCamera.stopPreview() }
            }
        })
    }

    private fun configureFrontOverlay() {
        binding.frontCameraPreview.visibility = View.GONE
        binding.frontCameraPreview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                if (binding.frontCircleSwitch.isChecked) startFrontCircle()
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                disableFrontCircle(updateSwitch = true)
                return true
            }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
        binding.frontCameraPreview.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragDownX = event.rawX; dragDownY = event.rawY; dragStartX = view.x; dragStartY = view.y; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val maxX = (binding.cameraStage.width - view.width).coerceAtLeast(0).toFloat()
                    val maxY = (binding.cameraStage.height - view.height).coerceAtLeast(0).toFloat()
                    view.x = (dragStartX + event.rawX - dragDownX).coerceIn(0f, maxX)
                    view.y = (dragStartY + event.rawY - dragDownY).coerceIn(0f, maxY)
                    true
                }
                else -> true
            }
        }
    }

    private fun configureControls() {
        binding.settingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.chatButton.setOnClickListener { startActivity(Intent(this, ChatActivity::class.java)) }
        binding.frontCircleSwitch.setOnCheckedChangeListener { _, enabled ->
            if (enabled) {
                binding.frontCameraPreview.visibility = View.VISIBLE
                mainHandler.postDelayed({ startFrontCircle() }, 250)
            } else disableFrontCircle(updateSwitch = false)
        }
        binding.startButton.setOnClickListener {
            when (binding.sourceSpinner.selectedItemPosition) {
                2 -> screenCaptureLauncher.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
                3 -> status("Bitte USB-Capture-Gerät in den Einstellungen auswählen")
                else -> toggleStream()
            }
        }
        binding.switchCameraButton.setOnClickListener {
            disableFrontCircle(updateSwitch = true)
            runCatching { rtmpCamera.switchCamera() }
                .onFailure { status("Kamera konnte nicht gewechselt werden") }
        }
        binding.micSwitch.setOnCheckedChangeListener { _, enabled ->
            runCatching { if (enabled) rtmpCamera.enableAudio() else rtmpCamera.disableAudio() }
        }
        binding.micVolume.setOnSeekBarChangeListener(simpleSeekListener { status("Mikrofonpegel: $it %") })
        binding.deviceVolume.setOnSeekBarChangeListener(simpleSeekListener { progress ->
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, ((progress / 100f) * max).toInt(), 0)
        })
    }

    private fun startMainPreview() {
        if (!activityActive || !previewReady || !hasCameraPermission() || rtmpCamera.isOnPreview) return
        runCatching { rtmpCamera.startPreview() }
            .onSuccess { status("Kamera bereit") }
            .onFailure { status("Kameravorschau konnte nicht gestartet werden") }
    }

    private fun startFrontCircle() {
        if (!concurrentSupported || !activityActive || !binding.frontCircleSwitch.isChecked) return
        if (!hasCameraPermission() || !binding.frontCameraPreview.isAvailable || frontCamera != null || frontOpening) return
        val frontId = runCatching {
            cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            }
        }.getOrNull() ?: return disableFrontCircle(true)

        frontOpening = true
        try {
            cameraManager.openCamera(frontId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    frontOpening = false
                    if (!activityActive || !binding.frontCircleSwitch.isChecked) return camera.close()
                    frontCamera = camera
                    createFrontSession(camera)
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); disableFrontCircle(true) }
                override fun onError(camera: CameraDevice, error: Int) { camera.close(); disableFrontCircle(true) }
            }, mainHandler)
        } catch (_: Exception) {
            frontOpening = false
            disableFrontCircle(true)
            status("Frontkamera-Kreis wurde sicher deaktiviert")
        }
    }

    private fun createFrontSession(camera: CameraDevice) {
        try {
            val texture = binding.frontCameraPreview.surfaceTexture ?: return disableFrontCircle(true)
            texture.setDefaultBufferSize(360, 360)
            val surface = Surface(texture)
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
            camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (!activityActive || frontCamera == null) return session.close()
                    frontSession = session
                    runCatching { session.setRepeatingRequest(request.build(), null, mainHandler) }
                        .onFailure { disableFrontCircle(true) }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) { session.close(); disableFrontCircle(true) }
            }, mainHandler)
        } catch (_: Exception) {
            disableFrontCircle(true)
        }
    }

    private fun disableFrontCircle(updateSwitch: Boolean) {
        frontOpening = false
        runCatching { frontSession?.close() }
        runCatching { frontCamera?.close() }
        frontSession = null
        frontCamera = null
        if (::binding.isInitialized) {
            binding.frontCameraPreview.visibility = View.GONE
            if (updateSwitch && binding.frontCircleSwitch.isChecked) {
                binding.frontCircleSwitch.setOnCheckedChangeListener(null)
                binding.frontCircleSwitch.isChecked = false
                configureControls()
            }
        }
    }

    private fun toggleStream() {
        if (rtmpCamera.isStreaming) {
            runCatching { rtmpCamera.stopStream() }
            binding.startButton.text = "Live starten"
            return status("Stream beendet")
        }
        val prefs = getSharedPreferences("stream", MODE_PRIVATE)
        val server = prefs.getString("server", "").orEmpty().trim().trimEnd('/')
        val key = prefs.getString("key", "").orEmpty().trim().trimStart('/')
        if (server.isBlank() || key.isBlank()) return status("RTMP-Server und Stream-Key in den Einstellungen speichern")
        val portrait = currentRotation == 0 || currentRotation == 180
        val prepared = runCatching {
            rtmpCamera.prepareVideo(if (portrait) 720 else 1280, if (portrait) 1280 else 720, 30, 3_500_000, currentRotation, 2) &&
                rtmpCamera.prepareAudio(128_000, 44_100, true, false, false)
        }.getOrDefault(false)
        if (!prepared) return status("Encoder konnte nicht vorbereitet werden")
        runCatching { rtmpCamera.startStream("$server/$key") }
            .onSuccess { binding.startButton.text = "Stream stoppen" }
            .onFailure { status("Streamstart fehlgeschlagen") }
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) startMainPreview()
        else permissionLauncher.launch(permissions)
    }

    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun showConfiguration() {
        val configured = getSharedPreferences("stream", MODE_PRIVATE).getString("key", "").orEmpty().isNotBlank()
        status(if (configured) "Stream eingerichtet · stabiler Kameramodus" else "Stream noch nicht eingerichtet")
    }

    private fun simpleSeekListener(action: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) action(progress) }
        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }

    private fun status(text: String) { runOnUiThread { binding.statusText.text = text } }
    override fun onConnectionStarted(url: String) = status("Verbinde …")
    override fun onConnectionSuccess() = status("LIVE – Verbindung steht")
    override fun onConnectionFailed(reason: String) { runCatching { rtmpCamera.stopStream() }; status("Verbindung fehlgeschlagen: $reason") }
    override fun onNewBitrate(bitrate: Long) = status("LIVE – ${bitrate / 1000} kbit/s")
    override fun onDisconnect() = status("Verbindung getrennt")
    override fun onAuthError() = status("Stream-Key wurde abgelehnt")
    override fun onAuthSuccess() = status("Authentifizierung erfolgreich")

    override fun onDestroy() {
        activityActive = false
        disableFrontCircle(updateSwitch = false)
        runCatching { if (rtmpCamera.isStreaming) rtmpCamera.stopStream() }
        runCatching { if (rtmpCamera.isOnPreview) rtmpCamera.stopPreview() }
        super.onDestroy()
    }
}
