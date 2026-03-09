package de.cjdev.renderra.network;

import com.mojang.logging.annotations.MethodsReturnNonnullByDefault;
import de.cjdev.renderra.Renderra;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.commands.data.DataCommands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;

import java.util.*;

@MethodsReturnNonnullByDefault
public record FastFrameManipulate(int entityID, int[] sections) implements CustomPacketPayload {
    public static final Identifier IDENTIFIER = Identifier.parse("videoplayer:frame_update");
    public static final Type<FastFrameManipulate> PACKET_TYPE =
            new Type<>(IDENTIFIER);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return PACKET_TYPE;
    }

    public void write(FriendlyByteBuf byteBuf) {
        byteBuf.writeVarInt(this.entityID);

        byteBuf.writeVarInt(this.sections.length);
        for (int section : this.sections) {
            byteBuf.writeVarInt(section);
        }
    }

    public static FastFrameManipulate decode(FriendlyByteBuf byteBuf) {
        int entityID = byteBuf.readVarInt();

        IntList sectionList = new IntArrayList();
        int pixelCount = byteBuf.readVarInt();
        for (int i = 0; i < pixelCount; i++) {
            sectionList.add(byteBuf.readVarInt());
        }

        return new FastFrameManipulate(entityID, sectionList.toIntArray());
    }

    public void handle(MinecraftServer server, ServerPlayer player) {
        if (!player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) return;
        if (player.level().getEntity(this.entityID) instanceof Display.ItemDisplay display) {
            sendBackDisplay(display);
        }
    }

    public void sendBackDisplay(Display.ItemDisplay display) {
        ItemStack copyStack = display.getItemStack().copy();
        copyStack.update(DataComponents.CUSTOM_MODEL_DATA, CustomModelData.EMPTY, customModelData ->
                new CustomModelData(
                        customModelData.floats(),
                        customModelData.flags(),
                        customModelData.strings(),
                        IntList.of(this.sections)
                )
        );
        var vanillaPacket = new ClientboundSetEntityDataPacket(display.getId(), List.of(new SynchedEntityData.DataValue<>(23, EntityDataSerializers.ITEM_STACK, copyStack)));
        var renderraPacket = new ClientboundCustomPayloadPacket(this);

        Renderra.sendPacketToAllNear(display, PACKET_TYPE, renderraPacket, vanillaPacket);
    }

    public static void register() {
        PayloadTypeRegistry.playC2S().registerLarge(PACKET_TYPE, CustomPacketPayload.codec(FastFrameManipulate::write, FastFrameManipulate::decode), Integer.MAX_VALUE);
        PayloadTypeRegistry.playS2C().registerLarge(PACKET_TYPE, CustomPacketPayload.codec(FastFrameManipulate::write, FastFrameManipulate::decode), Integer.MAX_VALUE);
        ServerPlayNetworking.registerGlobalReceiver(PACKET_TYPE, (payload, context) -> payload.handle(context.server(), context.player()));
    }
}
