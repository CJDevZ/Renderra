package de.cjdev.renderra.client.network;

import com.moulberry.axiom.packets.AxiomServerboundManipulateEntity;
import com.moulberry.axiom.packets.AxiomServerboundPacket;
import net.minecraft.nbt.EndTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

public class FramesRepositionPacket implements AxiomServerboundPacket {

    private final UUID[] uuids;
    private final Vec3[] positions;

    public FramesRepositionPacket(UUID[] uuids, Vec3[] positions) {
        if (uuids.length != positions.length) {
            throw new IllegalStateException("There are %s UUIDS, but %s Positions.".formatted(uuids.length, positions.length));
        }
        this.uuids = uuids;
        this.positions = positions;
    }

    @Override
    public ResourceLocation id() {
        return AxiomServerboundManipulateEntity.IDENTIFIER;
    }

    @Override
    public void write(FriendlyByteBuf friendlyByteBuf) {
        // One Entry
        friendlyByteBuf.writeVarInt(uuids.length);
        for (int i = 0; i < uuids.length; ++i) {
            // UUID
            friendlyByteBuf.writeUUID(this.uuids[i]);
            // Position Data
            var position = positions[i];
            friendlyByteBuf.writeByte(Relative.pack(Relative.ROTATION));
            friendlyByteBuf.writeDouble(position.x);
            friendlyByteBuf.writeDouble(position.y);
            friendlyByteBuf.writeDouble(position.z);
            friendlyByteBuf.writeFloat(0);
            friendlyByteBuf.writeFloat(0);
            // No NBT
            friendlyByteBuf.writeNbt(EndTag.INSTANCE);
            // No Passenger Manipulation
            friendlyByteBuf.writeVarInt(0);
        }
    }

    @Override
    public void handle(MinecraftServer minecraftServer, ServerPlayer serverPlayer) {

    }

}
