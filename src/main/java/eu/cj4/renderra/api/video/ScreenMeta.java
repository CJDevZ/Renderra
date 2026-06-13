package eu.cj4.renderra.api.video;

import net.minecraft.world.level.block.Rotation;

public record ScreenMeta(int width, int height, double heightPerScreen, float scale, Rotation rotation, ScaleMode scaleMode) {
    public ScreenMeta setWidth(int width) {
        return new ScreenMeta(width, height, heightPerScreen, scale, rotation, scaleMode);
    }

    public ScreenMeta setHeight(int height) {
        return new ScreenMeta(width, height, heightPerScreen, scale, rotation, scaleMode);
    }

    public ScreenMeta setResolution(int width, int height) {
        return new ScreenMeta(width, height, heightPerScreen, scale, rotation, scaleMode);
    }

    public ScreenMeta setHeightPerScreen(double heightPerScreen) {
        return new ScreenMeta(width, height, heightPerScreen, scale, rotation, scaleMode);
    }

    public ScreenMeta setScale(float scale) {
        return new ScreenMeta(width, height, heightPerScreen, scale, rotation, scaleMode);
    }

    public ScreenMeta setRotation(Rotation rotation) {
        return new ScreenMeta(width, height, heightPerScreen, scale, rotation, scaleMode);
    }

    public ScreenMeta setScaleMode(ScaleMode scaleMode) {
        return new ScreenMeta(width, height, heightPerScreen, scale, rotation, scaleMode);
    }

    public boolean zeroScale() {
        return this.width == 0 || this.height == 0;
    }
}
