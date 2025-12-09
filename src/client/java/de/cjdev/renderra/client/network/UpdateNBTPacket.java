package de.cjdev.renderra.client.network;

import com.moulberry.axiom.packets.AxiomServerboundManipulateEntity;
import com.moulberry.axiom.packets.AxiomServerboundPacket;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

public record UpdateNBTPacket(List<de.cjdev.renderra.network.UpdateNBTPacket.Modified> modifiedList, CompoundTag nbt) implements AxiomServerboundPacket {

    @Override
    public ResourceLocation id() {
        return AxiomServerboundManipulateEntity.IDENTIFIER;
    }

    @Override
    public void write(FriendlyByteBuf friendlyByteBuf) {
        // Iterate
        for (de.cjdev.renderra.network.UpdateNBTPacket.Modified uuid : this.modifiedList) {
            // Modified
            uuid.write(friendlyByteBuf);
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
