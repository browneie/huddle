package com.huddle;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiElement;
import net.minecraft.client.render.TextRenderer;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.client.sound.instance.SimpleSoundInstance;
import net.minecraft.resource.Identifier;
import org.polyfrost.compose.render.PolyBitmap;
import org.polyfrost.oneconfig.api.notifications.v1.NotificationType;
import org.polyfrost.oneconfig.api.notifications.v1.Notifications;

/** Shows notifications through OneConfig and draws the voice-call overlay. Render thread only. */
public final class Overlay {

    // Discord-ish palette
    private static final int WHITE = 0xFFFFFFFF;
    private static final int TEXT = 0xFFDCDDDE;
    private static final int MUTED_TEXT = 0xFF96989D;
    private static final int RED = 0xFFED4245;
    private static final int GREEN = 0xFF3BA55C;
    private static final int IDLE_DOT = 0xFF4F545C;

    private static final int MAX_VOICE_ROWS = 12;
    private static final int ROW_HEIGHT = 11;
    private static final int PAD = 4;

    /** Shown in OneConfig's HUD editor when you're not in a call, so there's something to place. */
    private static final LinkState.Voice SAMPLE_VOICE = new LinkState.Voice("Voice Channel", "Discord", Arrays.asList(
            new LinkState.Member("You", true, false, false, false, false, true, false),
            new LinkState.Member("Muted friend", false, true, false, false, false, false, false),
            new LinkState.Member("Deafened friend", false, true, true, false, false, false, false),
            new LinkState.Member("Streaming friend", false, false, false, false, false, false, true)));

    private static PolyBitmap icon;
    private static boolean iconLoaded;

    /** Huddle's own notices (updates, version mismatches), posted from any thread. */
    private static final ConcurrentLinkedQueue<String[]> NOTICES = new ConcurrentLinkedQueue<>();

    /** Shows a notice of Huddle's own on the next frame. Safe from any thread. */
    static void notifyLater(String title, String message) {
        NOTICES.add(new String[]{title, message});
    }

    private Overlay() {}

    public static void render(Minecraft mc) {
        LinkHuds.ensureRegistered();

        String[] notice;
        while ((notice = NOTICES.poll()) != null) showNotice(notice[0], notice[1]);
        Updater.Release update = Updater.takePending();
        if (update != null) showUpdate(update);

        // Always take notifications off the queue, so they don't pile up while turned off.
        LinkState.Notification n;
        while ((n = LinkState.pollNotification()) != null) {
            if (LinkConfig.enabled && LinkConfig.notifications) notify(mc, n);
        }
        if (!LinkConfig.enabled || !LinkConfig.voiceOverlay) return;

        LinkState.Voice voice = LinkState.voice();
        if (LinkHuds.editing() && (voice == null || voice.members.isEmpty())) voice = SAMPLE_VOICE;
        if (voice == null || mc.options.hideGui && mc.screen == null) return;

        mc.gameRenderer.setupGuiState();
        GlStateManager.clear(256); // depth, so screen items rendered in front don't hide us
        GlStateManager.enableBlend();
        GlStateManager.color4f(1.0F, 1.0F, 1.0F, 1.0F);
        renderVoice(mc.textRenderer, voice);
        GlStateManager.color4f(1.0F, 1.0F, 1.0F, 1.0F);
    }

    // ---------- notifications ----------

    /** Hands the message to OneConfig's notifications, the same pop-ups OneConfig and Poly+ use. */
    private static void notify(Minecraft mc, LinkState.Notification n) {
        // "Direct Message" adds nothing next to a name; server channels and group names do
        String title = n.subtitle.isEmpty() || n.subtitle.equals("Direct Message") ? n.title : n.title + " \u00b7 " + n.subtitle;
        String message = n.body.isEmpty() ? "sent a message" : n.body;
        try {
            if (!iconLoaded) {
                iconLoaded = true;
                icon = Notifications.icon("/assets/huddle/message.svg", 64);
            }
            Notifications.Builder builder = Notifications.builder(title, message)
                    .duration(LinkConfig.notificationSeconds * 1000.0F);
            if (icon != null) builder.icon(icon);
            builder.send();
        } catch (Throwable t) {
            LinkServer.LOGGER.error("OneConfig couldn't show a notification", t);
            return;
        }
        if (LinkConfig.sound) {
            mc.getSoundManager().play(SimpleSoundInstance.of(new Identifier("random.orb"), 1.6F));
        }
    }

    private static void showNotice(String title, String message) {
        try {
            Notifications.builder(title, message).type(NotificationType.INFO).duration(8000.0F).send();
        } catch (Throwable t) {
            LinkServer.LOGGER.info("{}: {}", title, message);
        }
    }

    /** "Huddle x.y.z is out", with Update (or View, when it can't be installed from here). */
    private static void showUpdate(Updater.Release r) {
        boolean installable = Updater.installable(r);
        String message = "You have " + Updater.version() + "."
                + (installable ? " Update now and it'll load next launch." : " Download it from GitHub.");
        try {
            Notifications.Builder b = Notifications.builder("Huddle " + r.version + " is available", message)
                    .type(NotificationType.INFO)
                    .duration(20000.0F);
            if (installable) b.action("Update", () -> Updater.installInBackground(r));
            b.action("View", () -> openLink(r.pageUrl));
            b.send();
        } catch (Throwable t) {
            LinkServer.LOGGER.info("Huddle {} is available: {}", r.version, r.pageUrl);
        }
    }

    private static void openLink(String url) {
        try {
            org.lwjgl.Sys.openURL(url);
        } catch (Throwable t) {
            LinkServer.LOGGER.info("Open {} in your browser", url);
        }
    }

    // ---------- voice overlay ----------

    private static final int ICON = 9;
    private static final int ICON_GAP = 2;
    private static final int NAME_GAP = 10;
    private static final int ICON_GRAY = 0xFFB9BBBE;

    // 9x9 pixel art, '#' = filled
    private static final String[] MIC = {
            "...###...",
            "..#####..",
            "..#####..",
            "..#####..",
            "#.#####.#",
            "#..###..#",
            ".##...##.",
            "....#....",
            "..#####..",
    };
    private static final String[] HEADPHONES = {
            "..#####..",
            ".#.....#.",
            "#.......#",
            "#.......#",
            "#.......#",
            "###...###",
            "###...###",
            "###...###",
            ".##...##.",
    };

    /** Where the voice overlay goes and how big it is, in screen GUI pixels (x, y) and unscaled size. */
    static final class VoiceLayout {
        String header;
        int rows, width, height;
        boolean overflow;
        float x, y, scale;

        float right() { return x + width * scale; }
        float bottom() { return y + height * scale; }
    }

    static VoiceLayout layoutVoice(TextRenderer font, LinkState.Voice voice) {
        VoiceLayout l = new VoiceLayout();
        List<LinkState.Member> members = voice.members;
        l.rows = Math.min(members.size(), MAX_VOICE_ROWS);
        l.overflow = members.size() > MAX_VOICE_ROWS;
        l.header = voice.guild.isEmpty() ? voice.channel : voice.channel + " \u00b7 " + voice.guild;

        // names in one column, icons right-aligned in another, so names never shift
        int nameCol = 0;
        int iconCol = 0;
        for (int i = 0; i < l.rows; i++) {
            LinkState.Member m = members.get(i);
            nameCol = Math.max(nameCol, font.getWidth(m.name));
            iconCol = Math.max(iconCol, rightWidth(font, m));
        }
        l.width = Math.max(font.getWidth(l.header), 10 + nameCol + (iconCol > 0 ? NAME_GAP + iconCol : 0)) + PAD * 2;
        l.height = PAD + 10 + l.rows * ROW_HEIGHT + (l.overflow ? ROW_HEIGHT : 0) + PAD - 2;

        LinkHuds.Box box = LinkHuds.voice;
        l.scale = box.scale;
        l.x = box.x;
        l.y = box.y;
        // tell OneConfig how big we are, so its editor outline and snapping match
        box.width = l.width;
        box.height = l.height;
        return l;
    }

    private static void renderVoice(TextRenderer font, LinkState.Voice voice) {
        drawVoice(font, layoutVoice(font, voice), voice, LinkConfig.overlayBackground);
    }

    static void drawVoice(TextRenderer font, VoiceLayout l, LinkState.Voice voice, boolean background) {
        GlStateManager.pushMatrix();
        GlStateManager.translatef(l.x, l.y, 0.0F);
        GlStateManager.scalef(l.scale, l.scale, 1.0F);

        if (background) GuiElement.fill(0, 0, l.width, l.height, 0x90000000);
        font.drawWithShadow(font.trim(l.header, l.width - PAD * 2), PAD, PAD, MUTED_TEXT);

        int ry = PAD + 10;
        for (int i = 0; i < l.rows; i++) {
            drawMember(font, voice.members.get(i), PAD, ry, l.width - PAD);
            ry += ROW_HEIGHT;
        }
        if (l.overflow) font.drawWithShadow("+" + (voice.members.size() - MAX_VOICE_ROWS) + " more", PAD, ry + 1, MUTED_TEXT);

        GlStateManager.popMatrix();
    }

    private static void drawMember(TextRenderer font, LinkState.Member m, int x, int y, int right) {
        // speaking light: green ring while talking, dim dot otherwise
        boolean speaking = m.speaking && !m.muted && !m.serverMuted && !m.deafened && !m.serverDeafened;
        if (speaking) {
            GuiElement.fill(x, y + 1, x + 7, y + 8, GREEN);
            GuiElement.fill(x + 2, y + 3, x + 5, y + 6, 0xFF2F3136);
        } else {
            GuiElement.fill(x + 1, y + 2, x + 6, y + 7, IDLE_DOT);
        }
        font.drawWithShadow(m.name, x + 10, y + 1, speaking ? WHITE : TEXT);

        // right to left: headphones, mic, LIVE
        int ix = right;
        if (m.deafened || m.serverDeafened) {
            ix -= ICON;
            drawIcon(HEADPHONES, ix, y, m.serverDeafened ? RED : ICON_GRAY, true);
            ix -= ICON_GAP;
        }
        if (m.muted || m.serverMuted) {
            ix -= ICON;
            drawIcon(MIC, ix, y, m.serverMuted ? RED : ICON_GRAY, true);
            ix -= ICON_GAP;
        }
        if (m.streaming) {
            int lw = font.getWidth("LIVE") + 3;
            ix -= lw;
            GuiElement.fill(ix, y, ix + lw, y + 9, RED);
            font.draw("LIVE", ix + 2, y + 1, WHITE);
        }
    }

    private static int rightWidth(TextRenderer font, LinkState.Member m) {
        int w = 0;
        if (m.deafened || m.serverDeafened) w += ICON + ICON_GAP;
        if (m.muted || m.serverMuted) w += ICON + ICON_GAP;
        if (m.streaming) w += font.getWidth("LIVE") + 3;
        else if (w > 0) w -= ICON_GAP;
        return w;
    }

    /** Draws pixel art with a red slash from top-left to bottom-right. */
    private static void drawIcon(String[] art, int x, int y, int color, boolean slashed) {
        for (int row = 0; row < art.length; row++) {
            String line = art[row];
            for (int col = 0; col < line.length(); col++) {
                if (line.charAt(col) == '#') GuiElement.fill(x + col, y + row, x + col + 1, y + row + 1, color);
            }
        }
        if (!slashed) return;
        for (int i = 0; i < ICON; i++) {
            // dark edge keeps the slash readable over a same-colored icon
            if (i + 1 < ICON) GuiElement.fill(x + i + 1, y + i, x + i + 2, y + i + 1, 0xFF202225);
            GuiElement.fill(x + i, y + i, x + i + 1, y + i + 1, RED);
        }
    }
}
