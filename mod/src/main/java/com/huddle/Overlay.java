package com.huddle;

import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sound.instance.SimpleSoundInstance;
import net.minecraft.resource.Identifier;
import org.polyfrost.compose.render.PolyBitmap;
import org.polyfrost.oneconfig.api.notifications.v1.NotificationType;
import org.polyfrost.oneconfig.api.notifications.v1.Notifications;

/** Shows Discord notifications and Huddle's own notices through OneConfig. Render thread only. */
public final class Overlay {

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
        String[] notice;
        while ((notice = NOTICES.poll()) != null) showNotice(notice[0], notice[1]);
        Updater.Release update = Updater.takePending();
        if (update != null) showUpdate(update);

        // Always take notifications off the queue, so they don't pile up while turned off.
        LinkState.Notification n;
        while ((n = LinkState.pollNotification()) != null) {
            if (LinkConfig.enabled && LinkConfig.notifications) notify(mc, n);
        }
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
}
