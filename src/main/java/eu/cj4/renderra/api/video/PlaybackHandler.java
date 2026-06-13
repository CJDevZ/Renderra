package eu.cj4.renderra.api.video;

import eu.cj4.renderra.api.VideoResult;
import eu.cj4.renderra.impl.video.VirtualPlaybackHandlerImpl;
import net.minecraft.resources.Identifier;

import java.io.File;
import java.util.concurrent.CompletableFuture;

public interface PlaybackHandler {
    static VirtualPlaybackHandlerImpl createVirtual() {
        return new VirtualPlaybackHandlerImpl();
    }

    static VirtualPlaybackHandlerImpl createVirtual(ScreenMeta screenMeta) {
        return new VirtualPlaybackHandlerImpl(screenMeta);
    }

    static VirtualPlaybackHandlerImpl createVirtual(ScreenMeta screenMeta, Identifier screenItemModel) {
        return new VirtualPlaybackHandlerImpl(screenMeta, screenItemModel);
    }

    VideoResult setVideo(File videoFile);
    VideoResult resumeVideo();
    void pauseVideo();
    void tick();
    CompletableFuture<VideoResult> generateAudio();

    void setVolume(float volume);
}
