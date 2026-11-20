@file:OptIn(ExperimentalOneConfigUiApi::class)

package com.huddle.ui

import com.huddle.LinkConfig
import net.minecraft.client.Minecraft
import org.apache.logging.log4j.LogManager
import org.polyfrost.oneconfig.api.hud.v1.HudManager
import org.polyfrost.oneconfig.api.ui.v1.ExperimentalOneConfigUiApi

/** Registers Huddle's OneConfig HUD and handles the soundboard wheel's keybind. */
object HuddleUi {
    private val LOGGER = LogManager.getLogger("Huddle")

    @JvmStatic
    fun init() {
        runCatching { HudManager.register(VoiceHud()) }
            .onFailure { LOGGER.error("Couldn't add the voice overlay to OneConfig's HUD editor", it) }
    }

    /** The soundboard keybind (set in LinkConfig, so it can be changed in Huddle's settings). */
    @JvmStatic
    fun onSoundboardKey(pressed: Boolean): Boolean {
        if (!LinkConfig.enabled) return false
        val open = SoundboardWheel.open
        if (pressed) {
            if (open != null) {
                open.dismiss() // pressing again closes it
                return true
            }
            // only from the game itself, not on top of chat or the inventory
            if (Minecraft.getInstance().screen != null) return false
            SoundboardWheel.show(heldKey = true)
            return true
        }
        open?.keyReleased()
        return open != null
    }
}
