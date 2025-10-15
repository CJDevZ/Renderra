package de.cjdev.renderra;

public record VideoMetaData(String fileName, double frameRate, long secondsLength, double frameDelta) {
    public static VideoMetaData None() {
        return new VideoMetaData(null, 0, 0, 0);
    }
}
