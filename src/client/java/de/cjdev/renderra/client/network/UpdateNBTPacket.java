package de.cjdev.renderra.client.network;

import com.moulberry.axiom.packets.AxiomServerboundManipulateEntity;
import com.moulberry.axiom.packets.AxiomServerboundPacket;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public record UpdateNBTPacket(UUID[] uuids, CompoundTag nbt) implements AxiomServerboundPacket {

    public UpdateNBTPacket(UUID uuid, CompoundTag nbt) {
        this(new UUID[] {uuid}, nbt);
    }

    @Override
    public ResourceLocation id() {
        return AxiomServerboundManipulateEntity.IDENTIFIER;
    }

    @Override
    public void write(FriendlyByteBuf friendlyByteBuf) {
        // One Entry
        friendlyByteBuf.writeVarInt(this.uuids.length);
        // Iterate
        for (UUID uuid : uuids) {
            // UUID
            friendlyByteBuf.writeUUID(uuid);
            // No Position Data
            friendlyByteBuf.writeByte(-1);
            // NBT
            friendlyByteBuf.writeNbt(this.nbt);
            // No Passenger Manipulation
            friendlyByteBuf.writeVarInt(0);
        }
    }

    @Override
    public void handle(MinecraftServer minecraftServer, ServerPlayer serverPlayer) {

    }

}
