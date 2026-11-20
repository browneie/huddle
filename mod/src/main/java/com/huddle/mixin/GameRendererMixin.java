package com.huddle.mixin;

import com.huddle.Overlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws after everything else, screens included, so notifications show over menus and chat too. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Shadow private Minecraft minecraft;

    @Inject(method = "render(FJ)V", at = @At("TAIL"))
    private void huddle$render(float tickDelta, long startTime, CallbackInfo ci) {
        Overlay.render(this.minecraft);
    }
}
