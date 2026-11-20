package com.huddle;

import net.fabricmc.api.ClientModInitializer;

public class Huddle implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        Updater.removeLeftovers();
        LinkConfig.INSTANCE.preload();
        LinkServer.start(LinkConfig.port);
        Updater.checkInBackground();
    }
}
