package de.cjdev.renderra.mixin;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.network.ServerPlayerConnection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;

@Mixin(ChunkMap.TrackedEntity.class)
public interface MixinTrackedEntity {
    @Accessor("seenBy")
    Set<ServerPlayerConnection> getSeenBy();
}
