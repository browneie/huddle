package com.huddle;

import org.polyfrost.oneconfig.api.config.v1.Config;
import org.polyfrost.oneconfig.api.config.v1.annotations.Number;
import org.polyfrost.oneconfig.api.config.v1.annotations.Slider;
import org.polyfrost.oneconfig.api.config.v1.annotations.Switch;

public class LinkConfig extends Config {

    public static final LinkConfig INSTANCE = new LinkConfig();

    @Switch(title = "Enabled")
    public static boolean enabled = true;

    @Number(title = "Port", description = "Must match the Huddle Equicord plugin's Port setting. Restart the game after changing it.",
            min = 1024.0F, max = 65535.0F)
    public static int port = 25599;

    @Switch(title = "Notifications", description = "Show Discord DMs and mentions as OneConfig notifications. Which Discord statuses send them is set in the Equicord plugin.",
            subcategory = "Notifications")
    public static boolean notifications = true;

    @Switch(title = "Sound", description = "Play a ding when a notification arrives.", subcategory = "Notifications")
    public static boolean sound = true;

    @Slider(title = "Duration", description = "Seconds each notification stays on screen.", subcategory = "Notifications",
            min = 2.0F, max = 15.0F, step = 1.0F)
    public static float notificationSeconds = 5.0F;

    @Switch(title = "Voice Overlay", description = "Show who's in your voice channel and whether they're muted or deafened. Move and resize it in OneConfig's HUD editor.",
            subcategory = "Voice Overlay")
    public static boolean voiceOverlay = true;

    @Switch(title = "Background", subcategory = "Voice Overlay")
    public static boolean overlayBackground = true;

    private LinkConfig() {
        super("huddle.json", "Huddle", Category.QOL);
        for (String option : new String[]{"notifications", "sound", "notificationSeconds", "voiceOverlay",
                "overlayBackground"}) {
            addDependency(option, "enabled");
        }
        addDependency("sound", "notifications");
        addDependency("notificationSeconds", "notifications");
        for (String option : new String[]{"overlayBackground"}) {
            addDependency(option, "voiceOverlay");
        }
    }
}
