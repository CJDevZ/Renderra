package de.cjdev.renderra;

import org.jetbrains.annotations.Nullable;

public enum VideoResult {
    NO_VIDEO("No Video"),
    NO_SCREENS("No Screens"),
    GEN_AUDIO("Gen Audio"),
    VIDEO_PLAYING("Video Playing"),
    INVALID_VIDEO("Invalid Video"),
    FFmpeg_ERROR("FFmpeg Error"),
    INVALID_INPUT("Invalid Input"),
    LOADED_VIDEO("Loaded Video", true),
    OK(null, true);

    private final @Nullable String toast;
    private final boolean isOk;

    VideoResult(@Nullable String toast) {
        this(toast, false);
    }

    VideoResult(@Nullable String toast, boolean isOk) {
        this.toast = toast;
        this.isOk = isOk;
    }

    public String toast(){
        return this.toast;
    }

    public boolean canDisplay() {
        return this.toast != null;
    }

    public boolean isOk() {
        return this.isOk;
    }
}
