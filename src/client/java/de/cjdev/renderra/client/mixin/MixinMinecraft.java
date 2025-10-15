package de.cjdev.renderra.client.mixin;

import de.cjdev.renderra.client.VideoPlayerClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MixinMinecraft {

    @Unique
    private long videoplayer$lastNanos = System.nanoTime();

    @Inject(method = "runTick", at = @At("RETURN"))
    private void injectRunTick(boolean bl, CallbackInfo ci) {
        var nanos = System.nanoTime();
        double deltaSeconds = (nanos - videoplayer$lastNanos) / 1_000_000_000.0;

        if (VideoPlayerClient.UPDATE != null)
            VideoPlayerClient.UPDATE.accept((Minecraft) (Object) this, deltaSeconds);

        this.videoplayer$lastNanos = nanos;
    }
}
