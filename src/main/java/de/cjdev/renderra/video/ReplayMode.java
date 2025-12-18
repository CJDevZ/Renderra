package de.cjdev.renderra.video;

public enum ReplayMode {
    NORMAL(0xFFFFFF),
    LOOP(0x55FF55);

    public final int buttonColor;

    ReplayMode(int buttonColor) {
        this.buttonColor = buttonColor;
    }
}
