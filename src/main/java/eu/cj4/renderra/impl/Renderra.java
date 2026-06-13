package eu.cj4.renderra.impl;

import com.mojang.logging.LogUtils;
import eu.cj4.renderra.mixin.MixinChunkMap;
import eu.cj4.renderra.mixin.MixinTrackedEntity;
import eu.cj4.renderra.impl.network.FastFrameManipulate;
import eu.cj4.renderra.impl.network.ServerboundUpdateNBTPacket;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.slf4j.Logger;

import java.io.File;
import java.util.function.Predicate;

public class Renderra implements ModInitializer {

    public static final Logger LOGGER = LogUtils.getLogger();

    public static final File VIDEOS_FOLDER = new File("videoplayer");
    public static final String[] endings = {".mp4", ".mkv", ".mov", ".avi", ".flv", ".webm", ".ts", ".m2ts", ".ogv", ".wmv", ".gif", ".png", ".jpeg"};
    public static final Predicate<String> VIDEO_FILTER = name -> {
        for (String ending : Renderra.endings) {
            if (name.endsWith(ending)) return true;
        }
        return false;
    };
    public static final Predicate<String> SUBTITLE_FILTER = name -> name.endsWith(".srt");

    public static final Java2DFrameConverter IMAGE_CONVERTER = new Java2DFrameConverter();

    @Override
    public void onInitialize() {
        FastFrameManipulate.register();
        ServerboundUpdateNBTPacket.register();

        PolymerResourcePackUtils.addModAssets("renderra");
    }

    public static void sendPacketToAllNear(Entity entity, Identifier customPayloadIdentifier, Packet<?> moddedPacket, Packet<?> vanillaPacket) {
        for (ServerPlayerConnection connection : ((MixinTrackedEntity) ((MixinChunkMap) ((ServerChunkCache) entity.level().getChunkSource()).chunkMap).getEntityMap().get(entity.getId())).getSeenBy()) {
            if (ServerPlayNetworking.canSend(connection.getPlayer(), customPayloadIdentifier)) {
                connection.send(moddedPacket);
            } else {
                connection.send(vanillaPacket);
            }
        }
    }
}
