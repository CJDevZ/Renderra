package de.cjdev.renderra.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.*;
import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.moulberry.axiom.displayentity.DisplayEntityManipulator;
import de.cjdev.renderra.client.network.UpdateNBTPacket;
import de.cjdev.renderra.client.screen.VideoPlayerScreen;
import de.cjdev.renderra.subtitle.SRTLoader;
import de.cjdev.renderra.network.FastFrameManipulate;
import de.cjdev.renderra.network.ImageIterable;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.phys.AABB;
import org.lwjgl.glfw.GLFW;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import static de.cjdev.renderra.Renderra.*;

/// TESTING RESOLUTION: 128x72 // Currently: 108x54
public class VideoPlayerClient implements ClientModInitializer {

    public static final ResourceLocation MANIPULATE_ENTITY_IDENTIFIER = ResourceLocation.parse("axiom:manipulate_entity");
    public static final ResourceLocation UPDATE_NBT_IDENTIFIER = ResourceLocation.parse("renderra:update_nbt");

    public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(ResourceLocation.fromNamespaceAndPath("renderra", "keybinds"));
    public static final KeyMapping OPEN_VIDEO_PLAYER = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.renderra.open",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_B,
            CATEGORY
    ));

    //private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    public static BiConsumer<Minecraft, Double> UPDATE = null;
    public OperationMode operationMode = OperationMode.NONE;
    private Display lastSelectedDisplay;
    public final ClientPlaybackHandler PLAYBACK;
    //private final Robot ROBOT;
    public static Display.TextDisplay[] screens = null;
    private boolean axiomLoaded;

    public VideoPlayerClient()/* throws AWTException*/ {
        PLAYBACK = new ClientPlaybackHandler();
        //ROBOT = new Robot();
    }

    public enum OperationMode {
        NONE,
        ADD_SCREEN,
        REMOVE_SCREEN,
    }

    public void updateLightLevel(int lightLevel) {
        System.out.println("UPDATE LIGHT");

        CompoundTag compoundTag = new CompoundTag();
        CompoundTag brightnessTag = new CompoundTag();
        brightnessTag.putInt("block", lightLevel);
        brightnessTag.putInt("sky", 0);
        compoundTag.put("brightness", compoundTag);
        var screenUUIDs = PLAYBACK.SCREEN_META.getScreenUUIDs();
        List<de.cjdev.renderra.network.UpdateNBTPacket.Modified> modifiedList = new ArrayList<>(screenUUIDs.length);
        for (UUID screenUUID : screenUUIDs) {
            modifiedList.add(new de.cjdev.renderra.network.UpdateNBTPacket.Modified(screenUUID, null));
        }
        new UpdateNBTPacket(modifiedList, compoundTag).send();
    }

    public static boolean notInGame() {
        return Minecraft.getInstance().getConnection() == null;
    }

    public boolean addScreen(Display.TextDisplay display) {
        return PLAYBACK.SCREEN_META.addScreen(display);
    }

    public boolean removeScreen(Display.TextDisplay display) {
        return PLAYBACK.SCREEN_META.removeScreen(display);
    }

    public void handleFastFrameManipulate(FastFrameManipulate packet, LocalPlayer player) {
        if (player.level().getEntity(packet.getEntityID()) instanceof Display.TextDisplay textDisplay) {
            Component text = Component.literal("").withStyle(style ->
                    style.withFont(FONT));
            text.getSiblings().addAll(((ImageIterable) packet.getBufferedImage()).sections());
            textDisplay.setText(text);
        }
    }

    @Override
    public void onInitializeClient() {
        axiomLoaded = FabricLoader.getInstance().isModLoaded("axiom");

        VIDEOS_FOLDER.mkdirs();

        ClientPlayNetworking.registerGlobalReceiver(FastFrameManipulate.PACKET_TYPE, (payload, context) -> this.handleFastFrameManipulate(payload, context.player()));
        ClientPlayConnectionEvents.JOIN.register((clientPacketListener, packetSender, minecraft) -> {
            PLAYBACK.cleanUpVideo();

            PLAYBACK.warnedNoSupportedProtocol = false;
            PLAYBACK.SCREEN_META.screens.clear();
            PLAYBACK.SCREEN_META.subtitleScreen = null;

            PLAYBACK.subtitles = null;
            PLAYBACK.subtitleCurrently = null;
            operationMode = OperationMode.NONE;
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(getCommand()));

        UPDATE = PLAYBACK::deltaTick;

        AttackBlockCallback.EVENT.register((player, level, interactionHand, blockPos, direction) -> {
            if (!level.isClientSide() || player.getPermissionLevel() < 2) return InteractionResult.PASS;
            Minecraft minecraft = Minecraft.getInstance();
            if (!minecraft.options.keyAttack.isDown()) return InteractionResult.PASS;
            if (operationMode == OperationMode.ADD_SCREEN) {
                for (Display.TextDisplay screen : screens) {
                    if (CameraUtil.isMouseOverPoint(screen.position(), minecraft.player.getEyePosition(), minecraft.player.getLookAngle(), 0.18)) {
                        return InteractionResult.FAIL;
                    }
                }
            } else if (operationMode == OperationMode.REMOVE_SCREEN) {
                for (Display.TextDisplay screen : screens) {
                    if (CameraUtil.isMouseOverPoint(screen.position(), minecraft.player.getEyePosition(), minecraft.player.getLookAngle(), 0.18)) {
                        return InteractionResult.FAIL;
                    }
                }
            }
            return InteractionResult.PASS;
        });

        ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
            if (minecraft.player.getPermissionLevel() < 2) return;
            if (operationMode == OperationMode.ADD_SCREEN) {
                var camera = minecraft.getCameraEntity();
                if (camera == null) return;
                screens = minecraft.level.getEntitiesOfClass(Display.TextDisplay.class, AABB.ofSize(camera.position(), 10f, 10f, 10f)).toArray(Display.TextDisplay[]::new);
                if (minecraft.options.keyAttack.isDown()) {
                    for (Display.TextDisplay screen : screens) {
                        if (CameraUtil.isMouseOverPoint(screen.position(), minecraft.player.getEyePosition(), minecraft.player.getLookAngle(), 0.18)) {
                            boolean success = PLAYBACK.SCREEN_META.addScreen(screen);
                            addScreenMsg(minecraft, true, success);
                        }
                    }
                }
            } else if (operationMode == OperationMode.REMOVE_SCREEN) {
                screens = PLAYBACK.SCREEN_META.screens.toArray(Display.TextDisplay[]::new);
                if (minecraft.options.keyAttack.isDown()) {
                    for (Display.TextDisplay screen : screens) {
                        if (CameraUtil.isMouseOverPoint(screen.position(), minecraft.player.getEyePosition(), minecraft.player.getLookAngle(), 0.18)) {
                            boolean success = PLAYBACK.SCREEN_META.removeScreen(screen);
                            addScreenMsg(minecraft, false, success);
                        }
                    }
                }
            } else {
                screens = null;
            }
            PLAYBACK.tick();
            if (axiomLoaded && !(minecraft.screen instanceof VideoPlayerScreen)) {
                Display display = DisplayEntityManipulator.getActiveDisplayEntity();
                switch (this.operationMode) {
                    case ADD_SCREEN -> {
                        if (display instanceof Display.TextDisplay textDisplay && display != this.lastSelectedDisplay) {
                            boolean added = addScreen(textDisplay);
                            addScreenMsg(minecraft, true, added);
                        }
                    }
                    case REMOVE_SCREEN -> {
                        if (display instanceof Display.TextDisplay textDisplay && display != this.lastSelectedDisplay) {
                            boolean removed = removeScreen(textDisplay);
                            addScreenMsg(minecraft, false, removed);
                        }
                    }
                }
                if (this.lastSelectedDisplay != display) {
                    this.lastSelectedDisplay = display;
                }
            }
            if (minecraft.player != null && OPEN_VIDEO_PLAYER.isDown()) {
                minecraft.setScreen(new VideoPlayerScreen(minecraft.screen, this));
            }
        });
        WorldRenderEvents.BEFORE_TRANSLUCENT.register(CustomRenderPipeline.INSTANCE::extractAndDrawWaypoint);

        ClientEntityEvents.ENTITY_UNLOAD.register((entity, clientLevel) -> {
            if (!(entity instanceof Display.TextDisplay textDisplay)) return;
            PLAYBACK.SCREEN_META.removeScreen(textDisplay);
        });
    }

    private static void addScreenMsg(Minecraft minecraft, boolean add, boolean success) {
        if (add) {
            SystemToast.add(minecraft.getToastManager(), SystemToast.SystemToastId.NARRATOR_TOGGLE, Component.literal("VideoPlayer"), Component.literal(success ? "Added Screen" : "Already Added Screen"));
        } else {
            SystemToast.add(minecraft.getToastManager(), SystemToast.SystemToastId.NARRATOR_TOGGLE, Component.literal("VideoPlayer"), Component.literal(success ? "Removed Screen" : "Already like that"));
        }
    }

    private CompletableFuture<Suggestions> suggestTextDisplay(CommandContext<FabricClientCommandSource> context, SuggestionsBuilder builder) {
        Display activeDisplayEntity = DisplayEntityManipulator.getActiveDisplayEntity();
        if (activeDisplayEntity instanceof Display.TextDisplay)
            builder.suggest(activeDisplayEntity.getStringUUID());
        return builder.buildFuture();
    }

    public List<String> getVideoNames() {
        String[] fileNames = VIDEOS_FOLDER.list((dir, name) -> VIDEO_FILTER.test(name));
        List<String> defaults = new ArrayList<>();
        defaults.add("None");
        //try {
        //    Toolkit.getDefaultToolkit().getScre
        //    new Robot().createScreenCapture(new Rectangle(Toolkit.getDefaultToolkit().getScreenSize()));
        //} catch (AWTException e) {
        //    throw new RuntimeException(e);
        //}
        if (fileNames == null) {
            return defaults;
        }
        List<String> videoNames = Arrays.stream(fileNames).collect(Collectors.toList());
        for (int i = defaults.size() - 1; i == 0; i--) {
            videoNames.addFirst(defaults.get(i));
        }
        return videoNames;
    }

    private LiteralArgumentBuilder<FabricClientCommandSource> getCommand() {
        return ClientCommandManager.literal("vidplay").then(
                ClientCommandManager.literal("scale")
                        .then(ClientCommandManager.argument("scale", FloatArgumentType.floatArg(0, 10)).executes(this::setScale))
        ).then(
                ClientCommandManager.literal("subtitleDisplay")
                        .then(ClientCommandManager.argument("display", UuidArgument.uuid()).suggests(this::suggestTextDisplay).executes(this::setSubtitleUUID))
        ).then(
                ClientCommandManager.literal("subtitles")
                        .then(ClientCommandManager.argument("path", StringArgumentType.string()).suggests((context, builder) -> {
                            File[] files = VIDEOS_FOLDER.listFiles((dir, name) -> SUBTITLE_FILTER.test(name));
                            if (files == null) return builder.buildFuture();
                            for (File file : files) {
                                String name = file.getPath().substring(VIDEOS_FOLDER.getPath().length() + 1).replace('\\', '/');
                                builder.suggest(name.matches(".*\\s.*") ? ("\"" + name + "\"") : name);
                            }
                            return builder.buildFuture();
                        }).executes(this::setSubtitles))
        );
    }

    private int setSubtitleUUID(CommandContext<FabricClientCommandSource> context) {
        PLAYBACK.setSubtitleEntity(context.getArgument("display", UUID.class));
        return 1;
    }

    private int setScale(CommandContext<FabricClientCommandSource> context) {
        PLAYBACK.SCREEN_META.scale(context.getArgument("scale", float.class));
        return 1;
    }

    public Component getTimestampComponent() {
        return getTimestampComponent(PLAYBACK.getTimestamp());
    }

    public Component getTimestampComponent(double percentage) {
        return getTimestampComponent((long) (PLAYBACK.VIDEO_META.secondsLength() * 1_000_000 * percentage));
    }

    public Component getTimestampComponent(long timestamp) {
        long seconds = timestamp / 1_000_000L;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        seconds %= 60;
        minutes %= 60;
        StringBuilder builder = new StringBuilder();
        boolean doHours = hours > 0;
        if (doHours) {
            builder.append(hours);
            builder.append(':');
        }
        builder.append(doHours ? String.format("%02d", minutes) : minutes);
        builder.append(':');
        builder.append(String.format("%02d", seconds));

        return Component.literal(builder.toString());
    }

    private int setSubtitles(CommandContext<FabricClientCommandSource> context) {
        var filePath = context.getArgument("path", String.class);

        File srtFile = VIDEOS_FOLDER.toPath().resolve(filePath).toFile();
        if (!SUBTITLE_FILTER.test(srtFile.getName())) return 0;
        if (!srtFile.exists()) return 0;

        try {
            PLAYBACK.subtitles = SRTLoader.parseSRT(srtFile.toPath());
            VideoPlayerScreen.doToast("Loaded SRT file");
            return 1;
        } catch (IOException e) {
            VideoPlayerScreen.doToast("Failed to load SRT file");
            return 0;
        }
    }

}
