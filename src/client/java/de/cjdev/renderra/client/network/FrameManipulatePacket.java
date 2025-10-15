package de.cjdev.renderra.client.network;

import com.moulberry.axiom.packets.AxiomServerboundManipulateEntity;
import com.moulberry.axiom.packets.AxiomServerboundPacket;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public class FrameManipulatePacket implements AxiomServerboundPacket {

    private final UUID uuid;
    private final CompoundTag nbt;

    public FrameManipulatePacket(UUID uuid, CompoundTag nbt) {
        this.uuid = uuid;
        this.nbt = nbt;
    }

    @Override
    public ResourceLocation id() {
        return AxiomServerboundManipulateEntity.IDENTIFIER;
    }

    @Override
    public void write(FriendlyByteBuf byteBuf) {
        // One Entry
        byteBuf.writeVarInt(1);
        // UUID
        byteBuf.writeUUID(this.uuid);
        // No Position Data
        byteBuf.writeByte(-1);
        // NBT
        // TODO: Change to directly encode Frame
        byteBuf.writeNbt(this.nbt);
        // No Passenger Manipulation
        byteBuf.writeVarInt(0);
    }

    @Override
    public void handle(MinecraftServer minecraftServer, ServerPlayer serverPlayer) {

    }

}
