package eu.cj4.renderra.impl.video;

import eu.cj4.renderra.api.VideoResult;
import eu.cj4.renderra.api.video.*;
import eu.cj4.renderra.impl.audio.AudioSplitter;
import eu.cj4.renderra.impl.network.ImageIterable;
import eu.cj4.renderra.impl.subtitle.SRTLoader;
import eu.cj4.renderra.impl.subtitle.Subtitle;
import eu.pb4.polymer.virtualentity.api.elements.DisplayElement;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import eu.pb4.polymer.virtualentity.api.elements.TextDisplayElement;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.joml.Vector3f;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;

import static eu.cj4.renderra.impl.Renderra.VIDEO_FILTER;

public class VirtualPlaybackHandlerImpl implements PlaybackHandler {

    private ServerLevel serverLevel;
    private Vec3 audioPosition;

    protected ItemDisplayElement screenElement;
    protected TextDisplayElement subtitleElement;
    protected ScreenMeta screenMeta;
    protected VideoInfo videoInfo;
    protected ReplayMode replayMode;

    private boolean isPlaying = false;
    private FFmpegFrameGrabber GRAB;

    private Long currentSubtitle;
    private NavigableMap<Long, Subtitle> subtitles;
    private String audioPath;
    private Identifier audioResource;
    private float volume;
    private CompletableFuture<VideoResult> AUDIO_GENERATING;

    protected long lastAudioChunk;

    public VirtualPlaybackHandlerImpl() {
        this(new ScreenMeta(16, 9, 0d, 1f, Rotation.NONE, ScaleMode.BILINEAR));
    }

    public VirtualPlaybackHandlerImpl(ScreenMeta screenMeta) {
        this(screenMeta, Identifier.fromNamespaceAndPath("renderra", "screen"));
    }

    public VirtualPlaybackHandlerImpl(ScreenMeta screenMeta, Identifier screenItemModel) {
        this.screenElement = new ItemDisplayElement(new ItemStack(Items.WHITE_DYE.builtInRegistryHolder(), 1, DataComponentPatch.builder().set(DataComponents.ITEM_MODEL, screenItemModel).build()));
        this.screenElement.setItemDisplayContext(ItemDisplayContext.FIXED);

        this.screenMeta = screenMeta;
    }

    public TextDisplayElement addSubtitleElement() {
        TextDisplayElement element = new TextDisplayElement();
        this.subtitleElement = element;
        return element;
    }

    public void setScreenItemModel(Identifier screenItemModel) {
        this.screenElement.getItem().set(DataComponents.ITEM_MODEL, screenItemModel);
    }

    public DisplayElement getScreenElement() {
        return this.screenElement;
    }

    public ScreenMeta getScreenMeta() {
        return this.screenMeta;
    }

    public void setScreenMeta(ScreenMeta screenMeta) {
        this.screenMeta = screenMeta;
        float scale = screenMeta.scale();
        this.getScreenElement().setScale(new Vector3f(scale, scale, scale));
    }

    public void updateScreenMeta(UnaryOperator<ScreenMeta> operator) {
        this.screenMeta = operator.apply(this.screenMeta);
    }

    @Override
    public VideoResult setVideo(File videoFile) {
        this.isPlaying = false;
        this.currentSubtitle = null;

        if (!VIDEO_FILTER.test(videoFile.getName()) || !videoFile.exists()) {
            return VideoResult.INVALID_VIDEO_FORMAT;
        }

        if (this.GRAB != null) {
            try {
                this.GRAB.stop();
            } catch (FFmpegFrameGrabber.Exception ignored) {
            }
        }
        this.subtitles = new TreeMap<>();

        this.audioPath = "videoaudio/" + videoFile.getName();
        try {
            this.GRAB = new FFmpegFrameGrabber(videoFile);
            this.GRAB.start();
        } catch (FFmpegFrameGrabber.Exception e) {
            this.GRAB = null;
            return VideoResult.FFmpeg_ERROR;
        }

        processFrame();
        try {
            GRAB.setTimestamp(0);
        } catch (FFmpegFrameGrabber.Exception ignored) {
        }
        this.videoInfo = new VideoInfo(videoFile.getName(), GRAB.getFrameRate(), GRAB.getLengthInTime() / 1_000_000L, 1 / GRAB.getFrameRate());
        lastAudioChunk = -1;
        updateSubtitle(Component.empty());
        return VideoResult.LOADED_VIDEO;
    }

    public CompletableFuture<VideoResult> generateAudio() {
        if (this.GRAB == null) return CompletableFuture.completedFuture(VideoResult.NO_VIDEO);
        if (this.isPlaying) return CompletableFuture.completedFuture(VideoResult.VIDEO_PLAYING);
        if (this.AUDIO_GENERATING != null && !this.AUDIO_GENERATING.isDone()) return CompletableFuture.completedFuture(VideoResult.GEN_AUDIO);

        Path packFolder = Path.of(audioPath);
        Path soundsFolder = packFolder.resolve("assets/renderra/sounds");
        var future = CompletableFuture.supplyAsync(() -> {
            try {
                AudioSplitter.splitAudioFromVideo(GRAB, soundsFolder);
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
            return VideoResult.OK;
        });
        this.AUDIO_GENERATING = future;

        return future;
    }

    public void loadSubtitles(File srtFile) throws IOException {
        this.subtitles = SRTLoader.parseSRT(srtFile.toPath());
    }

    @Override
    public VideoResult resumeVideo() {
        if (this.AUDIO_GENERATING != null) {
            if (!this.AUDIO_GENERATING.isDone()) {
                return VideoResult.GEN_AUDIO;
            }
            this.AUDIO_GENERATING = null;
        }
        this.isPlaying = true;
        return VideoResult.OK;
    }

    public void pauseVideo() {
        this.isPlaying = false;
    }

    @Override
    public void tick() {
        if (this.AUDIO_GENERATING != null && !this.AUDIO_GENERATING.isDone()) {
            return;
        }
        if (this.isPlaying && this.GRAB != null) {
            processFrame();
            processSubtitles();
            processAudio();
        }
    }

    private void processFrame() {
        BufferedImage bufferedImage = PlaybackHandlerImpl.getTransformedImage(this.screenMeta, this.GRAB, this::videoDone);
        if (bufferedImage != null) {
            displayImage(bufferedImage);
        }
    }

    private void processSubtitles() {
        if (this.subtitles == null) return;
        long millis = this.GRAB.getTimestamp() / 1_000L;

        Map.Entry<Long, Subtitle> subtitle = SRTLoader.getSubtitleAt(subtitles, millis);
        if (subtitle == null && this.currentSubtitle != null) {
            this.currentSubtitle = null;
            updateSubtitle(Component.empty());
        } else if (subtitle != null && !Objects.equals(subtitle.getKey(), this.currentSubtitle)) {
            this.currentSubtitle = subtitle.getKey();
            updateSubtitle(subtitle.getValue().text());
        }
    }

    private void processAudio() {
        long chunk = (GRAB.getTimestamp() / AudioSplitter.CHUNK_MICROS);
        if (chunk == lastAudioChunk) return;

        if (this.volume != 0F && this.serverLevel != null && this.audioPosition != null && this.audioResource != null) {
            SoundEvent soundEvent = SoundEvent.createVariableRangeEvent(this.audioResource.withSuffix(String.format("%05x", chunk)));
            serverLevel.playSound(
                    null,
                    this.audioPosition.x, this.audioPosition.y, this.audioPosition.z,
                    soundEvent,
                    SoundSource.RECORDS,
                    1f, 1f
            );
        }

        lastAudioChunk = chunk;
    }

    private void updateSubtitle(Component component) {
        if (this.subtitleElement != null) {
            this.subtitleElement.setText(component);
        }
    }

    private void videoDone() {
        if (this.replayMode == ReplayMode.LOOP && GRAB != null) {
            try {
                GRAB.setTimestamp(0);
                lastAudioChunk = -1;
                return;
            } catch (FFmpegFrameGrabber.Exception ignored) {
            }
        }
        cleanUpVideo();
    }

    public void cleanUpVideo() {
        if (GRAB != null) {
            try {
                GRAB.stop();
            } catch (FFmpegFrameGrabber.Exception ignored) {
            }
            this.GRAB = null;
            this.isPlaying = false;
            this.videoInfo = VideoInfo.NONE;
        }
    }

    protected void displayImage(BufferedImage bufferedImage) {
        int width = bufferedImage.getWidth();
        int height = bufferedImage.getHeight();
        int[] pixels = ImageIterable.parseImage(bufferedImage, width, height, 0);
        this.screenElement.getItem().set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), IntList.of(pixels)));
    }

    public void setLevel(ServerLevel serverLevel) {
        this.serverLevel = serverLevel;
    }

    public void setAudioPosition(Vec3 audioPosition) {
        this.audioPosition = audioPosition;
    }

    public void setAudioResource(Identifier audioResource) {
        this.audioResource = audioResource;
    }

    public void setVolume(float volume) {
        this.volume = volume;
    }
}
