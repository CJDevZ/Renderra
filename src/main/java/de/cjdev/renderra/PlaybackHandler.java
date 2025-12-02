package de.cjdev.renderra;

import com.moulberry.axiom.packets.AxiomServerboundManipulateEntity;
import com.moulberry.axiom.utils.EntityDataUtils;
import de.cjdev.renderra.audio.AudioSplitter;
import de.cjdev.renderra.mixin.MixinChunkMap;
import de.cjdev.renderra.mixin.MixinTrackedEntity;
import de.cjdev.renderra.network.FastFrameManipulate;
import de.cjdev.renderra.subtitle.SRTLoader;
import de.cjdev.renderra.subtitle.Subtitle;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
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
    public ResourceLocation audioResource;

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
        colorMode = ColorMode.FIFTEEN_BIT;
        audioResource = ResourceLocation.parse("renderra:part");
        /// /// /// /// /// ///
        ListTag POSITION = new ListTag();
        POSITION.addAll(0, List.of(FloatTag.valueOf(-0.007f), FloatTag.valueOf(-0.013f), FloatTag.valueOf(0)));

        CompoundTag BRIGHTNESS_FIX = new CompoundTag();
        BRIGHTNESS_FIX.putInt("block", 15);
        BRIGHTNESS_FIX.putInt("sky", 0);

        CompoundTag TRANSFORMATION_FIX = new CompoundTag();
        TRANSFORMATION_FIX.put("translation", POSITION);

        CompoundTag TEXT_FIX = new CompoundTag();
        TEXT_FIX.putString("font", "m:p");
        TEXT_FIX.putString("text", "");

        SCREEN_FIX = new CompoundTag();
        SCREEN_FIX.put("transformation", TRANSFORMATION_FIX);
        SCREEN_FIX.put("text", TEXT_FIX);
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

        Display.TextDisplay screen = SCREEN_META.getMainScreen();
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

    public void doNBTMerge(UUID uuid, CompoundTag merge) {
        Entity entity = getLevel().getEntity(uuid);
        if (entity == null) return;
        CompoundTag compoundTag = EntityDataUtils.saveWithoutId(entity);
        compoundTag.merge(merge);
        EntityDataUtils.load(entity, compoundTag);
    }

    public VideoResult fixScreens() {
        if (SCREEN_META.screens.isEmpty()) return VideoResult.NO_SCREENS;
        // Position Screens
        Entity entity = SCREEN_META.getMainScreen();
        if (entity == null) return VideoResult.NO_SCREENS;
        pos = entity.position();
        ListTag SCALE = new ListTag();
        float scale = SCREEN_META.scale();
        SCALE.add(FloatTag.valueOf(scale));
        SCALE.add(FloatTag.valueOf(scale));
        SCALE.add(FloatTag.valueOf(scale));
        SCREEN_FIX.putInt("line_width", (SCREEN_META.pretty() ? 10 : 11) * SCREEN_META.width() + 1);
        SCREEN_FIX.getCompound("transformation").ifPresent(compoundTag ->
                compoundTag.put("scale", SCALE));
        doNBTMerge(entity.getUUID(), SCREEN_FIX);
        int screenCount = SCREEN_META.screens.size();
        int curScreen = screenCount - 1;
        int screenHeight = SCREEN_META.height();
        int sectionHeight = Math.round(((float) screenHeight) / (screenCount));

        var manipulateEntries = new ArrayList<AxiomServerboundManipulateEntity.ManipulateEntry>();
        UUID[] screenUUIDs = SCREEN_META.getScreenUUIDs();
        for (int i = 1; i < screenUUIDs.length; ++i) {
            double offset = (screenHeight - (curScreen--) * sectionHeight) * 0.25d * scale;
            Vec3 position = pos.add(0, offset, 0);
            var manipulateEntry = new AxiomServerboundManipulateEntity.ManipulateEntry(screenUUIDs[i], position, SCREEN_FIX);
            manipulateEntries.add(manipulateEntry);
        }
        new AxiomServerboundManipulateEntity(manipulateEntries).send();
        return VideoResult.OK;
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
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
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
        final boolean noSaving = this.SCREEN_META.pretty();
        ListTag listTag = new ListTag();
        StringBuilder curString = new StringBuilder();
        boolean fifteen_bit = colorMode == ColorMode.FIFTEEN_BIT;
        int fifteen_bit_colors = 30;
        if (fifteen_bit) {
            for (int y = heightOffset; y < height; ++y) {
                for (int x = 0; x < width; ++x) {
                    boolean endX = x == width - 1;
                    int rgb = image.getRGB(x, y) & 0xFFFFFF;

                    int red = Math.round(((rgb >> 16) & 0xFF) / 255f * (fifteen_bit_colors - 1));
                    int green = Math.round(((rgb >> 8) & 0xFF) / 255f * (fifteen_bit_colors - 1));
                    int blue = Math.round((rgb & 0xFF) / 255f * (fifteen_bit_colors - 1));
                    curString.append((char) (blue * fifteen_bit_colors * fifteen_bit_colors + green * fifteen_bit_colors + red + 12832));
                    if (noSaving && !endX) curString.append('.');
                }
            }

            sectionToCompound(listTag, curString.toString(), -1);
        } else {
            int lastColor = -1;
            for (int y = heightOffset; y < height; ++y) {
                for (int x = 0; x < width; ++x) {
                    int rgb = image.getRGB(x, y) & 0xFFFFFF;

                    if (lastColor == -1) {
                        // First pixel
                        lastColor = rgb;
                    }

                    boolean endX = x == width - 1;

                    if (rgb == lastColor) {
                        curString.append("A");
                        if (noSaving && !endX) curString.append('.');
                    } else {
                        // Color changed — save current segment
                        sectionToCompound(listTag, curString.toString(), lastColor);

                        curString.setLength(0); // Clear builder
                        curString.append("A");
                        if (noSaving && !endX) curString.append('.');
                        lastColor = rgb;
                    }
                }
            }

            // Add last segment if not empty
            if (!curString.isEmpty()) {
                sectionToCompound(listTag, curString.toString(), lastColor);
            }
        }

        CompoundTag screenCompound = new CompoundTag();
        screenCompound.put("extra", listTag);
        CompoundTag compoundTag = new CompoundTag();
        compoundTag.put("text", screenCompound);

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

    protected void fastDisplayImage(BufferedImage bufferedImage) {
        int screenCount = SCREEN_META.screens.size();

        int width = bufferedImage.getWidth();
        int height = bufferedImage.getHeight();

        int sectionHeight = Math.round(((float) height) / (screenCount));
        int pixelHeight = height - sectionHeight * (screenCount - 1);
        ClientboundCustomPayloadPacket[] packets = new ClientboundCustomPayloadPacket[screenCount];
        packets[0] = new ClientboundCustomPayloadPacket(new FastFrameManipulate(SCREEN_META.getMainScreen().getId(), this.colorMode, SCREEN_META.pretty(), width, height, height - pixelHeight, bufferedImage));

        Integer[] ids = SCREEN_META.getScreenIDs();
        int length = ids.length;
        while (--length > 0) {
            int screen = ids[length];
            int curHeight = (screenCount - length - 1) * sectionHeight;
            packets[length] = new ClientboundCustomPayloadPacket(new FastFrameManipulate(screen, this.colorMode, SCREEN_META.pretty(), width, curHeight + sectionHeight, curHeight, bufferedImage));
        }

        Display.TextDisplay mainScreen = SCREEN_META.getMainScreen();
        for (ServerPlayerConnection connection : ((MixinTrackedEntity) ((MixinChunkMap) ((ServerChunkCache) mainScreen.level().getChunkSource()).chunkMap).getEntityMap().get(mainScreen.getId())).getSeenBy()) {
            ServerPlayer serverPlayer = connection.getPlayer();
            if (!ServerPlayNetworking.canSend(serverPlayer, FastFrameManipulate.PACKET_TYPE)) continue;
            for (var packet : packets) {
                connection.send(packet);
            }
        }
    }

    public void tick() {
        if (SCREEN_META.dirty) {
            SCREEN_META.dirty = false;
            fixScreens();
        }
    }
}
