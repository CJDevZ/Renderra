package de.cjdev.renderra.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

public record UpdateNBTPacket(List<Modified> modifiedList, CompoundTag merge) implements CustomPacketPayload {
    public static final ResourceLocation IDENTIFIER = ResourceLocation.parse("renderra:update_nbt");
    public static final CustomPacketPayload.Type<UpdateNBTPacket> PACKET_TYPE =
            new CustomPacketPayload.Type<>(IDENTIFIER);

    public UpdateNBTPacket(FriendlyByteBuf byteBuf) {
        this(
                byteBuf.readList(Modified::read),
                byteBuf.readNbt()
        );
    }

    public record Modified(UUID uuid, Vec3 pos) {
        public static Modified read(FriendlyByteBuf byteBuf) {
            return new Modified(byteBuf.readUUID(), byteBuf.readBoolean() ? byteBuf.readVec3() : null);
        }

        public void write(FriendlyByteBuf byteBuf) {
            // UUID
            byteBuf.writeUUID(uuid);
            // Position Data
            byteBuf.writeBoolean(pos != null);
            if (pos != null) {
                byteBuf.writeVec3(pos);
            }
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return PACKET_TYPE;
    }

    public void write(FriendlyByteBuf friendlyByteBuf) {
        // Modified
        friendlyByteBuf.writeCollection(this.modifiedList, (object, object2) -> object2.write(object));
        // NBT
        friendlyByteBuf.writeNbt(this.merge);
    }

    public void handle(MinecraftServer minecraftServer, ServerPlayer serverPlayer) {
        if (serverPlayer.getPermissionLevel() < 2) return;
        ServerLevel level = serverPlayer.level();
        minecraftServer.execute(() -> {
            for (Modified modified : this.modifiedList) {
                UUID uuid = modified.uuid;
                var entity = level.getEntity(uuid);
                if (entity == null) continue;

                var position = modified.pos;
                if (position != null) {
                    entity.setPos(position);
                }

                var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, entity.registryAccess());
                entity.saveWithoutId(output);
                CompoundTag compoundTag = output.buildResult();
                compoundTag.merge(merge);
                var input = TagValueInput.create(ProblemReporter.DISCARDING, entity.registryAccess(), compoundTag);
                entity.load(input);
            }
        });
    }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(PACKET_TYPE, CustomPacketPayload.codec(UpdateNBTPacket::write, UpdateNBTPacket::new));
        ServerPlayNetworking.registerGlobalReceiver(PACKET_TYPE, (payload, context) -> payload.handle(context.server(), context.player()));
    }
}
