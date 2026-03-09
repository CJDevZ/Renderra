package de.cjdev.renderra.client.network;

import com.moulberry.axiom.packets.AxiomServerboundManipulateEntity;
import com.moulberry.axiom.packets.AxiomServerboundPacket;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

public record UpdateNBTPacket(List<de.cjdev.renderra.network.UpdateNBTPacket.Modified> modifiedList, CompoundTag nbt) implements AxiomServerboundPacket {

    @Override
    public Identifier id() {
        return AxiomServerboundManipulateEntity.IDENTIFIER;
    }

    @Override
    public void write(FriendlyByteBuf friendlyByteBuf) {
        friendlyByteBuf.writeVarInt(this.modifiedList.size());
        // Iterate
        for (de.cjdev.renderra.network.UpdateNBTPacket.Modified uuid : this.modifiedList) {
            // Modified
            uuid.writeAxiom(friendlyByteBuf);
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
