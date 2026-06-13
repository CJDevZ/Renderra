package eu.cj4.renderra.client.impl;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.moulberry.axiom.displayentity.DisplayEntityManipulator;
import eu.cj4.renderra.api.video.ScreenMeta;
import eu.cj4.renderra.client.impl.screen.VideoPlayerScreen;
import eu.cj4.renderra.client.impl.video.ClientPlaybackHandler;
import eu.cj4.renderra.impl.subtitle.SRTLoader;
import eu.cj4.renderra.impl.network.FastFrameManipulate;
import eu.cj4.renderra.impl.video.ScreenHolderImpl;
import it.unimi.dsi.fastutil.ints.IntList;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.phys.AABB;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import static eu.cj4.renderra.impl.Renderra.*;

/// TESTING RESOLUTION: 128x72 // Currently: 108x54
public class VideoPlayerClient implements ClientModInitializer {

    public static final Identifier MANIPULATE_ENTITY_IDENTIFIER = Identifier.parse("axiom:manipulate_entity");
    public static final Identifier UPDATE_NBT_IDENTIFIER = Identifier.parse("renderra:update_nbt");

    public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("renderra", "keybinds"));
    public static final KeyMapping OPEN_VIDEO_PLAYER = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.renderra.open",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_B,
            CATEGORY
    ));

    //private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    public static BiConsumer<Minecraft, Double> UPDATE = null;
    public OperationMode operationMode = OperationMode.NONE;
    private Display lastSelectedDisplay;
    public ClientPlaybackHandler PLAYBACK;
    //private final Robot ROBOT;
    public static Display.ItemDisplay[] screens = null;
    private static final boolean axiomLoaded = FabricLoader.getInstance().isModLoaded("axiom");

    public VideoPlayerClient()/* throws AWTException*/ {
        PLAYBACK = new ClientPlaybackHandler();
        //ROBOT = new Robot();
    }

    public enum OperationMode {
        NONE,
        ADD_SCREEN,
        REMOVE_SCREEN;

        public static final OperationMode[] VALUES = OperationMode.values();
    }

    public static boolean notInGame() {
        return Minecraft.getInstance().getConnection() == null;
    }

    public boolean addScreen(Display.ItemDisplay display) {
        return this.PLAYBACK.SCREEN_HOLDER.addScreen(display);
    }

    public boolean removeScreen(Display.ItemDisplay display) {
        return this.PLAYBACK.SCREEN_HOLDER.removeScreen(display);
    }

    public void handleFastFrameManipulate(FastFrameManipulate packet, LocalPlayer player) {
        if (player.level().getEntity(packet.entityID()) instanceof Display.ItemDisplay display) {
            display.getItemStack().update(DataComponents.CUSTOM_MODEL_DATA, CustomModelData.EMPTY, customModelData ->
                    new CustomModelData(
                            customModelData.floats(),
                            customModelData.flags(),
                            customModelData.strings(),
                            IntList.of(packet.pixels())
                    )
            );
        }
    }

    @Override
    public void onInitializeClient() {
        VIDEOS_FOLDER.mkdirs();

        ClientPlayNetworking.registerGlobalReceiver(FastFrameManipulate.PACKET_TYPE, (payload, context) -> this.handleFastFrameManipulate(payload, context.player()));
        ClientPlayConnectionEvents.JOIN.register((clientPacketListener, packetSender, minecraft) -> {
            ScreenMeta screenMeta = PLAYBACK.SCREEN_HOLDER.screenMeta();
            PLAYBACK.close();
            PLAYBACK = new ClientPlaybackHandler(new ScreenHolderImpl(screenMeta));
            UPDATE = PLAYBACK::deltaTick;
            this.operationMode = OperationMode.NONE;
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(getCommand()));

        if (!axiomLoaded) {
            AttackBlockCallback.EVENT.register((player, level, interactionHand, blockPos, direction) -> {
                if (!level.isClientSide() || !player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                    return InteractionResult.PASS;
                Minecraft minecraft = Minecraft.getInstance();
                if (!minecraft.options.keyAttack.isDown()) return InteractionResult.PASS;
                if (operationMode == OperationMode.ADD_SCREEN) {
                    for (Display.ItemDisplay screen : screens) {
                        if (CameraUtil.isMouseOverPoint(screen.position(), minecraft.player.getEyePosition(), minecraft.player.getLookAngle(), 0.18)) {
                            return InteractionResult.FAIL;
                        }
                    }
                } else if (operationMode == OperationMode.REMOVE_SCREEN) {
                    for (Display.ItemDisplay screen : screens) {
                        if (CameraUtil.isMouseOverPoint(screen.position(), minecraft.player.getEyePosition(), minecraft.player.getLookAngle(), 0.18)) {
                            return InteractionResult.FAIL;
                        }
                    }
                }
                return InteractionResult.PASS;
            });
        }

        ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
            if (minecraft.player == null) return;
            if (!minecraft.player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) return;
            if (!axiomLoaded) {
                if (operationMode == OperationMode.ADD_SCREEN) {
                    var camera = minecraft.getCameraEntity();
                    if (camera == null) return;
                    screens = minecraft.level.getEntitiesOfClass(Display.ItemDisplay.class, AABB.ofSize(camera.position(), 10f, 10f, 10f)).toArray(Display.ItemDisplay[]::new);
                    if (minecraft.options.keyAttack.isDown()) {
                        for (Display.ItemDisplay screen : screens) {
                            if (CameraUtil.isMouseOverPoint(screen.position(), minecraft.player.getEyePosition(), minecraft.player.getLookAngle(), 0.18)) {
                                boolean success = PLAYBACK.SCREEN_HOLDER.addScreen(screen);
                                addScreenMsg(minecraft, true, success);
                            }
                        }
                    }
                } else if (operationMode == OperationMode.REMOVE_SCREEN) {
                    screens = PLAYBACK.SCREEN_HOLDER.getScreens();
                    if (minecraft.options.keyAttack.isDown()) {
                        for (Display.ItemDisplay screen : screens) {
                            if (CameraUtil.isMouseOverPoint(screen.position(), minecraft.player.getEyePosition(), minecraft.player.getLookAngle(), 0.18)) {
                                boolean success = PLAYBACK.SCREEN_HOLDER.removeScreen(screen);
                                addScreenMsg(minecraft, false, success);
                            }
                        }
                    }
                } else {
                    screens = null;
                }
            }
            PLAYBACK.tick();
            // Axiom
            if (axiomLoaded && !(minecraft.screen instanceof VideoPlayerScreen)) {
                Display display = DisplayEntityManipulator.getActiveDisplayEntity();
                switch (this.operationMode) {
                    case ADD_SCREEN -> {
                        if (display instanceof Display.ItemDisplay itemDisplay && display != this.lastSelectedDisplay) {
                            boolean added = addScreen(itemDisplay);
                            addScreenMsg(minecraft, true, added);
                        }
                    }
                    case REMOVE_SCREEN -> {
                        if (display instanceof Display.ItemDisplay itemDisplay && display != this.lastSelectedDisplay) {
                            boolean removed = removeScreen(itemDisplay);
                            addScreenMsg(minecraft, false, removed);
                        }
                    }
                }
                if (this.lastSelectedDisplay != display) {
                    this.lastSelectedDisplay = display;
                }
            }
            //
            if (minecraft.player != null && OPEN_VIDEO_PLAYER.isDown()) {
                minecraft.setScreen(new VideoPlayerScreen(minecraft.screen, this));
            }
        });
        LevelRenderEvents.END_MAIN.register(CustomRenderPipeline.INSTANCE::extractAndDrawWaypoint);

        ClientEntityEvents.ENTITY_UNLOAD.register((entity, clientLevel) -> {
            if (!(entity instanceof Display.ItemDisplay display)) return;
            this.PLAYBACK.SCREEN_HOLDER.removeScreen(display);
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
        if (!axiomLoaded) {
            return builder.buildFuture();
        }
        Display activeDisplayEntity = com.moulberry.axiom.displayentity.DisplayEntityManipulator.getActiveDisplayEntity();
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
        return ClientCommands.literal("vidplay").then(
                ClientCommands.literal("scale")
                        .then(ClientCommands.argument("scale", FloatArgumentType.floatArg(0, 1000)).executes(this::setScale))
        ).then(
                ClientCommands.literal("subtitleDisplay")
                        .then(ClientCommands.argument("display", UuidArgument.uuid()).suggests(this::suggestTextDisplay).executes(this::setSubtitleUUID))
        ).then(
                ClientCommands.literal("subtitles")
                        .then(ClientCommands.argument("path", StringArgumentType.string()).suggests((context, builder) -> {
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
        PLAYBACK.SCREEN_HOLDER.scale(context.getArgument("scale", float.class));
        return 1;
    }

    public Component getTimestampComponent() {
        return getTimestampComponent(PLAYBACK.getTimestamp());
    }

    public Component getTimestampComponent(double percentage) {
        return getTimestampComponent((long) (PLAYBACK.VIDEO_INFO.secondsLength() * 1_000_000 * percentage));
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
