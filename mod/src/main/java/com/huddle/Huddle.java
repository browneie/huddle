package com.huddle;

import net.fabricmc.api.ClientModInitializer;

public class Huddle implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        LinkConfig.INSTANCE.preload();
        LinkServer.start(LinkConfig.port);
    }
}
