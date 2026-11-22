package com.huddle.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.huddle.LinkConfig
import com.huddle.LinkState
import org.polyfrost.compose.composables.PolyBox
import org.polyfrost.compose.composables.PolyCanvas
import org.polyfrost.compose.composables.PolyColumn
import org.polyfrost.compose.composables.PolyModifier
import org.polyfrost.compose.composables.PolyRow
import org.polyfrost.compose.composables.PolySpacer
import org.polyfrost.compose.composables.PolyText
import org.polyfrost.compose.composables.background
import org.polyfrost.compose.composables.padding
import org.polyfrost.compose.composables.size
import org.polyfrost.compose.render.PolyColor
import org.polyfrost.compose.render.RenderContext
import org.polyfrost.oneconfig.api.hud.v1.Hud
import org.polyfrost.oneconfig.api.hud.v1.HudManager

/**
 * The voice overlay as a OneConfig HUD, drawn with the same toolkit as Poly+'s party voice HUD. Move,
 * scale, restyle and hide it in OneConfig's HUD editor; its Show Background and Shadow settings apply here.
 */
class VoiceHud : Hud("huddle_voice", "Huddle Voice", Category.INFO) {

    override val description: String get() = "Who's in your Discord call, and who's muted, deafened, talking or live."

    private var voice by mutableStateOf<LinkState.Voice?>(null)

    override fun update(): Boolean {
        var next = if (LinkConfig.enabled && LinkConfig.voiceOverlay) LinkState.voice() else null
        if (next == null && HudManager.isEditing) next = SAMPLE
        if (next === voice) return false
        voice = next
        return true
    }

    override fun updateFrequency(): Long = 100_000_000L // 100 ms, like Poly+'s

    override fun shouldShow(): Boolean = voice != null

    override fun multipleInstancesAllowed(): Boolean = false

    override fun showByDefault(): Boolean = true

    override fun defaultPosition(): Pair<Float, Float> = 4f to 60f

    @Composable
    override fun Content() {
        val v = voice ?: return
        PolyColumn(gap = 3f, modifier = hudBackground(PolyModifier.padding(6f))) {
            val header = if (v.guild.isEmpty()) v.channel else "${v.channel} · ${v.guild}"
            Label(header, MUTED, 9f)
            for (m in v.members.take(MAX_ROWS)) Member(m)
            if (v.members.size > MAX_ROWS) Label("+${v.members.size - MAX_ROWS} more", MUTED, 9f)
        }
    }

    @Composable
    private fun Member(m: LinkState.Member) {
        val speaking = m.speaking && !m.muted && !m.serverMuted && !m.deafened && !m.serverDeafened
        PolyRow(gap = 5f) {
            PolyCanvas(PolyModifier.size(8f, 11f)) { x, y, _, h ->
                val top = y + (h - 8f) / 2f
                if (speaking) {
                    rect(x, top, 8f, 8f, GREEN, 4f)
                    rect(x + 2f, top + 2f, 4f, 4f, SPEAKING_HOLE, 2f)
                } else {
                    rect(x + 1f, top + 1f, 6f, 6f, IDLE, 3f)
                }
            }
            Label(m.name, if (speaking) PolyColor.WHITE else TEXT, 11f)
            val muted = m.muted || m.serverMuted
            val deaf = m.deafened || m.serverDeafened
            if (muted || deaf || m.streaming) PolySpacer(width = 4f)
            if (m.streaming) {
                PolyBox(PolyModifier.background(RED, 3f).padding(horizontal = 3f, vertical = 1f)) {
                    Label("LIVE", PolyColor.WHITE, 8f)
                }
            }
            if (muted) Icon(MIC, if (m.serverMuted) RED else ICON)
            if (deaf) Icon(HEADPHONES, if (m.serverDeafened) RED else ICON)
        }
    }

    /** Text that follows the Shadow setting in OneConfig's HUD editor (Style → Shadow). */
    @Composable
    private fun Label(text: String, color: PolyColor, size: Float) {
        PolyText(text, color = color, fontSize = size, shadow = showShadow, shadowColor = PolyColor(shadowColor),
            shadowOffset = maxOf(shadowOffsetX, 1f) * size / 12f)
    }

    @Composable
    private fun Icon(art: Array<String>, color: PolyColor) {
        PolyCanvas(PolyModifier.size(9f, 11f)) { x, y, _, h -> drawIcon(art, x, y + (h - 9f) / 2f, color) }
    }

    private fun RenderContext.drawIcon(art: Array<String>, x: Float, y: Float, color: PolyColor) {
        for ((row, line) in art.withIndex()) {
            for ((col, c) in line.withIndex()) if (c == '#') rect(x + col, y + row, 1f, 1f, color)
        }
        // the slash: red, with a dark edge so it reads on a red icon too
        for (i in 0 until 9) {
            if (i < 8) rect(x + i + 1, y + i, 1f, 1f, SLASH_EDGE)
            rect(x + i, y + i, 1f, 1f, RED)
        }
    }

    private companion object {
        const val MAX_ROWS = 12

        val TEXT = PolyColor(0xFFDCDDDE.toInt())
        val MUTED = PolyColor(0xFF96989D.toInt())
        val RED = PolyColor(0xFFED4245.toInt())
        val GREEN = PolyColor(0xFF3BA55C.toInt())
        val IDLE = PolyColor(0xFF4F545C.toInt())
        val ICON = PolyColor(0xFFB9BBBE.toInt())
        val SPEAKING_HOLE = PolyColor(0xFF1B1B21.toInt())
        val SLASH_EDGE = PolyColor(0xFF202225.toInt())

        val MIC = arrayOf(
            "...###...",
            "..#####..",
            "..#####..",
            "..#####..",
            "#.#####.#",
            "#..###..#",
            ".##...##.",
            "....#....",
            "..#####..",
        )
        val HEADPHONES = arrayOf(
            "..#####..",
            ".#.....#.",
            "#.......#",
            "#.......#",
            "#.......#",
            "###...###",
            "###...###",
            "###...###",
            ".##...##.",
        )

        /** Shown in the HUD editor when you're not in a call, so there's something to place. */
        val SAMPLE = LinkState.Voice("Voice Channel", "Discord", listOf(
            LinkState.Member("You", true, false, false, false, false, true, false),
            LinkState.Member("Muted friend", false, true, false, false, false, false, false),
            LinkState.Member("Deafened friend", false, true, true, false, false, false, false),
            LinkState.Member("Streaming friend", false, false, false, false, false, false, true),
        ))
    }
}
