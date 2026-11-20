@file:OptIn(ExperimentalOneConfigUiApi::class, ExperimentalComposeUiApi::class)

package com.huddle.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.google.gson.JsonObject
import com.huddle.LinkConfig
import com.huddle.LinkServer
import com.huddle.LinkState
import org.polyfrost.compose.backend.asComposeImageBitmap
import org.polyfrost.compose.render.PolyBitmap
import org.polyfrost.oneconfig.api.ui.v1.ExperimentalOneConfigUiApi
import org.polyfrost.oneconfig.api.ui.v1.components.Text
import org.polyfrost.oneconfig.api.ui.v1.platform.OneConfigComposeScreen
import org.polyfrost.oneconfig.api.ui.v1.platform.OneConfigScreens
import org.polyfrost.oneconfig.api.ui.v1.theme.OneConfigTheme
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** Sounds per page, one per spoke: the same eight-way wheel as Poly+'s. */
private const val PER_PAGE = 8
/** Holding the key longer than this and letting go plays whatever you're pointing at. */
private const val HOLD_TO_PICK_NANOS = 300_000_000L

private val RING_SIZE = 360.dp
private val RING_STROKE = 96.dp
private val TILE_RADIUS = 140.dp
private val TILE_W = 118.dp
private val TILE_H = 42.dp
private val DEAD_ZONE = 44.dp

/**
 * The soundboard wheel. Point at a sound and click (or let go of the key you're holding) to play it in
 * your call; scroll for the next eight; Escape or the middle closes it.
 */
class SoundboardWheel(private val openedAtNanos: Long, private val heldKey: Boolean) :
    OneConfigComposeScreen(previous = null, renderMode = RenderMode.Continuous) {

    override val panoramaBackground: Boolean get() = false
    override val dimBackground: Boolean get() = false // we draw our own, at the chosen strength
    override val pausesGame: Boolean get() = false

    /** Index on the current page the pointer is aiming at, or -1 for the middle. */
    private var aimed by mutableIntStateOf(-1)
    private var page by mutableIntStateOf(0)
    private var board by mutableStateOf<LinkState.Soundboard?>(null)

    init {
        LinkState.clearSoundboard()
        val asked = LinkServer.send(JsonObject().apply { addProperty("type", "soundboard_request") })
        if (!asked) board = LinkState.Soundboard(false, "Discord isn't connected. Open Discord with the Huddle plugin turned on.", emptyList())
    }

    private fun pageSounds(b: LinkState.Soundboard?): List<LinkState.Sound> {
        val all = b?.sounds ?: return emptyList()
        val from = page * PER_PAGE
        return if (from >= all.size) emptyList() else all.subList(from, minOf(from + PER_PAGE, all.size))
    }

    private fun pages(b: LinkState.Soundboard?) = maxOf(1, ((b?.sounds?.size ?: 0) + PER_PAGE - 1) / PER_PAGE)

    /** Plays the aimed sound, if any, and closes the wheel. */
    fun pick() {
        val sound = pageSounds(board).getOrNull(aimed)
        if (sound != null) {
            LinkServer.send(JsonObject().apply {
                addProperty("type", "play_sound")
                addProperty("id", sound.id)
                if (sound.guild != null) addProperty("guild", sound.guild)
            })
        }
        dismiss()
    }

    /** Called when the wheel's key is let go: a long hold picks, a tap leaves the wheel open to click. */
    fun keyReleased() {
        if (heldKey && System.nanoTime() - openedAtNanos > HOLD_TO_PICK_NANOS) pick()
    }

    override fun onClosed() {
        if (open === this) open = null
    }

    @Composable
    override fun Content() {
        val theme = OneConfigTheme.current
        var shown by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { shown = true }
        val grow by animateFloatAsState(if (shown) 1f else 0f, tween(160))
        val deadPx = with(LocalDensity.current) { DEAD_ZONE.toPx() }

        // the list arrives from Discord a moment after opening
        LaunchedEffect(Unit) {
            while (board == null) {
                withFrameNanos { }
                LinkState.soundboard()?.let { board = it }
            }
        }

        var size by remember { mutableStateOf(IntSize.Zero) }
        val b = board
        val sounds = pageSounds(b)
        val pageCount = pages(b)

        val dim = (LinkConfig.wheelBackgroundDim / 100f).coerceIn(0f, 1f)
        val solid = (LinkConfig.wheelOpacity / 100f).coerceIn(0.1f, 1f)
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = dim * grow))
                .onSizeChanged { size = it }
                .onPointerEvent(PointerEventType.Move) { e ->
                    val p = e.changes.firstOrNull()?.position ?: return@onPointerEvent
                    aimed = aim(p, size, sounds.size, deadPx)
                }
                .onPointerEvent(PointerEventType.Press) { pick() }
                .onPointerEvent(PointerEventType.Scroll) { e ->
                    val dy = e.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                    if (dy != 0f && pageCount > 1) {
                        page = Math.floorMod(page + if (dy > 0) 1 else -1, pageCount)
                        aimed = -1
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(RING_SIZE).scale(0.92f + 0.08f * grow).alpha(grow),
                contentAlignment = Alignment.Center,
            ) {
                // the dark ring the tiles sit on
                Box(Modifier.fillMaxSize().drawBehind {
                    val stroke = RING_STROKE.toPx()
                    drawCircle(
                        color = Color(0xFF101014).copy(alpha = 0.9f * solid),
                        radius = (this.size.minDimension - stroke) / 2f,
                        style = Stroke(width = stroke),
                    )
                })

                for ((i, sound) in sounds.withIndex()) {
                    val angle = -PI / 2 + i * (2 * PI / PER_PAGE)
                    val dx = (cos(angle) * TILE_RADIUS.value).toFloat().dp
                    val dy = (sin(angle) * TILE_RADIUS.value).toFloat().dp
                    Tile(sound, aimed == i, solid, Modifier.offset(dx, dy))
                }

                Center(b, sounds.getOrNull(aimed), page, pageCount, theme.textColor, theme.textColorSecondary)
            }
        }
    }

    @Composable
    private fun Tile(sound: LinkState.Sound, aimedAt: Boolean, solid: Float, modifier: Modifier) {
        val theme = OneConfigTheme.current
        val lift by animateFloatAsState(if (aimedAt) 1f else 0f, tween(90))
        val shape = RoundedCornerShape(10.dp)
        Row(
            modifier
                .width(TILE_W)
                .size(TILE_W, TILE_H)
                .scale(1f + 0.06f * lift)
                .background(Color(0xFF1B1B21).copy(alpha = if (aimedAt) maxOf(solid, 0.9f) else solid), shape)
                .border(1.dp, if (aimedAt) OneConfigTheme.accent else Color.White.copy(alpha = 0.06f), shape)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val image = rememberEmoji(sound)
            if (image != null) Image(image, null, Modifier.size(18.dp))
            Text(
                sound.name,
                color = if (aimedAt) theme.textColor else theme.textColor.copy(alpha = 0.85f),
                style = OneConfigTheme.typography.smallMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                legacyFormatting = false,
            )
        }
    }

    @Composable
    private fun Center(b: LinkState.Soundboard?, aimedSound: LinkState.Sound?, page: Int, pages: Int, text: Color, muted: Color) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when {
                b == null -> Text("Loading sounds…", color = muted, style = OneConfigTheme.typography.small)
                !b.available || b.sounds.isEmpty() -> Text(
                    b.reason ?: "No sounds to play here.",
                    Modifier.width(150.dp),
                    color = muted,
                    style = OneConfigTheme.typography.small,
                    textAlign = TextAlign.Center,
                    legacyFormatting = false,
                )
                aimedSound != null -> {
                    Text(aimedSound.name, color = text, style = OneConfigTheme.typography.bodyMedium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, legacyFormatting = false, modifier = Modifier.width(150.dp),
                        textAlign = TextAlign.Center)
                    Text(aimedSound.group, color = muted, style = OneConfigTheme.typography.caption, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, legacyFormatting = false)
                }
                else -> {
                    CloseMark(text)
                    if (pages > 1) Text("Scroll for more · ${page + 1}/$pages", color = muted, style = OneConfigTheme.typography.caption)
                }
            }
        }
    }

    @Composable
    private fun CloseMark(color: Color) {
        Box(Modifier.size(16.dp).drawBehind {
            val w = 1.6.dp.toPx()
            drawLine(color, Offset(0f, 0f), Offset(this.size.width, this.size.height), w, StrokeCap.Round)
            drawLine(color, Offset(this.size.width, 0f), Offset(0f, this.size.height), w, StrokeCap.Round)
        })
    }

    companion object {
        /** The open wheel, if any. */
        @JvmStatic
        var open: SoundboardWheel? = null
            private set

        private val emoji = HashMap<String, ImageBitmap?>()

        @JvmStatic
        fun show(heldKey: Boolean) {
            if (open != null) return
            val wheel = SoundboardWheel(System.nanoTime(), heldKey)
            open = wheel
            OneConfigScreens.display(wheel)
        }

        @Composable
        private fun rememberEmoji(sound: LinkState.Sound): ImageBitmap? = remember(sound.id) {
            emoji.getOrPut(sound.id) {
                sound.image?.let { bytes -> runCatching { PolyBitmap.decode(bytes)?.asComposeImageBitmap() }.getOrNull() }
            }
        }

        /** Which spoke the pointer is aiming at, by its angle from the middle; -1 inside the dead zone. */
        private fun aim(p: Offset, size: IntSize, count: Int, deadPx: Float): Int {
            if (count == 0 || size == IntSize.Zero) return -1
            val dx = p.x - size.width / 2f
            val dy = p.y - size.height / 2f
            if (hypot(dx, dy) < deadPx) return -1
            var a = atan2(dy, dx) + PI / 2 // 0 at the top, clockwise
            if (a < 0) a += 2 * PI
            val index = (a / (2 * PI / PER_PAGE)).roundToInt() % PER_PAGE
            return if (index < count) index else -1
        }
    }
}
