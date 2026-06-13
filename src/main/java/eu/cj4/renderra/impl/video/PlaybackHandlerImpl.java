package eu.cj4.renderra.impl.video;

import eu.cj4.renderra.api.VideoResult;
import eu.cj4.renderra.api.video.*;
import eu.cj4.renderra.impl.audio.AudioSplitter;
import eu.cj4.renderra.impl.network.FastFrameManipulate;
import eu.cj4.renderra.impl.network.ImageIterable;
import eu.cj4.renderra.impl.network.ServerboundUpdateNBTPacket;
import eu.cj4.renderra.impl.subtitle.SRTLoader;
import eu.cj4.renderra.impl.subtitle.Subtitle;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static eu.cj4.renderra.impl.Renderra.*;

public class PlaybackHandlerImpl implements PlaybackHandler {

    protected FFmpegFrameGrabber GRAB;

    public VideoInfo VIDEO_INFO;
    public final ScreenHolder SCREEN_HOLDER;

    public NavigableMap<Long, Subtitle> subtitles;
    public Long subtitleCurrently = null;
    protected long lastAudioChunk = -1;
    public String videoName = "None";
    private String audioPath;

    public CompletableFuture<VideoResult> AUDIO_GENERATING = null;
    private final CompoundTag SCREEN_FIX;

    public boolean playing;
    public float volume;
    public ReplayMode replayMode;
    public ColorFilter colorFilter;
    public Identifier audioResource;

    public Level level;
    protected static Vec3 pos;

    public PlaybackHandlerImpl() {
        this(new ScreenHolderImpl());
    }

    public PlaybackHandlerImpl(ScreenHolder screenHolder) {
        SCREEN_HOLDER = screenHolder;
        VIDEO_INFO = VideoInfo.NONE;
        volume = 1f;
        replayMode = ReplayMode.ONCE;
        colorFilter = ColorFilter.FULL;
        audioResource = Identifier.parse("renderra:part");
        // Creating SCREEN_FIX
        ListTag SCALE = new ListTag();
        FloatTag SCALAR = FloatTag.valueOf(screenHolder.scale());
        SCALE.add(SCALAR);
        SCALE.add(SCALAR);
        SCALE.add(SCALAR);

        CompoundTag BRIGHTNESS_FIX = new CompoundTag();
        BRIGHTNESS_FIX.putInt("block", 15);
        BRIGHTNESS_FIX.putInt("sky", 0);

        CompoundTag TRANSFORMATION_FIX = new CompoundTag();
        TRANSFORMATION_FIX.put("scale", SCALE);

        SCREEN_FIX = new CompoundTag();
        SCREEN_FIX.put("transformation", TRANSFORMATION_FIX);
        SCREEN_FIX.put("brightness", BRIGHTNESS_FIX);
    }

    public Level getLevel() {
        return this.level;
    }

    public VideoResult setupVideo() {
        return this.setVideo(new File(VIDEOS_FOLDER, videoName));
    }

    @Override
    public VideoResult setVideo(File videoFile) {
        if (SCREEN_HOLDER.hasNoScreens()) return VideoResult.NO_SCREENS;
        this.playing = false;
        this.subtitleCurrently = null;

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

        // Position Screens
        var result = fixScreens();
        if (!result.isOk()) return result;

        processFrame();
        try {
            this.GRAB.setTimestamp(0);
        } catch (FFmpegFrameGrabber.Exception ignored) {
        }
        VIDEO_INFO = new VideoInfo(videoName, GRAB.getFrameRate(), GRAB.getLengthInTime() / 1_000_000L, 1 / GRAB.getFrameRate());
        lastAudioChunk = -1;
        updateSubtitle(Component.empty());
        return VideoResult.LOADED_VIDEO;
    }

    @Override
    public VideoResult resumeVideo() {
        if (AUDIO_GENERATING != null && !AUDIO_GENERATING.isDone()) return VideoResult.GEN_AUDIO;
        if (GRAB == null || !Objects.equals(videoName, VIDEO_INFO.fileName())) {
            return setupVideo();
        }
        this.playing = true;
        return VideoResult.OK;
    }

    @Override
    public void pauseVideo() {
        this.playing = false;
    }

    protected void processSubtitles() {
        if (GRAB == null) return;
        if (subtitles == null) return;
        long millis = GRAB.getTimestamp() / 1_000L;

        Map.Entry<Long, Subtitle> subtitle = SRTLoader.getSubtitleAt(subtitles, millis);
        if (subtitle == null && subtitleCurrently != null) {
            subtitleCurrently = null;
            updateSubtitle(Component.empty());
        } else if (subtitle != null && !Objects.equals(subtitle.getKey(), subtitleCurrently)) {
            subtitleCurrently = subtitle.getKey();
            updateSubtitle(subtitle.getValue().text());
        }
    }

    protected void processAudio() {
        if (GRAB == null) return;
        long chunk = (GRAB.getTimestamp() / AudioSplitter.CHUNK_MICROS);
        if (chunk == lastAudioChunk) return;

        Entity source = SCREEN_HOLDER.getSoundSource();
        if (volume != 0F && source != null) {
            playSound(source, SoundEvent.createVariableRangeEvent(audioResource.withSuffix(String.format("%05x", chunk))), SoundSource.RECORDS, volume, 1f);
        }

        lastAudioChunk = chunk;
    }

    protected void playSound(Entity atEntity, SoundEvent soundEvent, SoundSource source, float volume, float pitch) {
        Vec3 position = atEntity.position();
        getLevel().playSound(atEntity, position.x, position.y, position.z, soundEvent, source, volume, pitch);
    }

    protected void updateSubtitle(Component text) {
        Display.TextDisplay subtitleScreen = SCREEN_HOLDER.getSubtitleDisplay();
        if (subtitleScreen != null) {
            subtitleScreen.setText(text);
        }
    }

    public void doEntityMerge(List<ServerboundUpdateNBTPacket.Modified> modifiedList, CompoundTag merge) {
        for (ServerboundUpdateNBTPacket.Modified modified : modifiedList) {
            Entity entity = getLevel().getEntity(modified.uuid());
            if (entity == null) continue;
            var position = modified.pos();
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
    }

    public VideoResult fixScreens() {
        if (SCREEN_HOLDER.hasNoScreens()) return VideoResult.NO_SCREENS;
        // Position Screens
        Entity entity = SCREEN_HOLDER.getMainDisplay();
        if (entity == null) return VideoResult.NO_SCREENS;
        pos = entity.position();
        ScreenMeta screenMeta = SCREEN_HOLDER.screenMeta();
        float scale = screenMeta.scale();
        ListTag SCALE = new ListTag();
        FloatTag SCALAR = FloatTag.valueOf(scale);
        SCALE.add(SCALAR);
        SCALE.add(SCALAR);
        SCALE.add(SCALAR);
        SCREEN_FIX.getCompound("transformation").ifPresent(compoundTag ->
                compoundTag.put("scale", SCALE));
        doEntityMerge(Collections.singletonList(new ServerboundUpdateNBTPacket.Modified(entity.getUUID(), null)), SCREEN_FIX);
        double heightPerScreen = screenMeta.heightPerScreen();

        UUID[] screenUUIDs = SCREEN_HOLDER.getScreenUUIDs();
        int screenCount = screenUUIDs.length;
        if (screenCount > 1) {
            List<ServerboundUpdateNBTPacket.Modified> modifiedList = new ArrayList<>(screenCount - 1);
            for (int i = 1; i < screenCount; ++i) {
                double offset = i * heightPerScreen * scale;
                Vec3 position = pos.add(0, offset, 0);
                modifiedList.add(new ServerboundUpdateNBTPacket.Modified(screenUUIDs[i], position));
            }

            updateDisplays(modifiedList, SCREEN_FIX);
        }
        return VideoResult.OK;
    }

    public void updateDisplays(List<ServerboundUpdateNBTPacket.Modified> modifiedList, CompoundTag merge) {
        doEntityMerge(modifiedList, merge);
    }

    public void videoDone() {
        if (replayMode == ReplayMode.LOOP && GRAB != null) {
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
            GRAB = null;
            playing = false;
            VIDEO_INFO = VideoInfo.NONE;
        }
        pos = null;
    }

    @Override
    public CompletableFuture<VideoResult> generateAudio() {
        if (GRAB == null)
            return CompletableFuture.completedFuture(VideoResult.NO_VIDEO);
        if (playing)
            return CompletableFuture.completedFuture(VideoResult.VIDEO_PLAYING);
        if (AUDIO_GENERATING != null && !AUDIO_GENERATING.isDone())
            return CompletableFuture.completedFuture(VideoResult.GEN_AUDIO);

        Path packFolder = Path.of(audioPath);
        Path soundsFolder = packFolder.resolve("assets/renderra/sounds");
        var future = CompletableFuture.supplyAsync(() -> {
            try {
                AudioSplitter.splitAudioFromVideo(GRAB, soundsFolder);

//                    Files.walk(soundResults).forEach(path -> {
//                        try {
//                            if (Files.isRegularFile(path)) {
//                                LOGGER.warn(path.toString());
//                                Path relative = soundResults.relativize(path);
//                                Path targetPath = soundsFolder.resolve(relative);
//
//                                Files.copy(path, targetPath, StandardCopyOption.REPLACE_EXISTING);
//                            }
//                        } catch (IOException e) {
//                            throw new RuntimeException(e);
//                        }
//                    });
//                    LOGGER.warn("WALK END");

//                    JsonObject sounds = new JsonObject();
//                    JsonArray arr = new JsonArray();
//                    try (DirectoryStream<Path> audioFiles = Files.newDirectoryStream(soundsFolder, "*.ogg")) {
//                        for (Path oggFile : audioFiles) {
//                            String name = oggFile.getFileName().toString().replace(".ogg", "");
//                            JsonObject soundEntry = new JsonObject();
//                            soundEntry.addProperty("name", name);
//                            arr.add(soundEntry);
//                        }
//                    }
//                    sounds.add("sounds", arr);
//                    Path soundsJson = packFolder.resolve("assets/renderra/sounds.json");
//                    Files.writeString(soundsJson, new GsonBuilder().create().toJson(sounds));
//                }
//                LOGGER.warn("RETURN");
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
            return VideoResult.OK;
        });
        this.AUDIO_GENERATING = future;
        return future;
    }

    public void setSubtitleEntity(UUID uuid) {
        if (getLevel().getEntity(uuid) instanceof Display.TextDisplay textDisplay) {
            SCREEN_HOLDER.setSubtitleDisplay(textDisplay);
        }
    }

    public VideoResult syncHz() {
        if (VIDEO_INFO == null) return VideoResult.NO_VIDEO;
        getLevel().tickRateManager().setTickRate((float) VIDEO_INFO.frameRate());
        return VideoResult.OK;
    }

    protected void processFrame() {
        BufferedImage bufferedImage = getTransformedImage(this.SCREEN_HOLDER.screenMeta(), this.GRAB, this::videoDone);
        if (bufferedImage != null) {
            this.fastDisplayImage(bufferedImage);
        }
    }

    public static @Nullable BufferedImage getTransformedImage(ScreenMeta screenMeta, FFmpegFrameGrabber GRAB, Runnable done) {
        Frame frame;
        try {
            frame = GRAB.grabImage(); // Note: Breaks when doing .grabFrame() with audio true
        } catch (FrameGrabber.Exception e) {
            done.run();
            return null;
        }
        if (frame == null) {
            done.run();
            return null;
        }

        // Return if Screen is 0 width or height
        if (screenMeta.zeroScale()) {
            return null;
        }

        // Rotate
        BufferedImage bufferedImage = rotate(IMAGE_CONVERTER.getBufferedImage(frame), screenMeta.rotation());

        // Resize
        int width = screenMeta.width();
        int height = screenMeta.height();
        BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, screenMeta.scaleMode().getRenderingHint());
        g.drawImage(bufferedImage, 0, 0, width, height, null);
        g.dispose();

        return resized;
    }

    public static @NonNull BufferedImage rotate(@NonNull BufferedImage src, Rotation rotation) {
        Objects.requireNonNull(src);
        if (rotation == null) return src;
        final int width = src.getWidth();
        final int height = src.getHeight();
        final boolean sideways = rotation.ordinal() == 1 || rotation.ordinal() == 3;
        BufferedImage rotated = new BufferedImage(sideways ? height : width, sideways ? width : height, src.getType());

        switch (rotation) {
            case NONE:
                return src;
            case CLOCKWISE_90:
                for (int y = 0; y < height; ++y) {
                    for (int x = 0; x < width; ++x) {
                        rotated.setRGB(height - 1 - y, x, src.getRGB(x, y));
                    }
                }
                break;
            case COUNTERCLOCKWISE_90:
                for (int y = 0; y < height; ++y) {
                    for (int x = 0; x < width; ++x) {
                        rotated.setRGB(y, width - 1 - x, src.getRGB(x, y));
                    }
                }
                break;
            case CLOCKWISE_180:
                for (int y = 0; y < height; ++y) {
                    for (int x = 0; x < width; ++x) {
                        rotated.setRGB(width - x - 1, height - y - 1, src.getRGB(x, y));
                    }
                }
                break;
        }

        return rotated;
    }

    public long getTimestamp() {
        if (GRAB == null) return 0;
        return GRAB.getTimestamp();
    }

    public void setTimestampSeconds(long timeStampSeconds) {
        if (GRAB == null) return;
        try {
            long timestamp = timeStampSeconds * 1_000_000;
            if (timestamp > GRAB.getLengthInTime()) return;
            GRAB.setTimestamp(timestamp);
        } catch (NumberFormatException | FFmpegFrameGrabber.Exception ignored) {
        }
    }

    protected void fastDisplayImage(BufferedImage bufferedImage) {
        int screenCount = this.SCREEN_HOLDER.getScreenCount();

        int width = bufferedImage.getWidth();
        int height = bufferedImage.getHeight();

        int sectionHeight = Math.round(((float) height) / (screenCount));
        int pixelHeight = height - sectionHeight * (screenCount - 1);
        Display.ItemDisplay mainScreen = this.SCREEN_HOLDER.getMainDisplay();
        new FastFrameManipulate(mainScreen.getId(), ImageIterable.parseImage(bufferedImage, width, height, height - pixelHeight))
                .sendBackDisplay(mainScreen);

        Integer[] ids = this.SCREEN_HOLDER.getScreenIDs();
        Display.ItemDisplay[] displays = this.SCREEN_HOLDER.getScreens();
        int length = ids.length;
        while (--length > 0) {
            int screen = ids[length];
            int curHeight = (screenCount - length - 1) * sectionHeight;
            int[] image = ImageIterable.parseImage(bufferedImage, width, curHeight + sectionHeight, curHeight);
            var fastFrameManipulate = new FastFrameManipulate(screen, image);
            fastFrameManipulate.sendBackDisplay(displays[length]);
        }
    }

    @Override
    public void tick() {
        if (this.SCREEN_HOLDER.eatDirty()) {
            fixScreens();
        }
    }

    @Override
    public void setVolume(float volume) {
        this.volume = volume;
    }
}
