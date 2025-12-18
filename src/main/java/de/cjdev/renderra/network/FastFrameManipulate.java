package de.cjdev.renderra.network;

import de.cjdev.renderra.video.ColorMode;
import de.cjdev.renderra.video.ImageProcessingResult;
import de.cjdev.renderra.Renderra;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.AbstractInt2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntLinkedOpenHashMap;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;

import java.awt.image.BufferedImage;
import java.util.*;

import static de.cjdev.renderra.Renderra.FONT;
import static de.cjdev.renderra.Renderra.LOGGER;

@MethodsReturnNonnullByDefault
public class FastFrameManipulate implements CustomPacketPayload {
    public static final ResourceLocation IDENTIFIER = ResourceLocation.parse("videoplayer:frame_update");
    public static final CustomPacketPayload.Type<FastFrameManipulate> PACKET_TYPE =
            new CustomPacketPayload.Type<>(IDENTIFIER);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return PACKET_TYPE;
    }

    private final int entityID;
    public final ColorMode colorMode;
    public final ImageProcessingResult imageProcessingResult;
    private List<Component> sections = null;
    public final boolean pretty;

    public int getEntityID() {
        return this.entityID;
    }

    public FastFrameManipulate(int entityID, ColorMode colorMode, boolean pretty, ImageIterable image) {
        this.entityID = entityID;
        this.colorMode = colorMode;
        this.imageProcessingResult = image.processingResult();
        this.sections = image.sections();
        this.pretty = pretty;
    }

    public FastFrameManipulate(int entityID, ColorMode colorMode, boolean pretty, int[] colorPalette, List<ImageIterable.PixelWidth> pixelWidths) {
        this.entityID = entityID;
        this.colorMode = colorMode;
        this.imageProcessingResult = new ImageProcessingResult(colorPalette, pixelWidths);
        this.pretty = pretty;
    }

    //public FastFrameManipulate(int entityID, ColorMode colorMode, boolean pretty, int width, int height, int heightOffset, BufferedImage bufferedImage) {
    //    this.entityID = entityID;
    //    this.colorMode = colorMode;
    //    this.width = width;
    //    this.height = height;
    //    this.heightOffset = heightOffset;
    //    this.bufferedImage = bufferedImage;
    //    this.pretty = pretty;
    //}

    public void write(FriendlyByteBuf byteBuf) {
        //if (this.bufferedPacket != null) {
        //    byteBuf.writeBytes(bufferedPacket.copy());
        //    return;
        //}

        // Build packet
        //bufferedPacket = new FriendlyByteBuf(Unpooled.buffer());
        byteBuf.writeVarInt(this.entityID);
        byteBuf.writeEnum(this.colorMode);
        byteBuf.writeBoolean(this.pretty);

        byteBuf.writeVarInt(this.imageProcessingResult.palette().length);
        for (int i : this.imageProcessingResult.palette()) {
            byteBuf.writeVarInt(i);
        }
        for (ImageIterable.PixelWidth section : this.imageProcessingResult.rleSegments()) {
            byteBuf.writeVarInt(section.consecutive());
            byteBuf.writeVarInt(section.colorIndex());
        }

        // Cache the built packet
        //byteBuf.writeBytes(this.bufferedPacket.copy());
    }

    public static void writeImageTiny(ColorMode colorMode, FriendlyByteBuf byteBuf, BufferedImage image, int width, int height, int heightOffset) {
        int lastColor = -1;
        int consecutivePixels = 0;
        //long maxPixelCount = (long) width * height;
        AbstractInt2IntMap palette = new Int2IntLinkedOpenHashMap();
        int paletteIndex = 0;
        for (int y = heightOffset; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                int color = image.getRGB(x, y) & 0xFFFFFF;
                int mappedColor = colorMode.getMappedColor(color);
                if (!palette.containsKey(mappedColor)) {
                    palette.put(mappedColor, paletteIndex);
                    ++paletteIndex;
                }
            }
        }
        byteBuf.writeVarInt(paletteIndex);
        for (Integer value : palette.keySet()) {
            byteBuf.writeVarInt(value);
        }

        FriendlyByteBuf pixelsBuf = new FriendlyByteBuf(Unpooled.buffer());

        for (int y = heightOffset; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                int rgb = colorMode.getMappedColor(image.getRGB(x, y) & 0xFFFFFF);

                if (lastColor == -1) {
                    // First pixel
                    lastColor = rgb;
                }

                if (rgb == lastColor) ++consecutivePixels;
                else {
                    // Color changed — save current segment
                    pixelsBuf.writeVarInt(consecutivePixels);
                    pixelsBuf.writeVarInt(palette.get(lastColor));

                    consecutivePixels = 1;
                    lastColor = rgb;
                }
            }
        }

        // Add last segment if not empty
        if (consecutivePixels > 0) {
            pixelsBuf.writeVarInt(consecutivePixels);
            pixelsBuf.writeVarInt(palette.get(lastColor));
        }

        byteBuf.writeBytes(pixelsBuf);
    }

    public static FastFrameManipulate decode(FriendlyByteBuf byteBuf) {
        int entityID = byteBuf.readVarInt();
        ColorMode colorMode1 = byteBuf.readEnum(ColorMode.class);
        boolean pretty = byteBuf.readBoolean();
        return new FastFrameManipulate(entityID, colorMode1, pretty, ImageIterable.read(byteBuf, colorMode1, pretty));
    }

    public void handle(MinecraftServer server, ServerPlayer player) {
        if (player.getPermissionLevel() < 2) return;
        if (player.level().getEntity(this.entityID) instanceof Display.TextDisplay textDisplay) {
            LOGGER.info("Received TextDisplay from {}", this.entityID);
            sendBackDisplay(textDisplay);
        }
    }

    public void sendBackDisplay(Display.TextDisplay textDisplay) {
        Component text = Component.literal("").withStyle(style ->
                style.withFont(FONT));
        text.getSiblings().addAll(this.sections);
        var vanillaPacket = new ClientboundSetEntityDataPacket(textDisplay.getId(), List.of(new SynchedEntityData.DataValue<>(23, EntityDataSerializers.COMPONENT, text)));
        var renderraPacket = new ClientboundCustomPayloadPacket(this);

        Renderra.sendPacketToAllNear(textDisplay, PACKET_TYPE, renderraPacket, vanillaPacket);
    }

    public static void register() {
        PayloadTypeRegistry.playC2S().registerLarge(PACKET_TYPE, CustomPacketPayload.codec(FastFrameManipulate::write, FastFrameManipulate::decode), Integer.MAX_VALUE);
        PayloadTypeRegistry.playS2C().registerLarge(PACKET_TYPE, CustomPacketPayload.codec(FastFrameManipulate::write, FastFrameManipulate::decode), Integer.MAX_VALUE);
        ServerPlayNetworking.registerGlobalReceiver(PACKET_TYPE, (payload, context) -> payload.handle(context.server(), context.player()));
    }
}
