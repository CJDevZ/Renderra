package eu.cj4.renderra.api.video;

public record VideoInfo(String fileName, double frameRate, long secondsLength, double frameDelta) {
    public static final VideoInfo NONE = new VideoInfo(null, 0, 0, 0);
}
