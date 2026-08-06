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
    private lateinit var orientationListener: OrientationEventListener
    private lateinit var cameraManager: CameraManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var previewReady = false
    private var currentRotation = 0
    private var activityActive = false
    private var concurrentCameraSupported = false
    private var frontCameraOpening = false
    private var frontCamera: CameraDevice? = null
    private var frontSession: CameraCaptureSession? = null
    private var dragDownX = 0f
    private var dragDownY = 0f
    private var dragStartX = 0f
    private var dragStartY = 0f

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            startCameraPreview()
            scheduleFrontCameraPreview()
        } else {
            hideFrontCameraCircle()
            status("Kamera- und Mikrofonrechte fehlen")
        }
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
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        audioManager = getSystemService(AudioManager::class.java)
        cameraManager = getSystemService(CameraManager::class.java)
        concurrentCameraSupported = detectConcurrentFrontBackSupport()
        rtmpCamera = RtmpCamera2(binding.openGlView, this)

        configureOrientationSensor()
        configureSources()
        configurePreview()
        configureFrontCameraOverlay()
        configureControls()
        requestPermissionsIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        activityActive = true
        if (::orientationListener.isInitialized && orientationListener.canDetectOrientation()) {
            orientationListener.enable()
        }
        showActiveLiveConfiguration()
        if (hasCameraPermission()) {
            startCameraPreview()
            scheduleFrontCameraPreview()
        }
    }

    override fun onPause() {
        activityActive = false
        mainHandler.removeCallbacksAndMessages(null)
        if (::orientationListener.isInitialized) orientationListener.disable()
        stopFrontCameraPreview()
        super.onPause()
    }

    private fun detectConcurrentFrontBackSupport(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return runCatching {
            val frontIds = cameraManager.cameraIdList.filter { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            }.toSet()
            val backIds = cameraManager.cameraIdList.filter { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }.toSet()
            cameraManager.concurrentCameraIds.any { group ->
                group.any(frontIds::contains) && group.any(backIds::contains)
            }
        }.getOrDefault(false)
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
        binding.openGlView.rotation = rotation.toFloat()
        binding.openGlView.requestLayout()
        binding.frontCameraPreview.rotation = rotation.toFloat()
        status("Kameraausrichtung: $mode · Sensor aktiv")
    }

    private fun configureSources() {
        val firstSource = if (concurrentCameraSupported) {
            "Rückkamera + Frontkamera-Kreis"
        } else {
            "Rückkamera (Frontkamera-Kreis nicht unterstützt)"
        }
        binding.sourceSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf(firstSource, "Frontkamera", "Handyspiel / Bildschirm", "TV / HDMI über USB-Capture")
        )
        if (!concurrentCameraSupported) hideFrontCameraCircle()
    }

    private fun configurePreview() {
        binding.openGlView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                previewReady = true
                startCameraPreview()
                scheduleFrontCameraPreview()
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                previewReady = false
                runCatching { if (rtmpCamera.isOnPreview) rtmpCamera.stopPreview() }
            }
        })
    }

    private fun configureFrontCameraOverlay() {
        if (!concurrentCameraSupported) {
            hideFrontCameraCircle()
            return
        }
        binding.frontCameraPreview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                scheduleFrontCameraPreview()
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                stopFrontCameraPreview()
                return true
            }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }

        binding.frontCameraPreview.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragDownX = event.rawX
                    dragDownY = event.rawY
                    dragStartX = view.x
                    dragStartY = view.y
                    true
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

    private fun scheduleFrontCameraPreview() {
        if (!concurrentCameraSupported || !activityActive || !previewReady) return
        mainHandler.removeCallbacks(frontPreviewRunnable)
        mainHandler.postDelayed(frontPreviewRunnable, 900)
    }

    private val frontPreviewRunnable = Runnable { startFrontCameraPreview() }

    private fun startFrontCameraPreview() {
        if (!concurrentCameraSupported || !activityActive || !previewReady) return
        if (!hasCameraPermission() || !binding.frontCameraPreview.isAvailable) return
        if (frontCamera != null || frontCameraOpening) return

        val frontId = runCatching {
            cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            }
        }.getOrNull()

        if (frontId.isNullOrBlank()) {
            disableFrontCameraCircle("Keine Frontkamera gefunden")
            return
        }

        frontCameraOpening = true
        try {
            cameraManager.openCamera(frontId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    frontCameraOpening = false
                    if (!activityActive) {
                        camera.close()
                        return
                    }
                    frontCamera = camera
                    createFrontSession(camera)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    frontCameraOpening = false
                    camera.close()
                    frontCamera = null
                    hideFrontCameraCircle()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    frontCameraOpening = false
                    camera.close()
                    frontCamera = null
                    disableFrontCameraCircle("Frontkamera-Kreis wird von diesem Handy nicht unterstützt")
                }
            }, mainHandler)
        } catch (_: SecurityException) {
            frontCameraOpening = false
            disableFrontCameraCircle("Kameraberechtigung fehlt")
        } catch (_: Exception) {
            frontCameraOpening = false
            disableFrontCameraCircle("Frontkamera-Kreis wurde sicher deaktiviert")
        }
    }

    private fun createFrontSession(camera: CameraDevice) {
        try {
            val texture = binding.frontCameraPreview.surfaceTexture ?: run {
                stopFrontCameraPreview()
                return
            }
            texture.setDefaultBufferSize(480, 480)
            val surface = Surface(texture)
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
            camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (!activityActive || frontCamera == null) {
                        session.close()
                        return
                    }
                    frontSession = session
                    runCatching {
                        session.setRepeatingRequest(request.build(), null, mainHandler)
                    }.onFailure {
                        disableFrontCameraCircle("Frontkamera-Kreis wurde sicher deaktiviert")
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    session.close()
                    disableFrontCameraCircle("Frontkamera-Kreis konnte nicht gestartet werden")
                }
            }, mainHandler)
        } catch (_: Exception) {
            disableFrontCameraCircle("Frontkamera-Kreis wurde sicher deaktiviert")
        }
    }

    private fun disableFrontCameraCircle(message: String) {
        concurrentCameraSupported = false
        stopFrontCameraPreview()
        runOnUiThread {
            hideFrontCameraCircle()
            status(message)
        }
    }

    private fun hideFrontCameraCircle() {
        if (::binding.isInitialized) binding.frontCameraPreview.visibility = View.GONE
    }

    private fun stopFrontCameraPreview() {
        frontCameraOpening = false
        runCatching { frontSession?.stopRepeating() }
        runCatching { frontSession?.abortCaptures() }
        runCatching { frontSession?.close() }
        runCatching { frontCamera?.close() }
        frontSession = null
        frontCamera = null
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
            stopFrontCameraPreview()
            runCatching { rtmpCamera.switchCamera() }
                .onSuccess { scheduleFrontCameraPreview() }
                .onFailure { status("Kamera konnte nicht gewechselt werden") }
        }
        binding.micSwitch.setOnCheckedChangeListener { _, enabled ->
            runCatching { if (enabled) rtmpCamera.enableAudio() else rtmpCamera.disableAudio() }
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
        val streamPrefs = getSharedPreferences("stream", MODE_PRIVATE)
        val configured = !streamPrefs.getString("server", "").isNullOrBlank() &&
            !streamPrefs.getString("key", "").isNullOrBlank()
        val cameraMode = if (concurrentCameraSupported) "Frontkamera-Kreis aktiv" else "stabiler Einzelkamera-Modus"
        status("${if (configured) "Stream eingerichtet" else "Stream nicht eingerichtet"} · $cameraMode · Gäste: $guestCount · USB: ${if (usbName.isBlank()) "kein USB-Gerät" else usbName}")
    }

    private fun toggleCameraStream() {
        if (rtmpCamera.isStreaming) {
            runCatching { rtmpCamera.stopStream() }
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
        val prepared = runCatching {
            rtmpCamera.prepareVideo(width, height, 30, 3_500_000, currentRotation, 2) &&
                rtmpCamera.prepareAudio(128_000, 44_100, true, false, false)
        }.getOrDefault(false)
        if (!prepared) {
            status("Encoder konnte nicht vorbereitet werden")
            return
        }

        runCatching { rtmpCamera.startStream("$server/$key") }
            .onSuccess {
                binding.startButton.text = "Stream stoppen"
                status("Verbindung wird aufgebaut …")
            }
            .onFailure { status("Stream konnte nicht gestartet werden: ${it.message ?: "Unbekannter Fehler"}") }
    }

    private fun requestScreenCapture() {
        screenCaptureLauncher.launch(
            getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()
        )
    }

    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissionsIfNeeded() {
        val permissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            startCameraPreview()
            scheduleFrontCameraPreview()
        } else permissionLauncher.launch(permissions)
    }

    private fun startCameraPreview() {
        if (!activityActive || !previewReady || rtmpCamera.isOnPreview) return
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
        if (!isFinishing && !isDestroyed) runOnUiThread { binding.statusText.text = text }
    }

    override fun onConnectionStarted(url: String) = status("Verbinde mit TikTok …")
    override fun onConnectionSuccess() = status("LIVE – Verbindung steht")
    override fun onConnectionFailed(reason: String) {
        status("Verbindung fehlgeschlagen: $reason")
        runCatching { if (rtmpCamera.isStreaming) rtmpCamera.stopStream() }
        runOnUiThread { binding.startButton.text = "Live starten" }
    }
    override fun onNewBitrate(bitrate: Long) = status("LIVE – ${bitrate / 1000} kbit/s")
    override fun onDisconnect() = status("Verbindung getrennt")
    override fun onAuthError() = status("TikTok hat den Stream-Key abgelehnt")
    override fun onAuthSuccess() = status("TikTok-Authentifizierung erfolgreich")

    override fun onDestroy() {
        activityActive = false
        mainHandler.removeCallbacksAndMessages(null)
        if (::orientationListener.isInitialized) orientationListener.disable()
        stopFrontCameraPreview()
        runCatching { if (rtmpCamera.isStreaming) rtmpCamera.stopStream() }
        runCatching { if (rtmpCamera.isOnPreview) rtmpCamera.stopPreview() }
        super.onDestroy()
    }
}
