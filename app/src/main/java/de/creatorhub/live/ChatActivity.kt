package de.creatorhub.live

import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import de.creatorhub.live.databinding.ActivityChatBinding
import java.util.Locale

class ChatActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityChatBinding
    private lateinit var tts: TextToSpeech
    private var voices: List<Voice> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        tts = TextToSpeech(this, this)

        binding.filterSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("Alle späteren Nachrichten", "Nur Moderatoren", "Nur Abonnenten", "Mods und Abonnenten", "Ausgewählte Nutzer")
        )

        loadSettings()
        binding.testButton.setOnClickListener {
            handleMessage("Crazy_Batto", "Creator Hub Voice Studio ist bereit")
        }
        binding.saveChatSettings.setOnClickListener { saveSettings() }
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            setStatus("Vorlesefunktion konnte nicht gestartet werden")
            return
        }
        tts.language = Locale.GERMANY
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        voices = tts.voices.orEmpty()
            .filterNot { it.isNetworkConnectionRequired }
            .sortedWith(compareBy({ it.locale.displayLanguage }, { it.name }))
        val labels = voices.map { "${it.locale.displayLanguage} · ${it.name}" }
            .ifEmpty { listOf("Systemstandard") }
        binding.voiceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)

        val savedVoice = prefs().getString("tts_voice", "").orEmpty()
        val index = voices.indexOfFirst { it.name == savedVoice }
        if (index >= 0) binding.voiceSpinner.setSelection(index)
    }

    private fun handleMessage(username: String, text: String) {
        val line = TextView(this).apply {
            this.text = "$username: $text"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 17f
            setPadding(0, 10, 0, 10)
        }
        binding.chatMessages.addView(line, 0)
        speak(username, text)
    }

    private fun speak(username: String, text: String) {
        if (!binding.ttsEnabled.isChecked) return
        voices.getOrNull(binding.voiceSpinner.selectedItemPosition)?.let { tts.voice = it }
        tts.setSpeechRate(binding.speedBar.progress.coerceAtLeast(25) / 100f)
        tts.setPitch(binding.pitchBar.progress.coerceAtLeast(25) / 100f)
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, binding.ttsVolume.progress / 100f)
        }
        tts.speak("$username sagt: $text", TextToSpeech.QUEUE_FLUSH, params, "creator-hub-test")
    }

    private fun loadSettings() {
        val p = prefs()
        binding.ttsEnabled.isChecked = p.getBoolean("tts_enabled", true)
        binding.filterSpinner.setSelection(p.getInt("tts_filter", 0).coerceIn(0, 4))
        binding.speedBar.progress = p.getInt("tts_speed", 100).coerceIn(25, 200)
        binding.pitchBar.progress = p.getInt("tts_pitch", 100).coerceIn(25, 200)
        binding.ttsVolume.progress = p.getInt("tts_volume", 100).coerceIn(0, 100)
        binding.allowedUsers.setText(p.getString("tts_users", ""))
    }

    private fun saveSettings() {
        val selectedVoice = voices.getOrNull(binding.voiceSpinner.selectedItemPosition)
        prefs().edit()
            .putBoolean("tts_enabled", binding.ttsEnabled.isChecked)
            .putInt("tts_filter", binding.filterSpinner.selectedItemPosition)
            .putInt("tts_speed", binding.speedBar.progress)
            .putInt("tts_pitch", binding.pitchBar.progress)
            .putInt("tts_volume", binding.ttsVolume.progress)
            .putString("tts_users", binding.allowedUsers.text.toString())
            .putString("tts_voice", selectedVoice?.name.orEmpty())
            .apply()
        setStatus("Voice-Einstellungen gespeichert")
    }

    private fun setStatus(text: String) {
        binding.chatStatus.text = text
    }

    private fun prefs() = getSharedPreferences("voice_settings", MODE_PRIVATE)

    override fun onDestroy() {
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        super.onDestroy()
    }
}
