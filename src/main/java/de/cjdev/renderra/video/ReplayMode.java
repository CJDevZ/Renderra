package de.cjdev.renderra.video;

public enum ReplayMode {
    NORMAL(0xFFFFFF),
    LOOP(0x55FF55);

    public static final ReplayMode[] VALUES = ReplayMode.values();

    public final int buttonColor;

    ReplayMode(int buttonColor) {
        this.buttonColor = buttonColor;
    }
}
