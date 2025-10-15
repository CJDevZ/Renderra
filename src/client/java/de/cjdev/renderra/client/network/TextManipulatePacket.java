package de.cjdev.renderra.client.network;

import com.moulberry.axiom.packets.AxiomServerboundManipulateEntity;
import com.moulberry.axiom.packets.AxiomServerboundPacket;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public record TextManipulatePacket(UUID uuid, Component text) implements AxiomServerboundPacket {

    @Override
    public ResourceLocation id() {
        return AxiomServerboundManipulateEntity.IDENTIFIER;
    }

    @Override
    public void write(FriendlyByteBuf friendlyByteBuf) {
        // One Entry
        friendlyByteBuf.writeVarInt(1);
        // UUID
        friendlyByteBuf.writeUUID(this.uuid);
        // No Position Data
        friendlyByteBuf.writeByte(-1);
        // NBT
        CompoundTag compoundTag = new CompoundTag();
        compoundTag.put("text", ComponentSerialization.CODEC.encodeStart(NbtOps.INSTANCE, this.text).getOrThrow());
        friendlyByteBuf.writeNbt(compoundTag);
        // No Passenger Manipulation
        friendlyByteBuf.writeVarInt(0);
    }

    @Override
    public void handle(MinecraftServer minecraftServer, ServerPlayer serverPlayer) {

    }

}
