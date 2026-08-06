package de.creatorhub.live

import android.content.Context

class SceneRepository(context: Context) {

    private val prefs = context.getSharedPreferences("creator_tools", Context.MODE_PRIVATE)

    data class Scene(
        val name: String,
        val filter: String,
        val overlayText: String,
        val showLogo: Boolean,
        val showText: Boolean,
        val showImage: Boolean,
        val showGifts: Boolean,
        val logoX: Int,
        val logoY: Int,
        val logoScale: Int,
        val textX: Int,
        val textY: Int,
        val textScale: Int,
        val imageX: Int,
        val imageY: Int,
        val imageScale: Int
    )

    fun loadActive(): Scene = load(prefs.getInt("active_scene", 0))

    fun load(index: Int): Scene {
        val safe = index.coerceIn(0, 4)
        val prefix = "scene_${safe}_"
        val defaultName = when (safe) {
            0 -> "Standard"
            1 -> "Gaming"
            2 -> "Talk"
            3 -> "Produkt"
            else -> "Eigene Szene"
        }
        return Scene(
            name = prefs.getString(prefix + "name", defaultName).orEmpty(),
            filter = prefs.getString(prefix + "filter", "none").orEmpty(),
            overlayText = prefs.getString(prefix + "text", "Creator Hub Live").orEmpty(),
            showLogo = prefs.getBoolean(prefix + "show_logo", true),
            showText = prefs.getBoolean(prefix + "show_text", false),
            showImage = prefs.getBoolean(prefix + "show_image", false),
            showGifts = prefs.getBoolean(prefix + "show_gifts", true),
            logoX = prefs.getInt(prefix + "logo_x", 6),
            logoY = prefs.getInt(prefix + "logo_y", 9),
            logoScale = prefs.getInt(prefix + "logo_scale", 75),
            textX = prefs.getInt(prefix + "text_x", 8),
            textY = prefs.getInt(prefix + "text_y", 72),
            textScale = prefs.getInt(prefix + "text_scale", 100),
            imageX = prefs.getInt(prefix + "image_x", 55),
            imageY = prefs.getInt(prefix + "image_y", 12),
            imageScale = prefs.getInt(prefix + "image_scale", 80)
        )
    }

    fun save(index: Int, scene: Scene) {
        val safe = index.coerceIn(0, 4)
        val prefix = "scene_${safe}_"
        prefs.edit()
            .putString(prefix + "name", scene.name)
            .putString(prefix + "filter", scene.filter)
            .putString(prefix + "text", scene.overlayText)
            .putBoolean(prefix + "show_logo", scene.showLogo)
            .putBoolean(prefix + "show_text", scene.showText)
            .putBoolean(prefix + "show_image", scene.showImage)
            .putBoolean(prefix + "show_gifts", scene.showGifts)
            .putInt(prefix + "logo_x", scene.logoX)
            .putInt(prefix + "logo_y", scene.logoY)
            .putInt(prefix + "logo_scale", scene.logoScale)
            .putInt(prefix + "text_x", scene.textX)
            .putInt(prefix + "text_y", scene.textY)
            .putInt(prefix + "text_scale", scene.textScale)
            .putInt(prefix + "image_x", scene.imageX)
            .putInt(prefix + "image_y", scene.imageY)
            .putInt(prefix + "image_scale", scene.imageScale)
            .apply()
    }

    fun setActive(index: Int) {
        prefs.edit().putInt("active_scene", index.coerceIn(0, 4)).apply()
    }

    fun activeIndex(): Int = prefs.getInt("active_scene", 0).coerceIn(0, 4)
}
