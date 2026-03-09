package de.cjdev.renderra.video;

import de.cjdev.renderra.VideoResult;
import de.cjdev.renderra.audio.AudioSplitter;
import de.cjdev.renderra.network.FastFrameManipulate;
import de.cjdev.renderra.network.ImageIterable;
import de.cjdev.renderra.network.UpdateNBTPacket;
import de.cjdev.renderra.subtitle.SRTLoader;
import de.cjdev.renderra.subtitle.Subtitle;
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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static de.cjdev.renderra.Renderra.*;

public class PlaybackHandler {

    protected FFmpegFrameGrabber GRAB;

    public VideoMetaData VIDEO_META;
    public final ScreenMetaData SCREEN_META;

    public NavigableMap<Long, Subtitle> subtitles;
    public Long subtitleCurrently = null;
    protected long lastAudioChunk = -1;
    public String videoName = "None";
    private String audioPath;

    public CompletableFuture<?> AUDIO_GENERATING = null;
    private final CompoundTag SCREEN_FIX;
    public Rotation rotation = Rotation.NONE;

    public boolean playing;
    public float volume;
    public ReplayMode replayMode;
    public ColorMode colorMode;
    public ScaleMode scaleMode;
    public Identifier audioResource;

    public Level level;
    protected static Vec3 pos;

    public PlaybackHandler() {
        this(new ScreenMetaData());
    }

    public PlaybackHandler(ScreenMetaData screenMeta) {
        SCREEN_META = screenMeta;
        VIDEO_META = VideoMetaData.None();
        volume = 1f;
        replayMode = ReplayMode.NORMAL;
        colorMode = ColorMode.FULL;
        scaleMode = ScaleMode.BILINEAR;
        audioResource = Identifier.parse("renderra:part");
        /// /// /// /// /// ///
        ListTag SCALE = new ListTag();
        FloatTag SCALAR = FloatTag.valueOf(screenMeta.scale());
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
        if (SCREEN_META.screens.isEmpty()) return VideoResult.NO_SCREENS;
        this.playing = false;
        this.subtitleCurrently = null;

        File videoFile = new File(VIDEOS_FOLDER, videoName);
        if (!VIDEO_FILTER.test(videoFile.getName()) || !videoFile.exists()) return VideoResult.INVALID_VIDEO;

        if (GRAB != null) {
            try {
                GRAB.stop();
            } catch (FFmpegFrameGrabber.Exception ignored) {
            }
        }
        subtitles = new TreeMap<>();

        try {
            audioPath = "videoaudio/" + videoFile.getName();
            GRAB = new FFmpegFrameGrabber(videoFile);
            GRAB.start();
        } catch (FFmpegFrameGrabber.Exception e) {
            GRAB = null;
            return VideoResult.FFmpeg_ERROR;
        }

        // Position Screens
        var result = fixScreens();
        if (!result.isOk()) return result;

        processFrame(GRAB);
        try {
            GRAB.setTimestamp(0);
        } catch (FFmpegFrameGrabber.Exception ignored) {
        }
        VIDEO_META = new VideoMetaData(videoName, GRAB.getFrameRate(), GRAB.getLengthInTime() / 1_000_000L, 1 / GRAB.getFrameRate());
        lastAudioChunk = -1;
        updateSubtitle(Component.empty());
        return VideoResult.LOADED_VIDEO;
    }

    public VideoResult resumeVideo() {
        if (AUDIO_GENERATING != null && !AUDIO_GENERATING.isDone()) return VideoResult.GEN_AUDIO;
        if (GRAB == null || !Objects.equals(videoName, VIDEO_META.fileName())) {
            return setupVideo();
        }
        this.playing = true;
        return VideoResult.OK;
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

        Display.ItemDisplay screen = SCREEN_META.getMainScreen();
        if (volume != 0F) {
            playSound(screen, SoundEvent.createVariableRangeEvent(audioResource.withSuffix(String.format("%05x", chunk))), SoundSource.RECORDS, volume, 1f);
        }

        lastAudioChunk = chunk;
    }

    protected void playSound(Entity atEntity, SoundEvent soundEvent, SoundSource source, float volume, float pitch) {
        Vec3 position = atEntity.position();
        getLevel().playSound(atEntity, position.x, position.y, position.z, soundEvent, source, volume, pitch);
    }

    protected void updateSubtitle(Component text) {
        if (SCREEN_META.subtitleScreen == null) return;
        SCREEN_META.subtitleScreen.setText(text);
    }

    public void doEntityMerge(List<de.cjdev.renderra.network.UpdateNBTPacket.Modified> modifiedList, CompoundTag merge) {
        for (UpdateNBTPacket.Modified modified : modifiedList) {
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
        if (SCREEN_META.screens.isEmpty()) return VideoResult.NO_SCREENS;
        // Position Screens
        Entity entity = SCREEN_META.getMainScreen();
        if (entity == null) return VideoResult.NO_SCREENS;
        pos = entity.position();
        float scale = SCREEN_META.scale();
        ListTag SCALE = new ListTag();
        FloatTag SCALAR = FloatTag.valueOf(scale);
        SCALE.add(SCALAR);
        SCALE.add(SCALAR);
        SCALE.add(SCALAR);
        SCREEN_FIX.getCompound("transformation").ifPresent(compoundTag ->
                compoundTag.put("scale", SCALE));
        doEntityMerge(List.of(new UpdateNBTPacket.Modified(entity.getUUID(), null)), SCREEN_FIX);
        double heightPerScreen = SCREEN_META.heightPerScreen();

        UUID[] screenUUIDs = SCREEN_META.getScreenUUIDs();
        int screenCount = screenUUIDs.length;
        if (screenCount > 1) {
            List<UpdateNBTPacket.Modified> modifiedList = new ArrayList<>(screenCount - 1);
            for (int i = 1; i < screenCount; ++i) {
                double offset = i * heightPerScreen * scale;
                Vec3 position = pos.add(0, offset, 0);
                modifiedList.add(new UpdateNBTPacket.Modified(screenUUIDs[i], position));
            }

            updateDisplays(modifiedList, SCREEN_FIX);
        }
        return VideoResult.OK;
    }

    public void updateDisplays(List<UpdateNBTPacket.Modified> modifiedList, CompoundTag merge) {
        doEntityMerge(modifiedList, merge);
    }

    public void videoDone() {
        LOGGER.warn("DONE");
        if (replayMode == ReplayMode.LOOP && GRAB != null) {
            try {
                LOGGER.warn("LOOP");
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
            VIDEO_META = VideoMetaData.None();
        }
        pos = null;
    }

    public VideoResult genAudio(BiConsumer<Boolean, @Nullable Exception> callback) {
        if (GRAB == null) return VideoResult.NO_VIDEO;
        if (playing) return VideoResult.VIDEO_PLAYING;
        if (AUDIO_GENERATING != null && !AUDIO_GENERATING.isDone()) return VideoResult.GEN_AUDIO;

        Path packFolder = Path.of(audioPath);
        Path soundsFolder = packFolder.resolve("assets/renderra/sounds");
        AUDIO_GENERATING = CompletableFuture.runAsync(() -> {
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

                callback.accept(true, null);
            } catch (IOException | InterruptedException e) {
                callback.accept(false, e);
            }
        });
        return VideoResult.OK;
    }

    public void setSubtitleEntity(UUID uuid) {
        if (getLevel().getEntity(uuid) instanceof Display.TextDisplay textDisplay) {
            SCREEN_META.subtitleScreen = textDisplay;
        }
    }

    public VideoResult syncHz() {
        if (VIDEO_META == null) return VideoResult.NO_VIDEO;
        getLevel().tickRateManager().setTickRate((float) VIDEO_META.frameRate());
        return VideoResult.OK;
    }

    protected void processFrame(FFmpegFrameGrabber GRAB) {
        //if (ClientPlayNetworking.canSend(PACKET_TYPE)) {
        processFastFrame(GRAB, this::fastDisplayImage, this::videoDone);
        //} else if (ClientPlayNetworking.canSend(AxiomServerboundManipulateEntity.IDENTIFIER)) {
        //    processAxiomFrame(GRAB, this::axiomDisplayImage, this::cleanUpVideo);
        //} else if (!this.warnedNoSupportedProtocol) {
        //    warnedNoSupportedProtocol = true;
        //    VideoPlayerScreen.doToast("No Supported Protocol");
        //}
    }

    protected void processAxiomFrame(FFmpegFrameGrabber GRAB, Consumer<CompoundTag[]> callback, Runnable done) {
        BufferedImage transformedImage = getTransformedImage(GRAB, done);
        if (transformedImage == null) return;

        int width = SCREEN_META.width();
        int height = SCREEN_META.height();

        var screenCount = SCREEN_META.screens.size();
        CompoundTag[] results = new CompoundTag[screenCount];
        int sectionHeight = Math.round(((float) height) / (screenCount));
        int pixelHeight = height - sectionHeight * (screenCount - 1);
        results[0] = convertImageToComponents(transformedImage, width, height, height - pixelHeight);
        for (int i = 1; i < screenCount; ++i) {
            int curHeight = (screenCount - i - 1) * sectionHeight;
            results[i] = convertImageToComponents(transformedImage, width, curHeight + sectionHeight, curHeight);
        }
        callback.accept(results);
    }

    protected void processFastFrame(FFmpegFrameGrabber GRAB, Consumer<BufferedImage> callback, Runnable done) {
        BufferedImage transformedImage = getTransformedImage(GRAB, done);
        if (transformedImage == null) return;
        callback.accept(transformedImage);
    }

    private @Nullable BufferedImage getTransformedImage(FFmpegFrameGrabber GRAB, Runnable done) {
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
        if (SCREEN_META.invisible()) {
            return null;
        }

        // Rotate
        BufferedImage bufferedImage = rotate(IMAGE_CONVERTER.getBufferedImage(frame), rotation);

        // Resize
        int width = SCREEN_META.width();
        int height = SCREEN_META.height();
        BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, this.scaleMode.getRenderingHint());
        g.drawImage(bufferedImage, 0, 0, width, height, null);
        g.dispose();

        return resized;
    }

    public static @NotNull BufferedImage rotate(@NotNull BufferedImage src, Rotation rotation) {
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

    private CompoundTag convertImageToComponents(final BufferedImage image, final int width, final int height, final int heightOffset) {
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

    private static void sectionToCompound(ListTag listTag, String pixels, int lastColor) {
        final int CHUNK_SIZE = Short.MAX_VALUE;

        int totalLength = pixels.length();

        String firstChunk = pixels.substring(0, Math.min(CHUNK_SIZE, totalLength));
        if (lastColor == -1) {
            listTag.add(StringTag.valueOf(firstChunk));
        } else {
            CompoundTag textCompound = new CompoundTag();
            textCompound.putString("text", firstChunk);
            textCompound.putString("color", String.format(Locale.ROOT, "#%06X", lastColor));
            listTag.add(textCompound);
        }

        // Write the rest in chunks
        int numChunks = (totalLength + CHUNK_SIZE - 1) / CHUNK_SIZE;
        for (int chunk = 1; chunk < numChunks; ++chunk) {
            int start = chunk * CHUNK_SIZE;
            int end = Math.min(start + CHUNK_SIZE, totalLength);
            listTag.add(StringTag.valueOf(pixels.substring(start, end)));
        }
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

    //public void sendScreen(Display.TextDisplay textDisplay, ImageIterable bufferedImage) {
    //    Component text = Component.literal("").withStyle(style ->
    //            style.withFont(ResourceLocation.fromNamespaceAndPath("m", "p")));
    //    text.getSiblings().addAll(bufferedImage.sections());
    //    var vanillaPacket = new ClientboundSetEntityDataPacket(textDisplay.getId(), List.of(new SynchedEntityData.DataValue<>(23, EntityDataSerializers.COMPONENT, text)));
    //    var moddedPacket = new ClientboundCustomPayloadPacket(this);
//
    //    for (ServerPlayerConnection connection : ((MixinTrackedEntity) ((MixinChunkMap) ((ServerChunkCache) level.getChunkSource()).chunkMap).getEntityMap().get(displayId)).getSeenBy()) {
    //        ServerPlayer serverPlayer = connection.getPlayer();
    //        if (ServerPlayNetworking.canSend(serverPlayer, PACKET_TYPE)) {
    //            connection.send(moddedPacket);
    //        } else {
    //            connection.send(vanillaPacket);
    //        }
    //    }
    //}

    protected void sendFastFramePacket(FastFrameManipulate fastFrameManipulate) {

    }

    protected void fastDisplayImage(BufferedImage bufferedImage) {
        int screenCount = SCREEN_META.screens.size();

        int width = bufferedImage.getWidth();
        int height = bufferedImage.getHeight();

        int sectionHeight = Math.round(((float) height) / (screenCount));
        int pixelHeight = height - sectionHeight * (screenCount - 1);
        Display.ItemDisplay mainScreen = SCREEN_META.getMainScreen();
        new FastFrameManipulate(mainScreen.getId(), ImageIterable.parseImage(bufferedImage, width, height, height - pixelHeight))
                .sendBackDisplay(mainScreen);

        Integer[] ids = SCREEN_META.getScreenIDs();
        Display.ItemDisplay[] displays = SCREEN_META.getScreens();
        int length = ids.length;
        while (--length > 0) {
            int screen = ids[length];
            int curHeight = (screenCount - length - 1) * sectionHeight;
            int[] image = ImageIterable.parseImage(bufferedImage, width, curHeight + sectionHeight, curHeight);
            var fastFrameManipulate = new FastFrameManipulate(screen, image);
            fastFrameManipulate.sendBackDisplay(displays[length]);
        }
    }

    public void tick() {
        if (SCREEN_META.dirty) {
            SCREEN_META.dirty = false;
            fixScreens();
        }
    }

    public static PlaybackHandler createPlaybackHandler(Display.ItemDisplay[] displays, int resolutionX, int resolutionY, float scale) {
        PlaybackHandler playbackHandler = new PlaybackHandler();

        ScreenMetaData screenMeta = playbackHandler.SCREEN_META;
        screenMeta.addScreens(displays);
        screenMeta.width(resolutionX);
        screenMeta.height(resolutionY);
        screenMeta.scale(scale);

        return playbackHandler;
    }
}
