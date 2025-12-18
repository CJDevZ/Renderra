package de.cjdev.renderra.client.video;

import de.cjdev.renderra.client.VideoPlayerClient;
import de.cjdev.renderra.video.PlaybackHandler;
import de.cjdev.renderra.VideoResult;
import de.cjdev.renderra.client.network.FrameManipulatePacket;
import de.cjdev.renderra.client.network.TextManipulatePacket;
import de.cjdev.renderra.client.network.UpdateNBTPacket;
import de.cjdev.renderra.client.screen.VideoPlayerScreen;
import de.cjdev.renderra.network.FastFrameManipulate;
import de.cjdev.renderra.network.ImageIterable;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.bytedeco.javacv.FFmpegFrameGrabber;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.UUID;

import static de.cjdev.renderra.client.VideoPlayerClient.notInGame;

public class ClientPlaybackHandler extends PlaybackHandler {

    private double SECONDS;
    public boolean warnedNoSupportedProtocol = false;

    @Override
    public Level getLevel() {
        return Minecraft.getInstance().level;
    }

    @Override
    protected void updateSubtitle(Component text) {
        if (SCREEN_META.subtitleScreen == null) return;

        new TextManipulatePacket(SCREEN_META.subtitleScreen.getUUID(), text).send();
    }

    @Override
    protected void playSound(Entity atEntity, SoundEvent soundEvent, SoundSource source, float volume, float pitch) {
        if (Minecraft.getInstance().player == null) return;
        Vec3 position = atEntity.position();
        Minecraft.getInstance().player.connection.sendCommand("playsound " + soundEvent.location() + " " + source.getName() + " @a " + position.x + " " + position.y + " " + position.z + " " + volume + " " + pitch + " " + soundEvent.fixedRange().orElse(0f));
    }

    public void deltaTick(Minecraft minecraft, double deltaTime) {
        if (!playing || GRAB == null || minecraft.isPaused() || SCREEN_META.screens.isEmpty()) {
            return;
        }

        SECONDS += deltaTime;

        if (SECONDS >= VIDEO_META.frameDelta()) {
            SECONDS -= VIDEO_META.frameDelta();

            processFrame(GRAB);
            processSubtitles();
            processAudio();
        }
    }

    public VideoResult syncHz() {
        if (VIDEO_META == null || VIDEO_META.frameRate() == 0F) return VideoResult.NO_VIDEO;
        Minecraft.getInstance().getConnection().sendCommand("tick rate " + VIDEO_META.frameRate());
        return VideoResult.OK;
    }

    @Override
    public void doEntityMerge(List<de.cjdev.renderra.network.UpdateNBTPacket.Modified> modified, CompoundTag merge) {
        if (ClientPlayNetworking.canSend(VideoPlayerClient.UPDATE_NBT_IDENTIFIER)) {
            ClientPlayNetworking.send(new de.cjdev.renderra.network.UpdateNBTPacket(modified, merge));
        } else if (ClientPlayNetworking.canSend(VideoPlayerClient.MANIPULATE_ENTITY_IDENTIFIER)) {
            new UpdateNBTPacket(modified, merge).send();
        }
    }

    @Override
    protected void processFrame(FFmpegFrameGrabber GRAB) {
        if (Minecraft.getInstance().getConnection() == null) return;
        if (ClientPlayNetworking.canSend(FastFrameManipulate.PACKET_TYPE)) {
            processFastFrame(GRAB, this::fastDisplayImage, this::videoDone);
        } else if (ClientPlayNetworking.canSend(VideoPlayerClient.MANIPULATE_ENTITY_IDENTIFIER)) {
            processAxiomFrame(GRAB, this::axiomDisplayImage, this::videoDone);
        } else {
            if (!this.warnedNoSupportedProtocol) {
                warnedNoSupportedProtocol = true;
                VideoPlayerScreen.doToast("No Supported Protocol");
            }
            this.cleanUpVideo();
        }
    }

    private void axiomDisplayImage(CompoundTag[] compoundTags) {
        if (notInGame()) return;
        UUID[] uuids = SCREEN_META.getScreenUUIDs();
        int length = uuids.length;
        while (--length != -1) {
            UUID screen = uuids[length];
            CompoundTag compoundTag = compoundTags[length];
            if (compoundTag == null) continue;

            new FrameManipulatePacket(screen, compoundTag).send();
        }
    }

    @Override
    protected void fastDisplayImage(BufferedImage bufferedImage) {
        if (notInGame()) return;
        int screenCount = SCREEN_META.screens.size();

        int width = bufferedImage.getWidth();
        int height = bufferedImage.getHeight();
        boolean pretty = SCREEN_META.pretty();

        int sectionHeight = Math.round(((float) height) / (screenCount));
        int pixelHeight = height - sectionHeight * (screenCount - 1);
        ClientPlayNetworking.send(new FastFrameManipulate(
                SCREEN_META.getMainScreen().getId(),
                this.colorMode,
                pretty,
                ImageIterable.parseImage(
                        this.colorMode,
                        bufferedImage,
                        width,
                        height,
                        height - pixelHeight,
                        pretty
                )
        ));

        Integer[] ids = SCREEN_META.getScreenIDs();
        int length = ids.length;
        while (--length > 0) {
            int screen = ids[length];
            int curHeight = (screenCount - length - 1) * sectionHeight;
            ClientPlayNetworking.send(new FastFrameManipulate(screen, this.colorMode, pretty, ImageIterable.parseImage(this.colorMode, bufferedImage, width, curHeight + sectionHeight, curHeight, pretty)));
        }
    }
}
