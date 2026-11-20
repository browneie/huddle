package com.huddle;

import org.polyfrost.oneconfig.api.config.v1.Config;
import org.polyfrost.oneconfig.api.config.v1.annotations.Keybind;
import org.polyfrost.oneconfig.api.config.v1.annotations.Number;
import org.polyfrost.oneconfig.api.config.v1.annotations.Slider;
import org.polyfrost.oneconfig.api.config.v1.annotations.Switch;
import org.polyfrost.oneconfig.api.ui.v1.keybind.KeybindHelper;
import org.polyfrost.oneconfig.api.ui.v1.keybind.OneConfigKeybind;

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

    @Switch(title = "Voice Overlay", description = "Show who's in your voice channel and whether they're muted or deafened. Move, resize and restyle it in OneConfig's HUD editor, where it's called Huddle Voice.",
            subcategory = "Voice Overlay")
    public static boolean voiceOverlay = true;

    /** OneConfig's key code for J (its codes follow USB HID, not LWJGL). */
    private static final int KEY_J = 13;

    @Keybind(title = "Open Soundboard", description = "Hold to open the wheel and let go to play what you're pointing at, or tap and click. Also in Minecraft's Controls, under Huddle.",
            subcategory = "Soundboard Wheel")
    public static OneConfigKeybind soundboardKey = KeybindHelper.builder()
            .key(KEY_J)
            .name("Open Soundboard")
            .category("Huddle")
            .inScreens() // so letting go is seen while the wheel is open
            .action((kotlin.jvm.functions.Function1<Boolean, Boolean>) com.huddle.ui.HuddleUi::onSoundboardKey)
            .register();

    @Slider(title = "Background Dim", description = "How dark the screen behind the soundboard wheel gets, in percent.",
            subcategory = "Soundboard Wheel", min = 0.0F, max = 100.0F, step = 5.0F)
    public static float wheelBackgroundDim = 50.0F;

    @Slider(title = "Wheel Opacity", description = "How solid the wheel's ring and sound tiles are, in percent.",
            subcategory = "Soundboard Wheel", min = 10.0F, max = 100.0F, step = 5.0F)
    public static float wheelOpacity = 95.0F;

    @Switch(title = "Check for Updates", description = "Look for a new Huddle release on GitHub when the game starts.",
            subcategory = "Updates")
    public static boolean checkForUpdates = true;

    @Switch(title = "Install Updates Automatically", description = "Download new releases without asking. They load the next time you start the game. Only jars whose checksum matches the one GitHub lists are installed.",
            subcategory = "Updates")
    public static boolean autoInstallUpdates = false;

    private LinkConfig() {
        super("huddle.json", "Huddle", Category.QOL);
        for (String option : new String[]{"notifications", "sound", "notificationSeconds", "voiceOverlay",
                "wheelBackgroundDim", "wheelOpacity"}) {
            addDependency(option, "enabled");
        }
        addDependency("autoInstallUpdates", "checkForUpdates");
        addDependency("sound", "notifications");
        addDependency("notificationSeconds", "notifications");
    }
}
