package com.huddle;

import org.apache.logging.log4j.LogManager;
import org.polyfrost.oneconfig.api.hud.v1.HudManager;
import org.polyfrost.oneconfig.api.hud.v1.HudResize;
import org.polyfrost.oneconfig.api.hud.v1.OneConfigHudWrapper;

/**
 * Puts the voice overlay in OneConfig's HUD editor. We keep drawing it ourselves; OneConfig handles
 * dragging, scaling, snapping and saving where it goes. (Notifications use OneConfig's own pop-ups.)
 */
public final class LinkHuds {

    /** One OneConfig HUD. Positions and scale are in GUI pixels; OneConfig saves them per profile. */
    public abstract static class Box implements OneConfigHudWrapper {
        private String id;
        private String name;
        public float x, y;
        public float scale = 1.0F;
        /** Unscaled size of what was last drawn (or the editor preview). */
        public float width, height;

        Box(String id, String name, float x, float y, float width, float height) {
            this.id = id;
            this.name = name;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        @Override public String getId() { return id; }
        @Override public void setId(String id) { this.id = id; }
        @Override public String getName() { return name; }
        @Override public void setName(String name) { this.name = name; }
        @Override public String getModId() { return "huddle"; }

        @Override public float getX() { return x; }
        @Override public void setX(float x) { this.x = x; }
        @Override public float getY() { return y; }
        @Override public void setY(float y) { this.y = y; }
        @Override public float getScale() { return scale; }
        @Override public void setScale(float scale) { this.scale = Math.max(0.25F, scale); }

        @Override public float getScaledWidth() { return width * scale; }
        @Override public void setScaledWidth(float w) { }
        @Override public float getScaledHeight() { return height * scale; }
        @Override public void setScaledHeight(float h) { }

        @Override public HudResize getResizeAxes() { return HudResize.None; }

        /** Hiding a HUD in the editor turns off the matching option in our config, and the reverse. */
        @Override public void save() { LinkConfig.INSTANCE.save(); }
    }

    public static Box voice;
    private static boolean tried;

    private LinkHuds() {}

    /** Registers on the first frame, once OneConfig is fully up. */
    static void ensureRegistered() {
        if (tried) return;
        tried = true;
        voice = new Box("huddle_voice", "Huddle Voice", 4, 60, 120, 60) {
            @Override public boolean getHidden() { return !LinkConfig.voiceOverlay; }
            @Override public void setHidden(boolean hidden) { LinkConfig.voiceOverlay = !hidden; }
        };
        try {
            voice.register();
        } catch (Throwable t) {
            // an OneConfig without this API: keep drawing at the default spot
            LogManager.getLogger("Huddle").error("Couldn't add the voice overlay to OneConfig's HUD editor", t);
        }
    }

    /** True while OneConfig's HUD editor (not the settings menu) is open. */
    static boolean editing() {
        try {
            return HudManager.INSTANCE.isEditorOpen();
        } catch (Throwable t) {
            return false;
        }
    }
}
