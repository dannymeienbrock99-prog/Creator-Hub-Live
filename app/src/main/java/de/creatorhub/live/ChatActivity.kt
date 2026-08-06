package de.creatorhub.live

import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import de.creatorhub.live.databinding.ActivityChatBinding
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.Locale

class ChatActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityChatBinding
    private lateinit var tts: TextToSpeech
    private val client = OkHttpClient.Builder().build()
    private var socket: WebSocket? = null
    private var voices: List<Voice> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        tts = TextToSpeech(this, this)

        binding.filterSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("Alle", "Nur Moderatoren", "Nur Abonnenten", "Mods und Abonnenten", "Ausgewählte Nutzer")
        )
        loadSettings()
        binding.connectButton.setOnClickListener { toggleConnection() }
        binding.testButton.setOnClickListener {
            handleChat(ChatMessage("Crazy_Batto", "Das ist eine Testnachricht", true, true))
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

    private fun toggleConnection() {
        if (socket != null) {
            socket?.close(1000, "Manuell getrennt")
            socket = null
            binding.connectButton.text = "Verbinden"
            setStatus("Getrennt")
            return
        }
        saveSettings()
        val url = binding.socketUrl.text.toString().trim()
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
            setStatus("Gültige TikFinity-WebSocket-Adresse eingeben")
            return
        }
        val key = binding.apiKey.text.toString().trim()
        val room = binding.roomName.text.toString().trim()
        val requestBuilder = Request.Builder().url(url)
        if (key.isNotBlank()) requestBuilder.header("Authorization", "Bearer $key")
        if (room.isNotBlank()) requestBuilder.header("X-Live-Room", room)
        setStatus("Verbinde mit TikFinity …")
        socket = client.newWebSocket(requestBuilder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                runOnUiThread {
                    binding.connectButton.text = "Trennen"
                    setStatus("TikFinity verbunden")
                }
                val subscribe = JSONObject()
                    .put("action", "subscribe")
                    .put("room", room)
                    .put("apiKey", key)
                webSocket.send(subscribe.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                parseEvent(text)?.let(::handleChat)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                socket = null
                runOnUiThread {
                    binding.connectButton.text = "Verbinden"
                    setStatus("TikFinity getrennt")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                socket = null
                runOnUiThread {
                    binding.connectButton.text = "Verbinden"
                    setStatus("Chat-Verbindung fehlgeschlagen: ${t.message ?: "Unbekannter Fehler"}")
                }
            }
        })
    }

    private fun parseEvent(raw: String): ChatMessage? = runCatching {
        val root = JSONObject(raw)
        val payload = root.optJSONObject("data") ?: root.optJSONObject("payload") ?: root
        val type = root.optString("event", root.optString("type", payload.optString("type"))).lowercase()
        if (type.isNotBlank() && type !in listOf("chat", "comment", "chatmessage", "message")) return null
        val text = firstNonBlank(payload, "comment", "message", "text", "content")
        if (text.isBlank()) return null
        val username = firstNonBlank(payload, "nickname", "username", "uniqueId", "userName", "user")
            .ifBlank { "TikTok-Gast" }
        ChatMessage(
            username = username,
            text = text,
            moderator = payload.optBoolean("isModerator", payload.optBoolean("moderator", false)),
            subscriber = payload.optBoolean("isSubscriber", payload.optBoolean("subscriber", payload.optBoolean("isSub", false)))
        )
    }.getOrNull()

    private fun firstNonBlank(json: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val value = json.optString(key).trim()
            if (value.isNotBlank() && value != "null") return value
        }
        return ""
    }

    private fun handleChat(message: ChatMessage) {
        runOnUiThread {
            val line = TextView(this).apply {
                text = buildString {
                    if (message.moderator) append("[MOD] ")
                    if (message.subscriber) append("[SUB] ")
                    append(message.username).append(": ").append(message.text)
                }
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 17f
                setPadding(0, 8, 0, 8)
            }
            binding.chatMessages.addView(line, 0)
            while (binding.chatMessages.childCount > 30) {
                binding.chatMessages.removeViewAt(binding.chatMessages.childCount - 1)
            }
        }
        if (shouldSpeak(message)) speak(message)
    }

    private fun shouldSpeak(message: ChatMessage): Boolean {
        if (!binding.ttsEnabled.isChecked) return false
        return when (binding.filterSpinner.selectedItemPosition) {
            1 -> message.moderator
            2 -> message.subscriber
            3 -> message.moderator || message.subscriber
            4 -> binding.allowedUsers.text.toString().split(',')
                .map { it.trim().removePrefix("@").lowercase() }
                .filter { it.isNotBlank() }
                .contains(message.username.removePrefix("@").lowercase())
            else -> true
        }
    }

    private fun speak(message: ChatMessage) {
        val selectedVoice = voices.getOrNull(binding.voiceSpinner.selectedItemPosition)
        if (selectedVoice != null) tts.voice = selectedVoice
        tts.setSpeechRate((binding.speedBar.progress.coerceAtLeast(25) / 100f))
        tts.setPitch((binding.pitchBar.progress.coerceAtLeast(25) / 100f))
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, binding.ttsVolume.progress / 100f)
        }
        tts.speak("${message.username} sagt: ${message.text}", TextToSpeech.QUEUE_ADD, params, message.hashCode().toString())
    }

    private fun loadSettings() {
        val p = prefs()
        binding.socketUrl.setText(p.getString("tikfinity_url", "ws://127.0.0.1:21213/"))
        binding.apiKey.setText(p.getString("tikfinity_key", ""))
        binding.roomName.setText(p.getString("tikfinity_room", ""))
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
            .putString("tikfinity_url", binding.socketUrl.text.toString().trim())
            .putString("tikfinity_key", binding.apiKey.text.toString().trim())
            .putString("tikfinity_room", binding.roomName.text.toString().trim())
            .putBoolean("tts_enabled", binding.ttsEnabled.isChecked)
            .putInt("tts_filter", binding.filterSpinner.selectedItemPosition)
            .putInt("tts_speed", binding.speedBar.progress)
            .putInt("tts_pitch", binding.pitchBar.progress)
            .putInt("tts_volume", binding.ttsVolume.progress)
            .putString("tts_users", binding.allowedUsers.text.toString())
            .putString("tts_voice", selectedVoice?.name.orEmpty())
            .apply()
        setStatus("Chat-Einstellungen gespeichert")
    }

    private fun setStatus(text: String) {
        binding.chatStatus.text = text
    }

    private fun prefs() = getSharedPreferences("chat_settings", MODE_PRIVATE)

    override fun onDestroy() {
        socket?.close(1000, "Activity beendet")
        client.dispatcher.executorService.shutdown()
        tts.stop()
        tts.shutdown()
        super.onDestroy()
    }

    data class ChatMessage(
        val username: String,
        val text: String,
        val moderator: Boolean,
        val subscriber: Boolean
    )
}
