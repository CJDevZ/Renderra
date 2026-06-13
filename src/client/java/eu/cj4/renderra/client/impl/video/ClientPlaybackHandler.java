package eu.cj4.renderra.client.impl.video;

import com.moulberry.axiom.packets.AxiomServerboundManipulateEntity;
import eu.cj4.renderra.api.video.ScreenHolder;
import eu.cj4.renderra.api.video.ScreenMeta;
import eu.cj4.renderra.client.impl.VideoPlayerClient;
import eu.cj4.renderra.impl.network.ServerboundUpdateNBTPacket;
import eu.cj4.renderra.impl.video.PlaybackHandlerImpl;
import eu.cj4.renderra.api.VideoResult;
import eu.cj4.renderra.client.impl.screen.VideoPlayerScreen;
import eu.cj4.renderra.impl.network.FastFrameManipulate;
import eu.cj4.renderra.impl.network.ImageIterable;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.jspecify.annotations.Nullable;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static eu.cj4.renderra.client.impl.VideoPlayerClient.notInGame;

public class ClientPlaybackHandler extends PlaybackHandlerImpl implements AutoCloseable {

    public ClientPlaybackHandler() {
        super();
    }

    public ClientPlaybackHandler(ScreenHolder screenMetaData) {
        super(screenMetaData);
    }

    private double SECONDS;
    public boolean warnedNoSupportedProtocol = false;

    @Override
    public Level getLevel() {
        return Minecraft.getInstance().level;
    }

    private static CompoundTag textNBTFromComponent(Component component) {
        CompoundTag compoundTag = new CompoundTag();
        compoundTag.put("text", ComponentSerialization.CODEC.encodeStart(NbtOps.INSTANCE, component).getOrThrow());
        return compoundTag;
    }

    @Override
    protected void updateSubtitle(Component text) {
        Display.TextDisplay subtitleDisplay = SCREEN_HOLDER.getSubtitleDisplay();
        if (subtitleDisplay != null) {
            doEntityMerge(Collections.singletonList(new ServerboundUpdateNBTPacket.Modified(subtitleDisplay.getUUID(), null)), textNBTFromComponent(text));
        }
    }

    @Override
    protected void playSound(Entity atEntity, SoundEvent soundEvent, SoundSource source, float volume, float pitch) {
        if (Minecraft.getInstance().player == null) return;
        Vec3 position = atEntity.position();
        Minecraft.getInstance().player.connection.sendCommand("playsound " + soundEvent.location() + " " + source.getName() + " @a " + position.x + " " + position.y + " " + position.z + " " + volume + " " + pitch + " " + soundEvent.fixedRange().orElse(0f));
    }

    public void deltaTick(Minecraft minecraft, double deltaTime) {
        if (!playing || GRAB == null || minecraft.isPaused() || SCREEN_HOLDER.hasNoScreens()) {
            return;
        }

        SECONDS += deltaTime;

        if (SECONDS >= VIDEO_INFO.frameDelta()) {
            SECONDS -= VIDEO_INFO.frameDelta();

            processFrame();
            processSubtitles();
            processAudio();
        }
    }

    public VideoResult syncHz() {
        if (VIDEO_INFO == null || VIDEO_INFO.frameRate() == 0F) return VideoResult.NO_VIDEO;
        Minecraft.getInstance().getConnection().sendCommand("tick rate " + VIDEO_INFO.frameRate());
        return VideoResult.OK;
    }

    @Override
    public void doEntityMerge(List<ServerboundUpdateNBTPacket.Modified> modified, CompoundTag merge) {
        if (ClientPlayNetworking.canSend(VideoPlayerClient.UPDATE_NBT_IDENTIFIER)) {
            ClientPlayNetworking.send(new ServerboundUpdateNBTPacket(modified, merge));
        } else if (ClientPlayNetworking.canSend(VideoPlayerClient.MANIPULATE_ENTITY_IDENTIFIER)) {
            List<AxiomServerboundManipulateEntity.ManipulateEntry> manipulateEntries = new ArrayList<>(modified.size());
            for (ServerboundUpdateNBTPacket.Modified entry : modified) {
                manipulateEntries.add(new AxiomServerboundManipulateEntity.ManipulateEntry(entry.uuid(), entry.pos(), merge));
            }
            new AxiomServerboundManipulateEntity(manipulateEntries).send();
        }
    }

    @Override
    protected void processFrame() {
        if (Minecraft.getInstance().getConnection() == null) return;
        if (ClientPlayNetworking.canSend(FastFrameManipulate.PACKET_TYPE)) {
            BufferedImage bufferedImage = getTransformedImage(this.SCREEN_HOLDER.screenMeta(), this.GRAB, this::videoDone);
            if (bufferedImage != null) {
                this.fastDisplayImage(bufferedImage);
            }
        } else if (ClientPlayNetworking.canSend(VideoPlayerClient.MANIPULATE_ENTITY_IDENTIFIER)) {
            var compoundTags = processAxiomFrame(this.GRAB, this::videoDone);
            if (compoundTags != null) {
                this.axiomDisplayImage(compoundTags);
            }
        } else {
            if (!this.warnedNoSupportedProtocol) {
                this.warnedNoSupportedProtocol = true;
                VideoPlayerScreen.doToast("No Supported Protocol");
            }
            this.cleanUpVideo();
        }
    }

    private CompoundTag image2NBT(final BufferedImage image, final int width, final int height, final int heightOffset) {
        int[] pixelArray = image.getRGB(0, heightOffset, width, height - heightOffset, null, 0, width);

        CompoundTag customModelData = new CompoundTag();
        customModelData.put("colors", new IntArrayTag(pixelArray));
        CompoundTag components = new CompoundTag();
        components.put("minecraft:custom_model_data", customModelData);
        CompoundTag itemTag = new CompoundTag();
        itemTag.put("components", components);
        CompoundTag compoundTag = new CompoundTag();
        compoundTag.put("item", itemTag);

        return compoundTag;
    }

    protected CompoundTag @Nullable [] processAxiomFrame(FFmpegFrameGrabber GRAB, Runnable done) {
        BufferedImage transformedImage = getTransformedImage(this.SCREEN_HOLDER.screenMeta(), GRAB, done);
        if (transformedImage == null) return null;

        ScreenMeta screenMeta = this.SCREEN_HOLDER.screenMeta();
        int width = screenMeta.width();
        int height = screenMeta.height();

        int screenCount = this.SCREEN_HOLDER.getScreenCount();
        CompoundTag[] results = new CompoundTag[screenCount];
        int sectionHeight = Math.round(((float) height) / (screenCount));
        int pixelHeight = height - sectionHeight * (screenCount - 1);
        results[0] = image2NBT(transformedImage, width, height, height - pixelHeight);
        for (int i = 1; i < screenCount; ++i) {
            int curHeight = (screenCount - i - 1) * sectionHeight;
            results[i] = image2NBT(transformedImage, width, curHeight + sectionHeight, curHeight);
        }
        return results;
    }

    private void axiomDisplayImage(CompoundTag[] compoundTags) {
        if (notInGame()) return;
        UUID[] uuids = SCREEN_HOLDER.getScreenUUIDs();
        int length = uuids.length;
        while (--length != -1) {
            UUID screen = uuids[length];
            CompoundTag compoundTag = compoundTags[length];
            if (compoundTag != null) {
                new AxiomServerboundManipulateEntity(Collections.singletonList(
                        new AxiomServerboundManipulateEntity.ManipulateEntry(screen, null, compoundTag)
                )).send();
            }
        }
    }

    @Override
    protected void fastDisplayImage(BufferedImage bufferedImage) {
        if (notInGame()) return;
        int screenCount = SCREEN_HOLDER.getScreenCount();

        int width = bufferedImage.getWidth();
        int height = bufferedImage.getHeight();

        int sectionHeight = Math.round(((float) height) / (screenCount));
        int pixelHeight = height - sectionHeight * (screenCount - 1);
        ClientPlayNetworking.send(new FastFrameManipulate(
                SCREEN_HOLDER.getMainDisplay().getId(),
                ImageIterable.parseImage(
                        bufferedImage,
                        width,
                        height,
                        height - pixelHeight
                )
        ));

        Integer[] ids = SCREEN_HOLDER.getScreenIDs();
        int length = ids.length;
        while (--length > 0) {
            int screen = ids[length];
            int curHeight = (screenCount - length - 1) * sectionHeight;
            ClientPlayNetworking.send(new FastFrameManipulate(screen, ImageIterable.parseImage(bufferedImage, width, curHeight + sectionHeight, curHeight)));
        }
    }

    @Override
    public void close() {
        this.cleanUpVideo();

        this.warnedNoSupportedProtocol = false;
        this.SCREEN_HOLDER.close();
        this.subtitles = null;
        this.subtitleCurrently = null;
    }
}
