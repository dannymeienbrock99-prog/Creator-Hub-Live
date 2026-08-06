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
        configureGuestCount()
        loadSettings()
        refreshUsbDevices()

        binding.refreshUsbButton.setOnClickListener { refreshUsbDevices() }
        binding.usbDeviceSpinner.setOnItemSelectedListener(SimpleItemSelectedListener { position ->
            showUsbDetails(position)
        })
        binding.saveSettingsButton.setOnClickListener { saveSettings() }
    }

    private fun configureGuestCount() {
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
            "Neue Capture-Karten, USB-Mikrofone und USB-Kameras werden beim Aktualisieren erkannt."
        } else {
            val interfaces = (0 until device.interfaceCount).joinToString { i ->
                "Klasse ${device.getInterface(i).interfaceClass}"
            }
            "Gerät: ${device.productName ?: device.deviceName}\n" +
                "Hersteller: ${device.manufacturerName ?: "Unbekannt"}\n" +
                "Vendor/Product: ${device.vendorId}/${device.productId}\n" +
                "Schnittstellen: ${device.interfaceCount}${if (interfaces.isNotBlank()) " ($interfaces)" else ""}"
        }
    }

    private fun loadSettings() {
        val prefs = prefs()
        binding.showChat.isChecked = prefs.getBoolean("overlay_chat", true)
        binding.showGifts.isChecked = prefs.getBoolean("overlay_gifts", true)
        binding.showGoal.isChecked = prefs.getBoolean("overlay_goal", false)
        binding.showViewerCount.isChecked = prefs.getBoolean("overlay_viewers", true)
        binding.showGuestFrames.isChecked = prefs.getBoolean("overlay_guests", true)
        binding.showLogo.isChecked = prefs.getBoolean("overlay_logo", true)
        binding.guestCountSpinner.setSelection(prefs.getInt("guest_count", 1).coerceIn(0, 8))
        binding.autoGuestLayout.isChecked = prefs.getBoolean("guest_auto_layout", true)
        binding.prioritizeHost.isChecked = prefs.getBoolean("guest_host_priority", true)
    }

    private fun saveSettings() {
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

        Toast.makeText(this, "Live-Einstellungen gespeichert", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun prefs() = getSharedPreferences("live_settings", MODE_PRIVATE)
}
