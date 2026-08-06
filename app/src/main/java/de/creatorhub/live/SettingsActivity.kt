package de.creatorhub.live

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import de.creatorhub.live.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var usbManager: UsbManager
    private var usbDevices: List<UsbDevice> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        usbManager = getSystemService(UsbManager::class.java)
        configureSpinners()
        loadSettings()
        refreshUsbDevices()

        binding.refreshUsbButton.setOnClickListener { refreshUsbDevices() }
        binding.usbDeviceSpinner.onItemSelectedListener = SimpleItemSelectedListener { position ->
            showUsbDetails(position)
        }
        binding.saveSettingsButton.setOnClickListener { saveSettings() }
    }

    private fun configureSpinners() {
        binding.resolutionSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("720p", "1080p")
        )
        binding.fpsSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("30 FPS", "60 FPS")
        )
        binding.bitrateModeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("Automatisch", "Manuell")
        )
        val counts = (0..8).map { if (it == 0) "Keine Gäste" else "$it Gäste" }
        binding.guestCountSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            counts
        )
    }

    private fun refreshUsbDevices() {
        usbDevices = usbManager.deviceList.values.sortedBy { it.productName ?: it.deviceName }
        val names = if (usbDevices.isEmpty()) {
            listOf("Kein kompatibles USB-Gerät erkannt")
        } else {
            usbDevices.map { device ->
                val product = device.productName ?: "USB-Gerät"
                "$product · ${device.vendorId}:${device.productId}"
            }
        }
        binding.usbDeviceSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            names
        )

        val savedId = prefs().getInt("usb_device_id", -1)
        val index = usbDevices.indexOfFirst { it.deviceId == savedId }
        if (index >= 0) binding.usbDeviceSpinner.setSelection(index)
        showUsbDetails(if (index >= 0) index else 0)
    }

    private fun showUsbDetails(position: Int) {
        val device = usbDevices.getOrNull(position)
        binding.usbDeviceInfo.text = if (device == null) {
            "Capture-Karten, USB-Mikrofone und USB-Kameras werden beim Aktualisieren erkannt."
        } else {
            "Gerät: ${device.productName ?: device.deviceName}\n" +
                "Hersteller: ${device.manufacturerName ?: "Unbekannt"}\n" +
                "Vendor/Product: ${device.vendorId}/${device.productId}\n" +
                "Schnittstellen: ${device.interfaceCount}"
        }
    }

    private fun loadSettings() {
        val connectionPrefs = getSharedPreferences("stream", MODE_PRIVATE)
        binding.serverInput.setText(connectionPrefs.getString("server", ""))
        binding.streamKeyInput.setText(connectionPrefs.getString("key", ""))

        val profile = getSharedPreferences("stream_profile", MODE_PRIVATE)
        binding.resolutionSpinner.setSelection(if (profile.getString("resolution", "720p") == "1080p") 1 else 0)
        binding.fpsSpinner.setSelection(if (profile.getInt("fps", 30) == 60) 1 else 0)
        binding.bitrateModeSpinner.setSelection(if (profile.getString("bitrate_mode", "Automatisch") == "Manuell") 1 else 0)
        binding.bitrateInput.setText(profile.getInt("bitrate_kbps", 3000).toString())
        binding.recordStream.isChecked = profile.getBoolean("record_stream", false)

        val prefs = prefs()
        binding.showChat.isChecked = prefs.getBoolean("overlay_chat", false)
        binding.showGifts.isChecked = prefs.getBoolean("overlay_gifts", false)
        binding.showGoal.isChecked = prefs.getBoolean("overlay_goal", false)
        binding.showViewerCount.isChecked = prefs.getBoolean("overlay_viewers", false)
        binding.showGuestFrames.isChecked = prefs.getBoolean("overlay_guests", false)
        binding.showLogo.isChecked = prefs.getBoolean("overlay_logo", true)
        binding.guestCountSpinner.setSelection(prefs.getInt("guest_count", 0).coerceIn(0, 8))
        binding.autoGuestLayout.isChecked = prefs.getBoolean("guest_auto_layout", true)
        binding.prioritizeHost.isChecked = prefs.getBoolean("guest_host_priority", true)
    }

    private fun saveSettings() {
        val server = binding.serverInput.text.toString().trim().trimEnd('/')
        val key = binding.streamKeyInput.text.toString().trim().trimStart('/')
        if (server.isNotBlank() && !server.startsWith("rtmp://") && !server.startsWith("rtmps://")) {
            binding.serverInput.error = "Server muss mit rtmp:// oder rtmps:// beginnen"
            return
        }

        getSharedPreferences("stream", MODE_PRIVATE).edit()
            .putString("server", server)
            .putString("key", key)
            .apply()

        val bitrate = binding.bitrateInput.text.toString().toIntOrNull()?.coerceIn(1000, 12000) ?: 3000
        getSharedPreferences("stream_profile", MODE_PRIVATE).edit()
            .putString("resolution", if (binding.resolutionSpinner.selectedItemPosition == 1) "1080p" else "720p")
            .putInt("fps", if (binding.fpsSpinner.selectedItemPosition == 1) 60 else 30)
            .putString("bitrate_mode", if (binding.bitrateModeSpinner.selectedItemPosition == 1) "Manuell" else "Automatisch")
            .putInt("bitrate_kbps", bitrate)
            .putBoolean("record_stream", binding.recordStream.isChecked)
            .apply()

        val selectedDevice = usbDevices.getOrNull(binding.usbDeviceSpinner.selectedItemPosition)
        prefs().edit()
            .putInt("usb_device_id", selectedDevice?.deviceId ?: -1)
            .putString("usb_device_name", selectedDevice?.productName ?: "")
            .putBoolean("overlay_chat", binding.showChat.isChecked)
            .putBoolean("overlay_gifts", binding.showGifts.isChecked)
            .putBoolean("overlay_goal", binding.showGoal.isChecked)
            .putBoolean("overlay_viewers", binding.showViewerCount.isChecked)
            .putBoolean("overlay_guests", binding.showGuestFrames.isChecked)
            .putBoolean("overlay_logo", binding.showLogo.isChecked)
            .putInt("guest_count", binding.guestCountSpinner.selectedItemPosition)
            .putBoolean("guest_auto_layout", binding.autoGuestLayout.isChecked)
            .putBoolean("guest_host_priority", binding.prioritizeHost.isChecked)
            .apply()

        Toast.makeText(this, "Creator-Hub-Einstellungen gespeichert", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun prefs() = getSharedPreferences("live_settings", MODE_PRIVATE)
}
