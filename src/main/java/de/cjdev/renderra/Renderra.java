package de.cjdev.renderra;

import com.mojang.logging.LogUtils;
import de.cjdev.renderra.mixin.MixinChunkMap;
import de.cjdev.renderra.mixin.MixinTrackedEntity;
import de.cjdev.renderra.network.FastFrameManipulate;
import de.cjdev.renderra.network.UpdateNBTPacket;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.slf4j.Logger;

import java.io.File;
import java.util.function.Predicate;

public class Renderra implements ModInitializer {

    public static final Logger LOGGER = LogUtils.getLogger();
    public static final FontDescription.Resource FONT = new FontDescription.Resource(ResourceLocation.fromNamespaceAndPath("m", "p"));

    public static final int COMPOUND_OFFLOAD_SIZE;
    public static final int COMPOUND_PIXEL_SIZE;

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
        UpdateNBTPacket.register();
    }

    public static void sendPacketToAllNear(Entity entity, CustomPacketPayload.Type<?> customPayloadType, Packet<?> moddedPacket, Packet<?> vanillaPacket) {
        for (ServerPlayerConnection connection : ((MixinTrackedEntity) ((MixinChunkMap) ((ServerChunkCache) entity.level().getChunkSource()).chunkMap).getEntityMap().get(entity.getId())).getSeenBy()) {
            if (ServerPlayNetworking.canSend(connection.getPlayer(), customPayloadType)) {
                connection.send(moddedPacket);
            } else {
                connection.send(vanillaPacket);
            }
        }
    }

    static {
        // Offload Calculation
        COMPOUND_OFFLOAD_SIZE = 25 + 19; // Base Compound + Packet Base
        COMPOUND_PIXEL_SIZE = 28; // One pixel with text "A"
    }
}
