package de.cjdev.renderra.client.screen;

import de.cjdev.renderra.*;
import de.cjdev.renderra.client.VideoPlayerClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Rotation;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

public class VideoPlayerScreen extends Screen {
    private final Screen parent;
    private final VideoPlayerClient videoPlayer;

    private EditBox WIDTH_BOX;
    private EditBox HEIGHT_BOX;

    private Boolean wasPlaying;

    public VideoPlayerScreen(Screen parent, VideoPlayerClient videoPlayer) {
        super(Component.literal("Video Player"));
        this.parent = parent;
        this.videoPlayer = videoPlayer;
    }

    public static void doToast(String content) {
        SystemToast.add(Minecraft.getInstance().getToastManager(), SystemToast.SystemToastId.NARRATOR_TOGGLE, Component.literal("Video Player"), Component.literal(content));
    }

    @Override
    protected void init() {
        // Setup Edit Boxes
        this.WIDTH_BOX = new EditBox(this.font, 40, this.height - 40 - 30, 50, 20, Component.literal("Width"));
        this.HEIGHT_BOX = new EditBox(this.font, 100, this.height - 40 - 30, 50, 20, Component.literal("Height"));
        this.WIDTH_BOX.setValue(String.valueOf(videoPlayer.PLAYBACK.SCREEN_META.width()));
        this.HEIGHT_BOX.setValue(String.valueOf(videoPlayer.PLAYBACK.SCREEN_META.height()));

        var playButton = Button.builder(getPlayButtonText(), button1 -> {
            if (this.videoPlayer.PLAYBACK.playing) {
                this.videoPlayer.PLAYBACK.playing = false;
            } else {
                save();
                VideoResult resumeVideo = this.videoPlayer.PLAYBACK.resumeVideo();
                if (!resumeVideo.isOk()) {
                    if (resumeVideo.canDisplay()) doToast(resumeVideo.toast());
                    return;
                }
                if (resumeVideo.canDisplay()) doToast(resumeVideo.toast());
            }
            button1.setMessage(getPlayButtonText());
        }).bounds(40, this.height - 40, 20, 20).build();
        this.addRenderableWidget(playButton);

        var cycleLoopBtn = CycleButton.<ReplayMode>builder(o -> null)
                .withValues(ReplayMode.values())
                .withInitialValue(this.videoPlayer.PLAYBACK.replayMode)
                .create(70, this.height - 40, 20, 20, null, (cycleButton, object) -> {
                    this.videoPlayer.PLAYBACK.replayMode = object;
                    cycleButton.setMessage(Component.literal("\uD83D\uDD01").withColor(object.buttonColor));
                });
        this.addRenderableWidget(cycleLoopBtn);
        cycleLoopBtn.setMessage(Component.literal("\uD83D\uDD01").withColor(this.videoPlayer.PLAYBACK.replayMode.buttonColor));

        this.addRenderableWidget(CycleButton.<ColorMode>builder(o -> Component.literal(o.name()))
                .withValues(ColorMode.values())
                .withInitialValue(this.videoPlayer.PLAYBACK.colorMode)
                .create(160, this.height - 40 - 30 * 4, 115, 20, Component.literal("Color"), (cycleButton, object) ->
                        this.videoPlayer.PLAYBACK.colorMode = object));

        this.addRenderableWidget(new VideoSelectionDropDown(this.minecraft, 240, 100, 20, 20, 100));

        this.addRenderableWidget(CycleButton.builder(Component::literal)
                .withValues(this.videoPlayer.getVideoNames())
                .withInitialValue(this.videoPlayer.PLAYBACK.videoName)
                .create(160, this.height - 40 - 30, 240, 20, Component.literal("Video"), (cycleButton, value) -> {
                    this.videoPlayer.PLAYBACK.videoName = value;
                    playButton.setMessage(getPlayButtonText());
                }));

        CycleButton<VideoPlayerClient.OperationMode> opButton = CycleButton.<VideoPlayerClient.OperationMode>builder(o -> Component.literal(o.name()))
                .withValues(VideoPlayerClient.OperationMode.values())
                .withInitialValue(this.videoPlayer.operationMode)
                .create(40, this.height - 40 - 90, 110, 20, Component.literal("OP"), (cycleButton, value) ->
                        this.videoPlayer.operationMode = value);
        this.addRenderableWidget(opButton);

        this.addRenderableWidget(CycleButton.<Rotation>builder(rotation -> Component.literal(rotation.name()))
                .withValues(Rotation.values())
                .withInitialValue(this.videoPlayer.PLAYBACK.rotation)
                .create(40, this.height - 40 - 60, 110, 20, Component.literal("Rotation"), (cycleButton, value) ->
                        this.videoPlayer.PLAYBACK.rotation = value));

        this.addRenderableWidget(Button.builder(Component.literal("Gen Audio"), button1 -> {
            VideoResult result = this.videoPlayer.PLAYBACK.genAudio((success, e) -> {
                if (success) {
                    doToast("Generated Audio");
                } else {
                    doToast("Failed Generating Audio");
                    assert e != null;
                    Renderra.LOGGER.warn("Failed Generating Audio", e);
                }
            });
            if (result == VideoResult.OK) {
                doToast("Generating Audio");
            } else if (result.canDisplay()) doToast(result.toast());
        }).bounds(160, this.height - 40 - 60, 115, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Sync Hz"), button1 -> {
            VideoResult result = this.videoPlayer.PLAYBACK.syncHz();
            if (result == VideoResult.OK) {
                doToast("Synced Hz");
            } else if (result.canDisplay()) doToast(result.toast());
        }).bounds(285, this.height - 40 - 60, 115, 20).build());

        this.addRenderableWidget(this.WIDTH_BOX);
        this.addRenderableWidget(this.HEIGHT_BOX);

        this.addRenderableWidget(Checkbox.builder(Component.literal("Pretty"), this.font)
                .pos(40, this.height - 40 - 120)
                .selected(this.videoPlayer.PLAYBACK.SCREEN_META.pretty())
                .onValueChange((checkbox, bl) ->
                        this.videoPlayer.PLAYBACK.SCREEN_META.pretty(bl))
                .build());

        VideoMetaData videoMetaData = this.videoPlayer.PLAYBACK.VIDEO_META;
        this.addRenderableWidget(new TimestampSliderButton(100, this.height - 40, 300, 20, this.videoPlayer.getTimestampComponent(), videoMetaData == null ? 0 : ((double) this.videoPlayer.PLAYBACK.getTimestamp() / 1_000_000L) / ((double) videoMetaData.secondsLength()), this));
        this.addRenderableWidget(new VolumeSliderButton(160, this.height - 40 - 90, 115, 20, Component.literal("Volume: " + this.videoPlayer.PLAYBACK.volume), this.videoPlayer.PLAYBACK.volume / 2, this.videoPlayer.PLAYBACK));
    }

    public static class VolumeSliderButton extends AbstractSliderButton {

        private final PlaybackHandler PLAYBACK;

        public VolumeSliderButton(int i, int j, int k, int l, Component component, double d, PlaybackHandler PLAYBACK) {
            super(i, j, k, l, component, d);
            this.PLAYBACK = PLAYBACK;
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Component.literal("Volume: " + Math.floor(this.value * 20) / 10));
        }

        @Override
        protected void applyValue() {
            PLAYBACK.volume = (float) Math.floor(this.value * 20) / 10;
        }
    }

    public static class VideoSelectionDropDown extends ContainerObjectSelectionList<VideoSelectionDropDown.Entry> {

        public VideoSelectionDropDown(Minecraft minecraft, int width, int height, int y, int itemHeight, int a) {
            super(minecraft, width, height, y, itemHeight/*, a*/);
            this.addEntry(new Entry(minecraft.font, "Test"));
        }

        public static class Entry extends ContainerObjectSelectionList.Entry<Entry> {
            private final Font font;
            private final String value;

            public Entry(Font font, String value) {
                this.font = font;
                this.value = value;
            }

            //@Override
            //public void render(GuiGraphics guiGraphics, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float tickDelta) {
            //    guiGraphics.drawString(this.font, value, x + 4, y + 6, 0xFFFFFF, false);
            //}

            @Override
            public @NotNull List<? extends NarratableEntry> narratables() {
                return List.of();
            }

            @Override
            public @NotNull List<? extends GuiEventListener> children() {
                return List.of();
            }

            @Override
            public void renderContent(GuiGraphics guiGraphics, int y, int x, boolean hovered, float tickDelta) {
                guiGraphics.drawString(this.font, value, x + 4, y + 6, 0xFFFFFF, false);
            }
        }
    }

    public static class TimestampSliderButton extends AbstractSliderButton {

        private final VideoPlayerScreen videoPlayer;

        public TimestampSliderButton(int i, int j, int k, int l, Component component, double d, VideoPlayerScreen videoPlayer) {
            super(i, j, k, l, component, d);
            this.videoPlayer = videoPlayer;
        }

        @Override
        protected void updateMessage() {
            this.setMessage(this.videoPlayer.videoPlayer.getTimestampComponent(this.value));
        }

        @Override
        protected void applyValue() {

        }

        @Override
        protected void onDrag(MouseButtonEvent mouseButtonEvent, double d, double e) {
            if (this.videoPlayer.wasPlaying == null) {
                this.videoPlayer.wasPlaying = this.videoPlayer.videoPlayer.PLAYBACK.playing;
                this.videoPlayer.videoPlayer.PLAYBACK.playing = false;
            }
            super.onDrag(mouseButtonEvent, d, e);
        }

        @Override
        public void onRelease(MouseButtonEvent mouseButtonEvent) {
            applyTime();
            if (videoPlayer.wasPlaying != null) {
                this.videoPlayer.videoPlayer.PLAYBACK.playing = videoPlayer.wasPlaying;
            }
            super.onRelease(mouseButtonEvent);
        }

        @Override
        public void onClick(MouseButtonEvent mouseButtonEvent, boolean bl) {
            applyTime();
            super.onClick(mouseButtonEvent, bl);
        }

        public void applyTime() {
            VideoPlayerClient clientMod = this.videoPlayer.videoPlayer;
            VideoMetaData videoMetaData = clientMod.PLAYBACK.VIDEO_META;
            if (videoMetaData == null) {
                return;
            }
            long videoLength = videoMetaData.secondsLength();
            clientMod.PLAYBACK.setTimestampSeconds((long) (this.value * videoLength));
        }
    }

    public Component getPlayButtonText() {
        return Component.literal(this.videoPlayer.PLAYBACK.playing ? "⏸" :
                Objects.equals(this.videoPlayer.PLAYBACK.videoName, this.videoPlayer.PLAYBACK.VIDEO_META.fileName()) ? "▶"
                : "⏳");
    }

    @Override
    public void render(GuiGraphics guiGraphics, int i, int j, float f) {
        super.render(guiGraphics, i, j, f);
    }

    public void save() {
        try {
            this.videoPlayer.PLAYBACK.SCREEN_META.width(Integer.parseUnsignedInt(WIDTH_BOX.getValue()));
            this.videoPlayer.PLAYBACK.SCREEN_META.height(Integer.parseUnsignedInt(HEIGHT_BOX.getValue()));
        } catch (NumberFormatException ignored) {
        }
    }

    @Override
    public void onClose() {
        if (this.wasPlaying != null) {
            this.videoPlayer.PLAYBACK.playing = this.wasPlaying;
        }
        this.minecraft.setScreen(parent);
        save();
    }
}
